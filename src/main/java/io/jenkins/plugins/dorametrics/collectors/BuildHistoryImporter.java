package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Imports builds that are already on disk, for instances where the plugin was installed
 * after the builds ran, or where a job did not match the filters at the time.
 *
 * <p>Each build is handed to {@link BuildRecorder#record(Run)}, the same entry point the
 * listener uses, so imported builds get the same filter, commit and stage handling as
 * builds recorded as they complete.
 *
 * <p>Only one import runs at a time. A second caller is told the import is already
 * running rather than being allowed to double the work.
 */
public final class BuildHistoryImporter {

    private static final Logger LOGGER = Logger.getLogger(BuildHistoryImporter.class.getName());
    private static final long DAY_MS = 86_400_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Result lastResult;

    private BuildHistoryImporter() {
    }

    /** Counters for one import run. */
    public static final class Result {
        public final int jobs;
        public final int recorded;
        public final int skipped;
        public final int failed;
        public final long durationMs;

        Result(int jobs, int recorded, int skipped, int failed, long durationMs) {
            this.jobs = jobs;
            this.recorded = recorded;
            this.skipped = skipped;
            this.failed = failed;
            this.durationMs = durationMs;
        }

        @Override
        public String toString() {
            return "jobs=" + jobs + " recorded=" + recorded
                    + " skipped=" + skipped + " failed=" + failed + " in " + durationMs + "ms";
        }
    }

    /** True while an import is running. */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    /** Counters from the most recent completed import, or null if none has run. */
    public static Result getLastResult() {
        return lastResult;
    }

    /**
     * Imports every build newer than {@code days} days that is not already stored, on the
     * calling thread.
     *
     * @return the counters, or null if an import was already running
     */
    public static Result importHistory(int days) {
        if (!reserve()) {
            LOGGER.info("Build history import already running, ignoring this request");
            return null;
        }
        try {
            // The importer reads every job, including ones the requesting user cannot see.
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                return run(days);
            }
        } finally {
            release();
        }
    }

    /**
     * Starts an import on Jenkins' executor and returns at once.
     *
     * <p>The slot is taken before this returns, not when the submitted task begins, so a
     * caller that polls {@link #isRunning()} immediately afterwards sees the import in
     * progress rather than the counters of the previous one.
     *
     * @return false if an import was already running, in which case nothing was started
     */
    public static boolean startAsync(int days) {
        if (!reserve()) {
            return false;
        }
        jenkins.util.Timer.get().submit(() -> {
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                run(days);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Build history import failed", e);
            } finally {
                release();
            }
        });
        return true;
    }

    /** Takes the single-flight slot. Package private so the contract can be tested directly. */
    static boolean reserve() {
        return RUNNING.compareAndSet(false, true);
    }

    static void release() {
        RUNNING.set(false);
    }

    private static Result run(int days) {
        long startedAt = System.currentTimeMillis();
        long cutoff = startedAt - (Math.max(1, (long) days) * DAY_MS);
        MetricsStore store = MetricsStore.getInstance();

        int jobs = 0, recorded = 0, skipped = 0, failed = 0;

        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            jobs++;
            String jobName = job.getFullName();

            // One query per job rather than one per build. Builds already stored are left
            // alone: re-recording them would be wasted work, and insertBuild replaces the
            // build row with a new id, which strands the stage and commit rows it had.
            Set<Integer> alreadyStored = new HashSet<>();
            try {
                store.getBuilds(jobName, cutoff, startedAt + DAY_MS)
                        .forEach(b -> alreadyStored.add(b.buildNumber));
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Could not read stored builds for " + jobName, e);
            }

            for (Run<?, ?> run = job.getLastBuild(); run != null; run = run.getPreviousBuild()) {
                if (run.getTimeInMillis() < cutoff) {
                    break; // builds walk newest first, so everything below is older too
                }
                if (run.isBuilding() || alreadyStored.contains(run.getNumber())) {
                    skipped++;
                    continue;
                }
                try {
                    BuildRecorder.record(run);
                    recorded++;
                } catch (Exception e) {
                    failed++;
                    LOGGER.log(Level.WARNING, "Could not import " + run.getFullDisplayName(), e);
                }
            }
        }

        Result result = new Result(jobs, recorded, skipped, failed, System.currentTimeMillis() - startedAt);
        lastResult = result;
        LOGGER.info("Build history import finished: " + result);
        return result;
    }

    /** The import window, capped at the retention window so it cannot import rows cleanup would drop. */
    public static int resolveDays(DoraGlobalConfiguration config) {
        if (config == null) {
            return 30;
        }
        int days = Math.max(1, config.getHistoryImportDays());
        int retention = config.getRetentionDays();
        return retention > 0 ? Math.min(days, retention) : days;
    }
}
