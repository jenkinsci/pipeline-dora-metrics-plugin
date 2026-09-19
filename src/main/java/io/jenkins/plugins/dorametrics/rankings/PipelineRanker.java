package io.jenkins.plugins.dorametrics.rankings;

import io.jenkins.plugins.dorametrics.JobVisibility;
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
 * Ranks pipelines by various criteria using optimized SQL aggregates.
 */
public class PipelineRanker {

    private final MetricsStore store;
    // Jobs left out of every ranking, resolved once per ranker.
    private final Set<String> excludedJobs;

    public PipelineRanker() {
        this(MetricsStore.getInstance());
    }

    /** Constructor for testing. */
    public PipelineRanker(MetricsStore store) {
        this(store, JobVisibility.excludedForCurrentUser(DoraGlobalConfiguration.get(), store));
    }

    /** Constructor with an explicit set of jobs to leave out. */
    public PipelineRanker(MetricsStore store, Set<String> excludedJobs) {
        this.store = store;
        this.excludedJobs = excludedJobs;
    }

    public List<RankedPipeline> slowestPipelines(long fromMs, long toMs, int limit) {
        List<MetricsStore.JobStats> stats = store.getJobStats(fromMs, toMs, limit, "avg_dur DESC", excludedJobs);
        List<RankedPipeline> ranked = new ArrayList<>();
        for (MetricsStore.JobStats s : stats) {
            ranked.add(new RankedPipeline(s.jobName, s.avgDurationMs,
                    DurationFormatter.format((long) s.avgDurationMs), s.buildCount));
        }
        return ranked;
    }

    public List<RankedPipeline> mostFailingPipelines(long fromMs, long toMs, int limit) {
        List<MetricsStore.JobStats> stats = store.getJobStats(fromMs, toMs, limit * 2, "failures DESC", excludedJobs);
        List<RankedPipeline> ranked = new ArrayList<>();
        for (MetricsStore.JobStats s : stats) {
            double failureRate = s.buildCount > 0 ? (double) s.failureCount / s.buildCount * 100 : 0;
            ranked.add(new RankedPipeline(s.jobName, failureRate,
                    String.format("%.1f%%", failureRate), s.buildCount));
        }
        ranked.sort((a, b) -> Double.compare(b.value, a.value));
        return ranked.stream().limit(limit).collect(Collectors.toList());
    }

    public List<RankedPipeline> mostImproved(long currentFrom, long currentTo,
                                              long previousFrom, long previousTo, int limit) {
        Map<String, List<BuildRecord>> current = groupByJob(currentFrom, currentTo);
        Map<String, List<BuildRecord>> previous = groupByJob(previousFrom, previousTo);
        List<RankedPipeline> ranked = new ArrayList<>();

        for (String jobName : current.keySet()) {
            if (!previous.containsKey(jobName)) continue;

            double currentAvg = current.get(jobName).stream()
                    .mapToLong(b -> b.durationMs).average().orElse(0);
            double previousAvg = previous.get(jobName).stream()
                    .mapToLong(b -> b.durationMs).average().orElse(0);
            if (previousAvg <= 0) continue;

            double improvement = ((previousAvg - currentAvg) / previousAvg) * 100;
            ranked.add(new RankedPipeline(jobName, improvement,
                    String.format("%+.1f%%", -improvement), current.get(jobName).size()));
        }

        ranked.sort((a, b) -> Double.compare(b.value, a.value));
        return ranked.stream().limit(limit).collect(Collectors.toList());
    }

    public List<RankedPipeline> flakiestPipelines(long fromMs, long toMs, int limit) {
        Map<String, List<BuildRecord>> byJob = groupByJob(fromMs, toMs);
        List<RankedPipeline> ranked = new ArrayList<>();

        for (Map.Entry<String, List<BuildRecord>> entry : byJob.entrySet()) {
            List<BuildRecord> builds = entry.getValue();
            if (builds.size() < 3) continue;

            builds.sort(Comparator.comparingLong(b -> b.timestamp));
            int transitions = 0;
            for (int i = 1; i < builds.size(); i++) {
                if (!builds.get(i).result.equals(builds.get(i - 1).result)) {
                    transitions++;
                }
            }

            double flakyScore = (double) transitions / (builds.size() - 1) * 100;
            ranked.add(new RankedPipeline(entry.getKey(), flakyScore,
                    String.format("%.0f%% transitions", flakyScore), builds.size()));
        }

        ranked.sort((a, b) -> Double.compare(b.value, a.value));
        return ranked.stream().limit(limit).collect(Collectors.toList());
    }

    public List<RankedStage> slowestStages(long fromMs, long toMs, int limit) {
        List<MetricsStore.StageStats> stats = store.getStageStats(fromMs, toMs, limit, "avg_dur DESC", excludedJobs);
        List<RankedStage> ranked = new ArrayList<>();
        for (MetricsStore.StageStats s : stats) {
            ranked.add(new RankedStage(s.stageName, s.avgDurationMs,
                    DurationFormatter.format((long) s.avgDurationMs), s.totalRuns));
        }
        return ranked;
    }

    public List<RankedStage> mostFailingStages(long fromMs, long toMs, int limit) {
        List<MetricsStore.StageStats> stats = store.getStageStats(fromMs, toMs, limit * 2, "failures DESC", excludedJobs);
        List<RankedStage> ranked = new ArrayList<>();
        for (MetricsStore.StageStats s : stats) {
            double failureRate = s.totalRuns > 0 ? (double) s.failureCount / s.totalRuns * 100 : 0;
            ranked.add(new RankedStage(s.stageName, failureRate,
                    String.format("%.1f%%", failureRate), s.totalRuns));
        }
        ranked.sort((a, b) -> Double.compare(b.value, a.value));
        return ranked.stream().limit(limit).collect(Collectors.toList());
    }

    private Map<String, List<BuildRecord>> groupByJob(long fromMs, long toMs) {
        return store.getAllBuilds(fromMs, toMs, excludedJobs).stream()
                .collect(Collectors.groupingBy(b -> b.jobName));
    }

    public static class RankedPipeline {
        public final String jobName;
        public final double value;
        public final String displayValue;
        public final int buildCount;

        public RankedPipeline(String jobName, double value, String displayValue, int buildCount) {
            this.jobName = jobName;
            this.value = value;
            this.displayValue = displayValue;
            this.buildCount = buildCount;
        }
    }

    public static class RankedStage {
        public final String stageName;
        public final double value;
        public final String displayValue;
        public final int occurrences;

        public RankedStage(String stageName, double value, String displayValue, int occurrences) {
            this.stageName = stageName;
            this.value = value;
            this.displayValue = displayValue;
            this.occurrences = occurrences;
        }
    }
}
