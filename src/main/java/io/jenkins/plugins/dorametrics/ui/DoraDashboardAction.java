package io.jenkins.plugins.dorametrics.ui;

import hudson.Extension;
import hudson.model.RootAction;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraMetric;
import io.jenkins.plugins.dorametrics.rankings.PipelineRanker;
import io.jenkins.plugins.dorametrics.rankings.PipelineRanker.RankedPipeline;
import io.jenkins.plugins.dorametrics.rankings.PipelineRanker.RankedStage;
import jenkins.model.Jenkins;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Dashboard UI at /dora-metrics/. Data methods called from Jelly template.
 * API endpoints are handled by {@link DoraApiAction} at /dora-api/.
 */
@Extension
public class DoraDashboardAction implements RootAction {

    @Override
    public String getIconFileName() { return "symbol-bar-chart-outline plugin-ionicons-api"; }

    @Override
    public String getDisplayName() { return "DORA Metrics"; }

    @Override
    public String getUrlName() { return "dora-metrics"; }

    /** Whether the current user may trigger a build history import. Used by index.jelly. */
    @SuppressWarnings("unused") // called from Jelly
    public boolean isCanImport() {
        return Jenkins.get().hasPermission(Jenkins.ADMINISTER);
    }

    // === Dashboard data for Jelly ===

    private static final DoraMetric EMPTY_METRIC =
            new DoraMetric("N/A", "N/A", DoraCalculator.DoraBand.LOW, 0);

    public DoraMetric getDeploymentFrequency() {
        try { return new DoraCalculator().deploymentFrequency(thirtyDaysAgo(), now(), getPattern()); }
        catch (Exception e) { return EMPTY_METRIC; }
    }

    public DoraMetric getLeadTime() {
        try { return new DoraCalculator().leadTimeForChanges(thirtyDaysAgo(), now(), getPattern()); }
        catch (Exception e) { return EMPTY_METRIC; }
    }

    public DoraMetric getMttr() {
        try { return new DoraCalculator().meanTimeToRestore(thirtyDaysAgo(), now(), getPattern()); }
        catch (Exception e) { return EMPTY_METRIC; }
    }

    public DoraMetric getChangeFailureRate() {
        try { return new DoraCalculator().changeFailureRate(thirtyDaysAgo(), now(), getPattern()); }
        catch (Exception e) { return EMPTY_METRIC; }
    }

    public List<RankedPipeline> getSlowestPipelines() {
        try { return filterVisible(new PipelineRanker().slowestPipelines(thirtyDaysAgo(), now(), getTopN())); }
        catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    public List<RankedPipeline> getMostFailingPipelines() {
        try { return filterVisible(new PipelineRanker().mostFailingPipelines(thirtyDaysAgo(), now(), getTopN())); }
        catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    public List<RankedPipeline> getMostImprovedPipelines() {
        try {
            long n = now(); long ago = thirtyDaysAgo();
            return filterVisible(new PipelineRanker().mostImproved(ago, n, ago - (30L * 86400_000), ago, getTopN()));
        } catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    public List<RankedPipeline> getFlakiestPipelines() {
        try { return filterVisible(new PipelineRanker().flakiestPipelines(thirtyDaysAgo(), now(), getTopN())); }
        catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    public List<RankedStage> getSlowestStages() {
        try { return new PipelineRanker().slowestStages(thirtyDaysAgo(), now(), getTopN()); }
        catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    public List<RankedStage> getMostFailingStages() {
        try { return new PipelineRanker().mostFailingStages(thirtyDaysAgo(), now(), getTopN()); }
        catch (Exception e) { return java.util.Collections.emptyList(); }
    }

    /**
     * Convert job full name to Jenkins URL path for drill-down links.
     * e.g. "production/api-gateway" -> "job/production/job/api-gateway"
     */
    public String jobUrl(String jobName) {
        if (jobName == null) return "";
        return "job/" + jobName.replace("/", "/job/");
    }

    /**
     * Filter out jobs the current user does not have permission to see.
     */
    private List<RankedPipeline> filterVisible(List<RankedPipeline> pipelines) {
        Jenkins jenkins = Jenkins.get();
        return pipelines.stream()
                .filter(p -> DoraApiAction.isVisibleItem(jenkins, p.jobName))
                .collect(Collectors.toList());
    }

    // === Helpers ===

    private String getPattern() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        return config != null ? config.getProductionJobPattern() : ".*";
    }

    private int getTopN() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        return config != null ? config.getDashboardTopN() : 10;
    }

    private long now() { return System.currentTimeMillis(); }

    private long thirtyDaysAgo() { return now() - (30L * 86400_000); }
}
