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
import java.util.concurrent.atomic.AtomicInteger;
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

    // Progress is kept in atomics and read as a snapshot rather than by mutating a Result,
    // because the status endpoint reads this on a request thread while the import writes on
    // Jenkins' executor.
    private static final AtomicInteger P_JOBS = new AtomicInteger();
    private static final AtomicInteger P_RECORDED = new AtomicInteger();
    private static final AtomicInteger P_SKIPPED = new AtomicInteger();
    private static final AtomicInteger P_FAILED = new AtomicInteger();
    private static volatile long startedAt;   // 0 until the first run
    private static volatile long finishedAt;  // 0 while a run is in progress
    private static volatile boolean lastCompleted;
    private static volatile String lastError;

    private BuildHistoryImporter() {
    }

    /** Counters for one import run. */
    public static final class Result {
        public final int jobs;
        public final int recorded;
        public final int skipped;
        public final int failed;
        public final long durationMs;
        /**
         * False when the run stopped before it had walked everything, so the counters
         * describe a partial pass. Counters alone cannot say this: a run that stopped
         * before it started looks exactly like a run that found nothing to do.
         */
        public final boolean completed;
        /** The failure that ended the run, or null. */
        public final String error;

        Result(int jobs, int recorded, int skipped, int failed, long durationMs, boolean completed) {
            this(jobs, recorded, skipped, failed, durationMs, completed, null);
        }

        Result(int jobs, int recorded, int skipped, int failed, long durationMs, boolean completed,
               String error) {
            this.error = error;
            this.jobs = jobs;
            this.recorded = recorded;
            this.skipped = skipped;
            this.failed = failed;
            this.durationMs = durationMs;
            this.completed = completed;
        }

        @Override
        public String toString() {
            return "jobs=" + jobs + " recorded=" + recorded
                    + " skipped=" + skipped + " failed=" + failed
                    + (completed ? "" : " (stopped early)")
                    + (error == null ? "" : " error=" + error)
                    + " in " + durationMs + "ms";
        }
    }

    /** True while an import is running. */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    /**
     * A snapshot of the run in progress, or of the last one to finish, or null if none has
     * ever run. Reading it while an import is going returns the counts so far rather than
     * the previous run's totals.
     */
    public static Result getLastResult() {
        long begun = startedAt;
        if (begun == 0) {
            return null;
        }
        long ended = finishedAt;
        return new Result(P_JOBS.get(), P_RECORDED.get(), P_SKIPPED.get(), P_FAILED.get(),
                (ended > 0 ? ended : System.currentTimeMillis()) - begun,
                ended > 0 && lastCompleted, lastError);
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
                Result result = run(days);
                // Otherwise the automatic run still walks the whole instance later for
                // nothing. Guarded the same way, so a run that wrote nothing is not counted.
                DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
                if (config != null && !config.isHistoryImportDone()
                        && HistoryImportTask.shouldMarkDone(result)) {
                    config.markHistoryImportDone();
                }
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
        long begun = System.currentTimeMillis();
        // Clear the previous run's numbers before this one starts, so a caller polling while
        // it runs never reads the last run's totals as though they belonged to this one.
        P_JOBS.set(0);
        P_RECORDED.set(0);
        P_SKIPPED.set(0);
        P_FAILED.set(0);
        lastCompleted = false;
        lastError = null;
        finishedAt = 0;
        startedAt = begun;

        long cutoff = begun - (Math.max(1, (long) days) * DAY_MS);
        MetricsStore store = MetricsStore.getInstance();
        boolean completed = true;

        try {
            DoraGlobalConfiguration config = DoraGlobalConfiguration.get();

            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                if (shouldStop()) {
                    LOGGER.info("Build history import stopping early, Jenkins is going down");
                    completed = false;
                    break;
                }
                P_JOBS.incrementAndGet();
                String jobName = job.getFullName();

                // Asking once per job rather than once per build, so a job that is filtered out
                // never has its builds loaded at all.
                if (config != null && !config.shouldTrackJob(jobName)) {
                    continue;
                }

                // Everything for one job is wrapped, including walking its builds. A job with an
                // unreadable build must not end the whole run: if it did, the caller would never
                // mark the import done and the task would walk the instance again an hour later.
                try {
                    Set<Integer> alreadyStored = new HashSet<>();
                    store.getBuilds(jobName, cutoff, begun + DAY_MS)
                            .forEach(b -> alreadyStored.add(b.buildNumber));

                    for (Run<?, ?> r = job.getLastBuild(); r != null; r = r.getPreviousBuild()) {
                        if (shouldStop()) {
                            completed = false;
                            break;
                        }
                        if (r.getTimeInMillis() < cutoff) {
                            break; // builds walk newest first, so everything below is older too
                        }
                        if (r.isBuilding() || alreadyStored.contains(r.getNumber())) {
                            P_SKIPPED.incrementAndGet();
                            continue;
                        }
                        try {
                            switch (BuildRecorder.record(r)) {
                                case RECORDED -> P_RECORDED.incrementAndGet();
                                case FILTERED -> P_SKIPPED.incrementAndGet();
                                case FAILED -> P_FAILED.incrementAndGet();
                            }
                        } catch (Exception e) {
                            P_FAILED.incrementAndGet();
                            LOGGER.log(Level.WARNING, "Could not import " + r.getFullDisplayName(), e);
                        }
                    }
                } catch (Exception e) {
                    P_FAILED.incrementAndGet();
                    LOGGER.log(Level.WARNING, "Could not import job " + jobName, e);
                }
            }
        } catch (RuntimeException e) {
            // Recorded so the status says what went wrong rather than showing counts that
            // look like an ordinary run.
            lastError = e.toString();
            lastCompleted = false;
            finishedAt = System.currentTimeMillis();
            throw e;
        }

        lastCompleted = completed;
        finishedAt = System.currentTimeMillis();

        Result result = new Result(P_JOBS.get(), P_RECORDED.get(), P_SKIPPED.get(), P_FAILED.get(),
                finishedAt - begun, completed, null);
        LOGGER.info("Build history import finished: " + result);
        return result;
    }

    /**
     * True once the import should give up: Jenkins is going down, or the thread it runs on
     * was interrupted. Checked per job and per build, since a large instance can spend a
     * long time in this loop.
     */
    private static boolean shouldStop() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins == null || jenkins.isTerminating();
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
