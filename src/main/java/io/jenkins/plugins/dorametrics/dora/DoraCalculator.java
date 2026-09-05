package io.jenkins.plugins.dorametrics.dora;

import io.jenkins.plugins.dorametrics.DisabledPipelines;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.store.MetricsStore.BuildRecord;
import io.jenkins.plugins.dorametrics.util.DurationFormatter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calculates all four DORA metrics from stored build data.
 * Uses optimized SQL aggregates where possible.
 */
public class DoraCalculator {

    public enum DoraBand {
        ELITE("Elite", "#1a7f37"),
        HIGH("High", "#2da44e"),
        MEDIUM("Medium", "#bf8700"),
        LOW("Low", "#cf222e");

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

    public DoraCalculator() {
        this(MetricsStore.getInstance(), DoraGlobalConfiguration.get());
    }

    /** Constructor for testing with injected dependencies. */
    public DoraCalculator(MetricsStore store, DoraGlobalConfiguration config) {
        this(store, config, DisabledPipelines.names(config, store));
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
        long successCount = store.countSuccessfulBuilds(fromMs, toMs, jobPattern, excludedJobs);
        double days = Math.max(1, (toMs - fromMs) / (double) 86400_000);
        double frequency = successCount / days;

        double elite = config != null ? config.getDfEliteThreshold() : 1.0;
        double high = config != null ? config.getDfHighThreshold() : 0.142;
        double medium = config != null ? config.getDfMediumThreshold() : 0.033;

        DoraBand band = frequency >= elite ? DoraBand.ELITE
                : frequency >= high ? DoraBand.HIGH
                : frequency >= medium ? DoraBand.MEDIUM
                : DoraBand.LOW;

        return new DoraMetric("Deployment Frequency",
                String.format("%.1f/day", frequency), band, frequency);
    }

    /**
     * Lead Time for Changes: avg time from commit to deploy.
     */
    public DoraMetric leadTimeForChanges(long fromMs, long toMs, String jobPattern) {
        double avgMs = store.avgLeadTimeMs(fromMs, toMs, jobPattern, excludedJobs);

        if (avgMs <= 0) {
            return new DoraMetric("Lead Time for Changes", "N/A", DoraBand.LOW, 0);
        }

        double ltElite = config != null ? config.getLtEliteSeconds() * 1000 : 3600L * 1000;
        double ltHigh = config != null ? config.getLtHighSeconds() * 1000 : 86400L * 1000;
        double ltMedium = config != null ? config.getLtMediumSeconds() * 1000 : 604800L * 1000;

        DoraBand band = avgMs < ltElite ? DoraBand.ELITE
                : avgMs < ltHigh ? DoraBand.HIGH
                : avgMs < ltMedium ? DoraBand.MEDIUM
                : DoraBand.LOW;

        return new DoraMetric("Lead Time for Changes",
                DurationFormatter.format((long) avgMs), band, avgMs);
    }

    /**
     * MTTR: avg time from failure to next success per job.
     * Still uses row-level scan (no simple SQL aggregate for this).
     */
    public DoraMetric meanTimeToRestore(long fromMs, long toMs, String jobPattern) {
        List<BuildRecord> builds = store.getAllBuilds(fromMs, toMs, excludedJobs);
        if (!".*".equals(jobPattern) && jobPattern != null) {
            builds = builds.stream()
                    .filter(b -> b.jobName.matches(jobPattern))
                    .collect(Collectors.toList());
        }

        Map<String, List<BuildRecord>> byJob = builds.stream()
                .collect(Collectors.groupingBy(b -> b.jobName));

        List<Long> restoreTimes = new ArrayList<>();
        for (List<BuildRecord> jobBuilds : byJob.values()) {
            jobBuilds.sort(Comparator.comparingLong(b -> b.timestamp));
            Long failureStart = null;
            for (BuildRecord build : jobBuilds) {
                if (build.isFailure() && failureStart == null) {
                    failureStart = build.timestamp;
                } else if (build.isSuccess() && failureStart != null) {
                    restoreTimes.add(build.timestamp - failureStart);
                    failureStart = null;
                }
            }
        }

        if (restoreTimes.isEmpty()) {
            return new DoraMetric("Mean Time to Restore", "N/A", DoraBand.ELITE, 0);
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
     * Change Failure Rate: % of deploys that fail.
     */
    public DoraMetric changeFailureRate(long fromMs, long toMs, String jobPattern) {
        long total = store.countTotalBuilds(fromMs, toMs, jobPattern, excludedJobs);
        if (total == 0) {
            return new DoraMetric("Change Failure Rate", "N/A", DoraBand.LOW, 0);
        }

        long failures = store.countFailedBuilds(fromMs, toMs, jobPattern, excludedJobs);
        double rate = (double) failures / total * 100;

        double cfrElite = config != null ? config.getCfrElitePercent() : 5.0;
        double cfrHigh = config != null ? config.getCfrHighPercent() : 10.0;
        double cfrMedium = config != null ? config.getCfrMediumPercent() : 15.0;

        DoraBand band = rate <= cfrElite ? DoraBand.ELITE
                : rate <= cfrHigh ? DoraBand.HIGH
                : rate <= cfrMedium ? DoraBand.MEDIUM
                : DoraBand.LOW;

        return new DoraMetric("Change Failure Rate",
                String.format("%.1f%%", rate), band, rate);
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
