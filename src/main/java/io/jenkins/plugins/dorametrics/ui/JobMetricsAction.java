package io.jenkins.plugins.dorametrics.ui;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraMetric;
import io.jenkins.plugins.dorametrics.rankings.PipelineRanker.RankedStage;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.store.MetricsStore.BuildRecord;
import io.jenkins.plugins.dorametrics.util.DurationFormatter;
import jenkins.model.TransientActionFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a "DORA Metrics" tab to each job page.
 * Caches build data per request to avoid repeated DB queries.
 */
public class JobMetricsAction implements Action {

    private final Job<?, ?> job;
    private transient List<BuildRecord> cachedBuilds;

    public JobMetricsAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override
    public String getIconFileName() { return "symbol-bar-chart-outline plugin-ionicons-api"; }

    @Override
    public String getDisplayName() { return "DORA Metrics"; }

    @Override
    public String getUrlName() { return "dora-metrics"; }

    public String getJobName() { return job.getFullName(); }

    /**
     * The job's own page always shows its own numbers, even when the job is
     * disabled and left out of the global metrics.
     */
    private DoraCalculator perJobCalculator() {
        return new DoraCalculator(MetricsStore.getInstance(), DoraGlobalConfiguration.get(), java.util.Collections.emptySet());
    }

    private String jobPattern() {
        return "^" + java.util.regex.Pattern.quote(job.getFullName()) + "$";
    }

    private List<BuildRecord> getBuilds() {
        if (cachedBuilds == null) {
            cachedBuilds = MetricsStore.getInstance()
                    .getBuilds(job.getFullName(), thirtyDaysAgo(), now());
        }
        return cachedBuilds;
    }

    public DoraMetric getDeploymentFrequency() {
        return perJobCalculator().deploymentFrequency(thirtyDaysAgo(), now(), jobPattern());
    }

    public DoraMetric getLeadTime() {
        return perJobCalculator().leadTimeForChanges(thirtyDaysAgo(), now(), jobPattern());
    }

    public DoraMetric getMttr() {
        return perJobCalculator().meanTimeToRestore(thirtyDaysAgo(), now(), jobPattern());
    }

    public DoraMetric getChangeFailureRate() {
        return perJobCalculator().changeFailureRate(thirtyDaysAgo(), now(), jobPattern());
    }

    public List<RankedStage> getSlowestStages() {
        Map<String, List<Long>> stageDurations = new LinkedHashMap<>();

        for (BuildRecord build : getBuilds()) {
            for (MetricsStore.StageRecord stage : MetricsStore.getInstance().getStages(build.id)) {
                stageDurations.computeIfAbsent(stage.stageName, k -> new ArrayList<>())
                        .add(stage.durationMs);
            }
        }

        List<RankedStage> ranked = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : stageDurations.entrySet()) {
            double avg = entry.getValue().stream().mapToLong(Long::longValue).average().orElse(0);
            ranked.add(new RankedStage(entry.getKey(), avg,
                    DurationFormatter.format((long) avg), entry.getValue().size()));
        }

        ranked.sort((a, b) -> Double.compare(b.value, a.value));
        return ranked.size() > 10 ? ranked.subList(0, 10) : ranked;
    }

    public String getAvgDurationFormatted() {
        double avg = getBuilds().stream().mapToLong(b -> b.durationMs).average().orElse(0);
        return DurationFormatter.format((long) avg);
    }

    public long getTotalBuilds() { return getBuilds().size(); }

    public long getSuccessfulBuilds() {
        return getBuilds().stream().filter(BuildRecord::isSuccess).count();
    }

    public long getFailedBuilds() {
        return getBuilds().stream().filter(BuildRecord::isFailure).count();
    }

    private long now() { return System.currentTimeMillis(); }
    private long thirtyDaysAgo() { return now() - (30L * 86400_000); }

    @Extension
    public static class Factory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() { return Job.class; }

        @Override
        public Collection<? extends Action> createFor(Job target) {
            return Collections.singleton(new JobMetricsAction(target));
        }
    }
}
