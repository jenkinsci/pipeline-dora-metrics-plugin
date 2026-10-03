package io.jenkins.plugins.dorametrics.dora;

import io.jenkins.plugins.dorametrics.JobVisibility;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.store.MetricsStore.BuildRecord;
import io.jenkins.plugins.dorametrics.util.DurationFormatter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calculates all four DORA metrics from stored build data.
 * Uses optimized SQL aggregates where possible.
 */
public class DoraCalculator {

    public enum DoraBand {
        // white text on each colour reaches 4.5:1 contrast
        ELITE("Elite", "#116329"),
        HIGH("High", "#1f883d"),
        MEDIUM("Medium", "#9a6700"),
        LOW("Low", "#cf222e"),
        /** Nothing to rate yet, such as no deployments or no failures in the period. */
        NONE("N/A", "#6e7781");

        public final String label;
        public final String color;

        DoraBand(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

    private final MetricsStore store;
    private final DoraGlobalConfiguration config;
    // Jobs left out of every calculation, resolved once per calculator.
    private final Set<String> excludedJobs;
    /** Set for a single job's figures, which can then be read by job name rather than by pattern. */
    private String onlyJob;

    public DoraCalculator() {
        this(MetricsStore.getInstance(), DoraGlobalConfiguration.get());
    }

    /** Constructor for testing with injected dependencies. */
    public DoraCalculator(MetricsStore store, DoraGlobalConfiguration config) {
        this(store, config, JobVisibility.excludedForCurrentUser(config, store));
    }

    /** Constructor with an explicit set of jobs to leave out. */
    public DoraCalculator(MetricsStore store, DoraGlobalConfiguration config, Set<String> excludedJobs) {
        this.store = store;
        this.config = config;
        this.excludedJobs = excludedJobs;
    }

    /**
     * Deployment Frequency: successful deploys per day.
     */
    public DoraMetric deploymentFrequency(long fromMs, long toMs, String jobPattern) {
        long successCount = store.countSuccessfulBuilds(fromMs, toMs, jobPattern, excludedJobs, branchPattern());
        double days = Math.max(1, (toMs - fromMs) / (double) 86400_000);
        double frequency = successCount / days;

        double elite = config != null ? config.getDfEliteThreshold() : 1.0;
        double high = config != null ? config.getDfHighThreshold() : 0.142;
        double medium = config != null ? config.getDfMediumThreshold() : 0.033;

        DoraBand band = frequency >= elite ? DoraBand.ELITE
                : frequency >= high ? DoraBand.HIGH
                : frequency >= medium ? DoraBand.MEDIUM
                : DoraBand.LOW;

        return new DoraMetric("Deployment Frequency", frequencyText(frequency), band, frequency);
    }

    /**
     * Lead Time for Changes: avg time from commit to deploy.
     */
    public DoraMetric leadTimeForChanges(long fromMs, long toMs, String jobPattern) {
        double avgMs = store.avgLeadTimeMs(fromMs, toMs, jobPattern, excludedJobs, branchPattern());

        if (avgMs <= 0) {
            return new DoraMetric("Lead Time for Changes", "N/A", DoraBand.NONE, 0);
        }

        double ltElite = config != null ? config.getLtEliteSeconds() * 1000 : 86400L * 1000;
        double ltHigh = config != null ? config.getLtHighSeconds() * 1000 : 604800L * 1000;
        double ltMedium = config != null ? config.getLtMediumSeconds() * 1000 : 2592000L * 1000;

        DoraBand band = avgMs < ltElite ? DoraBand.ELITE
                : avgMs < ltHigh ? DoraBand.HIGH
                : avgMs < ltMedium ? DoraBand.MEDIUM
                : DoraBand.LOW;

        return new DoraMetric("Lead Time for Changes",
                DurationFormatter.format((long) avgMs), band, avgMs);
    }

    /**
     * MTTR: average time from the first failure of a run of failures until the build that
     * fixed it finished. A run of failures that began before the window counts when its fix
     * lands inside the window. One that is still open is not counted: it has no end yet.
     */
    public DoraMetric meanTimeToRestore(long fromMs, long toMs, String jobPattern) {
        List<Long> restoreTimes = restores(buildsFor(fromMs, toMs, jobPattern), fromMs).stream()
                .map(r -> r[1]).collect(Collectors.toList());

        if (restoreTimes.isEmpty()) {
            return new DoraMetric("Mean Time to Restore", "N/A", DoraBand.NONE, 0);
        }

        double avgMs = restoreTimes.stream().mapToLong(Long::longValue).average().orElse(0);

        double mttrElite = config != null ? config.getMttrEliteSeconds() * 1000 : 3600L * 1000;
        double mttrHigh = config != null ? config.getMttrHighSeconds() * 1000 : 86400L * 1000;
        double mttrMedium = config != null ? config.getMttrMediumSeconds() * 1000 : 604800L * 1000;

        DoraBand band = avgMs < mttrElite ? DoraBand.ELITE
                : avgMs < mttrHigh ? DoraBand.HIGH
                : avgMs < mttrMedium ? DoraBand.MEDIUM
                : DoraBand.LOW;

        return new DoraMetric("Mean Time to Restore",
                DurationFormatter.format((long) avgMs), band, avgMs);
    }

    /**
     * Change Failure Rate: failed deployments as a share of all deployments. Aborted and
     * not-built builds deployed nothing, so they count toward neither.
     */
    public DoraMetric changeFailureRate(long fromMs, long toMs, String jobPattern) {
        long total = store.countDeployments(fromMs, toMs, jobPattern, excludedJobs, branchPattern());
        if (total == 0) {
            return new DoraMetric("Change Failure Rate", "N/A", DoraBand.NONE, 0);
        }

        long failures = store.countFailedBuilds(fromMs, toMs, jobPattern, excludedJobs, branchPattern());
        double rate = (double) failures / total * 100;

        double cfrElite = config != null ? config.getCfrElitePercent() : 5.0;
        double cfrHigh = config != null ? config.getCfrHighPercent() : 10.0;
        double cfrMedium = config != null ? config.getCfrMediumPercent() : 15.0;

        DoraBand band = rate <= cfrElite ? DoraBand.ELITE
                : rate <= cfrHigh ? DoraBand.HIGH
                : rate <= cfrMedium ? DoraBand.MEDIUM
                : DoraBand.LOW;

        return new DoraMetric("Change Failure Rate",
                String.format(Locale.ROOT, "%.1f%%", rate), band, rate);
    }

    /**
     * The builds in the window that the metrics look at: this calculator's exclusions, the job
     * pattern, and the production branches when only those count.
     */
    private List<BuildRecord> buildsFor(long fromMs, long toMs, String jobPattern) {
        List<BuildRecord> builds = onlyJob != null
                ? store.getBuilds(onlyJob, fromMs, toMs)
                : store.getAllBuilds(fromMs, toMs, excludedJobs);
        if (!".*".equals(jobPattern) && jobPattern != null && !builds.isEmpty()) {
            java.util.regex.Pattern job = java.util.regex.Pattern.compile(jobPattern);
            builds = builds.stream()
                    .filter(b -> job.matcher(b.jobName).matches())
                    .collect(Collectors.toList());
        }
        String branchPattern = branchPattern();
        if (branchPattern != null) {
            builds = builds.stream()
                    .filter(b -> MetricsStore.isOnBranch(b, branchPattern))
                    .collect(Collectors.toList());
        }
        return builds;
    }

    /**
     * Every recovery among {@code builds} as {@code [start of the fixing build, time to restore]}:
     * from the first failure of a run of failures until the build that fixed it finished.
     */
    private List<long[]> restores(List<BuildRecord> builds, long fromMs) {
        Map<String, List<BuildRecord>> byJob = builds.stream()
                .collect(Collectors.groupingBy(b -> b.jobName));
        Map<String, Long> openAtStart = store.failureStreaksOpenAt(fromMs, excludedJobs, branchPattern());

        List<long[]> restores = new ArrayList<>();
        for (Map.Entry<String, List<BuildRecord>> entry : byJob.entrySet()) {
            List<BuildRecord> jobBuilds = entry.getValue();
            jobBuilds.sort(Comparator.comparingLong(b -> b.timestamp));
            Long failureStart = openAtStart.get(entry.getKey());
            for (BuildRecord build : jobBuilds) {
                if (build.isFailure() && failureStart == null) {
                    failureStart = build.timestamp;
                } else if (build.isSuccess() && failureStart != null) {
                    restores.add(new long[] {build.timestamp, build.timestamp + build.durationMs - failureStart});
                    failureStart = null;
                }
            }
        }
        return restores;
    }

    /**
     * Narrows this calculator's daily figures to one job, read by its name. The job pattern
     * passed alongside still has to match only that job.
     */
    public DoraCalculator forJob(String jobName) {
        this.onlyJob = jobName;
        return this;
    }

    /** The four metrics for one day, as the cards show them for a whole period. */
    public static final class Day {
        private int deployments;
        private int deploymentAttempts;
        private int failures;
        private long leadTimeTotal;
        private int leadTimeCount;
        private long restoreTotal;
        private int restoreCount;

        public int deployments() {
            return deployments;
        }

        /** Failed deployments as a percentage of deployments, or null with none that day. */
        public Double changeFailureRate() {
            return deploymentAttempts == 0 ? null : failures * 100.0 / deploymentAttempts;
        }

        public Long leadTimeMs() {
            return leadTimeCount == 0 ? null : leadTimeTotal / leadTimeCount;
        }

        public Long restoreTimeMs() {
            return restoreCount == 0 ? null : restoreTotal / restoreCount;
        }
    }

    /**
     * The four metrics day by day across the window, every day present, each build counted on
     * the day it started in {@code zone}, with the same filters as the whole-period metrics.
     */
    public java.util.SortedMap<LocalDate, Day> dailyMetrics(long fromMs, long toMs, String jobPattern, ZoneId zone) {
        java.util.SortedMap<LocalDate, Day> days = new java.util.TreeMap<>();
        LocalDate last = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate();
        for (LocalDate d = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate(); !d.isAfter(last); d = d.plusDays(1)) {
            days.put(d, new Day());
        }
        List<BuildRecord> builds = buildsFor(fromMs, toMs, jobPattern);
        for (BuildRecord b : builds) {
            Day day = days.get(Instant.ofEpochMilli(b.timestamp).atZone(zone).toLocalDate());
            if (day == null) continue;
            if (b.isSuccess()) day.deployments++;
            if (b.isFailure()) day.failures++;
            if (b.isSuccess() || b.isFailure() || "UNSTABLE".equals(b.result)) day.deploymentAttempts++;
        }
        for (long[] lead : store.leadTimes(fromMs, toMs, jobPattern, excludedJobs, branchPattern())) {
            Day day = days.get(Instant.ofEpochMilli(lead[0]).atZone(zone).toLocalDate());
            if (day == null) continue;
            day.leadTimeTotal += lead[1];
            day.leadTimeCount++;
        }
        for (long[] restore : restores(builds, fromMs)) {
            Day day = days.get(Instant.ofEpochMilli(restore[0]).atZone(zone).toLocalDate());
            if (day == null) continue;
            day.restoreTotal += restore[1];
            day.restoreCount++;
        }
        return days;
    }

    /**
     * Deploys per day, per week or per month, whichever keeps the number at one or more, so a
     * team deploying monthly does not read "0.0/day" next to a Medium band.
     */
    static String frequencyText(double perDay) {
        if (perDay >= 1 || perDay == 0) {
            return String.format(Locale.ROOT, "%.1f/day", perDay);
        }
        if (perDay * 7 >= 1) {
            return String.format(Locale.ROOT, "%.1f/week", perDay * 7);
        }
        return String.format(Locale.ROOT, "%.1f/month", perDay * 30);
    }

    /**
     * The branches that count, or null for all of them. "Track All Branches" off means only
     * builds on a branch matching "Production Branch Pattern" count toward the four metrics.
     */
    private String branchPattern() {
        if (config == null || config.isTrackAllBranches()) {
            return null;
        }
        String pattern = config.getProductionBranchPattern();
        return pattern == null || pattern.isEmpty() ? null : pattern;
    }

    public static class DoraMetric {
        public final String name;
        public final String displayValue;
        public final DoraBand band;
        public final double rawValue;

        public DoraMetric(String name, String displayValue, DoraBand band, double rawValue) {
            this.name = name;
            this.displayValue = displayValue;
            this.band = band;
            this.rawValue = rawValue;
        }
    }
}
