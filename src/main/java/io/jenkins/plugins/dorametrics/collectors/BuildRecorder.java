package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.Cause;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import jenkins.scm.RunWithSCM;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records one build's metrics: the build row, its commits, and, for a pipeline, its stages.
 *
 * <p>Shared by {@link BuildDataCollector}, which calls {@link #record(Run)} as each build
 * finishes. The planned build history import (issue #12) will call it once per build already
 * on disk, so both paths apply the same job filter and write the same data.
 */
public final class BuildRecorder {

    private static final Logger LOGGER = Logger.getLogger(BuildRecorder.class.getName());

    private BuildRecorder() {
    }

    /** Records {@code run}, unless the configured job filter excludes it. */
    public static void record(Run<?, ?> run) {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        String jobName = run.getParent().getFullName();

        if (config != null && !config.shouldTrackJob(jobName)) {
            return;
        }

        MetricsStore store = MetricsStore.getInstance();
        int buildNumber = run.getNumber();
        long timestamp = run.getTimeInMillis();
        long durationMs = run.getDuration();
        String result = run.getResult() != null ? run.getResult().toString() : "UNKNOWN";
        String triggerType = getTriggerType(run);
        String branch = getBranch(run);

        long buildId = store.insertBuild(jobName, buildNumber, timestamp, durationMs, result, triggerType, branch);
        if (buildId < 0) return;

        collectCommitData(run, buildId, store);

        if (run instanceof WorkflowRun) {
            collectStageData((WorkflowRun) run, buildId, store);
        }

        LOGGER.fine("Collected metrics for " + jobName + "#" + buildNumber
                + " (" + result + ", " + durationMs + "ms)");
    }

    private static void collectCommitData(Run<?, ?> run, long buildId, MetricsStore store) {
        try {
            for (ChangeLogSet<? extends ChangeLogSet.Entry> changeSet : getChangeSets(run)) {
                for (ChangeLogSet.Entry entry : changeSet) {
                    store.insertCommit(buildId, entry.getCommitId(),
                            entry.getAuthor().getFullName(), entry.getTimestamp());
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not collect commit data for " + run.getFullDisplayName(), e);
        }
    }

    private static List<ChangeLogSet<? extends ChangeLogSet.Entry>> getChangeSets(Run<?, ?> run) {
        if (run instanceof RunWithSCM<?, ?> rws) {
            return rws.getChangeSets();
        }
        return Collections.emptyList();
    }

    private static void collectStageData(WorkflowRun run, long buildId, MetricsStore store) {
        try {
            if (run.getExecution() == null) return;

            DepthFirstScanner scanner = new DepthFirstScanner();
            List<FlowNode> allNodes = new ArrayList<>();
            scanner.setup(run.getExecution().getCurrentHeads());
            scanner.forEach(allNodes::add);

            // A stage is identified by its own start node, not by its name: the same
            // name can run several times in one build (parallel branches, a matrix, a loop).
            List<StepEndNode> stageEnds = new ArrayList<>();
            for (FlowNode node : allNodes) {
                if (node instanceof StepEndNode endNode && isStageOrBranch(endNode.getStartNode())) {
                    stageEnds.add(endNode);
                }
            }

            for (StepEndNode endNode : stageEnds) {
                StepStartNode startNode = endNode.getStartNode();
                LabelAction label = startNode.getAction(LabelAction.class);
                TimingAction startTiming = startNode.getAction(TimingAction.class);
                TimingAction endTiming = endNode.getAction(TimingAction.class);
                if (label == null || startTiming == null || endTiming == null) continue;

                long duration = Math.max(0, endTiming.getStartTime() - startTiming.getStartTime());
                boolean hasError = endNode.getAction(ErrorAction.class) != null;

                store.insertStage(buildId, label.getDisplayName(), duration, hasError ? "FAILURE" : "SUCCESS");
            }

            LOGGER.fine("Collected " + stageEnds.size() + " stages for " + run.getFullDisplayName());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not collect stage data for " + run.getFullDisplayName(), e);
        }
    }

    /** A stage step or a branch of a parallel step, as opposed to a step that only carries a label. */
    private static boolean isStageOrBranch(StepStartNode startNode) {
        StepDescriptor descriptor = startNode.getDescriptor();
        return startNode.getAction(org.jenkinsci.plugins.workflow.actions.ThreadNameAction.class) != null
                || descriptor == null
                || "stage".equals(descriptor.getFunctionName());
    }

    private static String getBranch(Run<?, ?> run) {
        try {
            hudson.EnvVars env = run.getEnvironment(hudson.model.TaskListener.NULL);
            String[] branchVars = {"BRANCH_NAME", "GIT_BRANCH", "GIT_LOCAL_BRANCH",
                    "SVN_BRANCH", "CHANGE_BRANCH", "BRANCH"};
            for (String var : branchVars) {
                String branch = env.get(var);
                if (branch != null && !branch.isEmpty()) {
                    if (branch.contains("/")) {
                        branch = branch.substring(branch.lastIndexOf('/') + 1);
                    }
                    return branch;
                }
            }
            // Multibranch: job name often IS the branch
            String jobName = run.getParent().getName();
            String fullName = run.getParent().getFullName();
            if (fullName.contains("/") && !fullName.equals(jobName)) {
                return jobName;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not determine branch for " + run.getFullDisplayName(), e);
        }
        return null;
    }

    private static String getTriggerType(Run<?, ?> run) {
        List<Cause> causes = run.getCauses();
        if (causes.isEmpty()) return "UNKNOWN";
        Cause cause = causes.get(0);
        if (cause instanceof Cause.UserIdCause) return "USER";
        if (cause instanceof Cause.UpstreamCause) return "UPSTREAM";
        String className = cause.getClass().getSimpleName();
        if (className.contains("Timer")) return "TIMER";
        if (className.contains("SCM")) return "SCM";
        return className;
    }
}
