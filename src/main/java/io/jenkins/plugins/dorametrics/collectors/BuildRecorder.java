package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.Cause;
import hudson.model.Result;
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
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
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
final class BuildRecorder {

    private static final Logger LOGGER = Logger.getLogger(BuildRecorder.class.getName());

    private BuildRecorder() {
    }

    /** Records {@code run}, unless the configured job filter excludes it. */
    static void record(Run<?, ?> run) {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        String jobName = run.getParent().getFullName();

        if (config != null && !config.shouldTrackJob(jobName)) {
            return;
        }

        MetricsStore store = MetricsStore.getInstance();
        int buildNumber = run.getNumber();
        long timestamp = run.getTimeInMillis();
        long durationMs = run.getDuration();
        Result runResult = run.getResult();
        String result = runResult != null ? runResult.toString() : "UNKNOWN";
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
            FlowExecution execution = run.getExecution();
            if (execution == null) return;

            DepthFirstScanner scanner = new DepthFirstScanner();
            List<FlowNode> allNodes = new ArrayList<>();
            scanner.setup(execution.getCurrentHeads());
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
                    return branchName(var, branch);
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

    /**
     * The branch as people write it, path included, so a production pattern such as
     * {@code release/.*} can match it. Only the parts that are not the branch are dropped:
     * a {@code refs/heads/} or {@code refs/remotes/<remote>/} prefix, and the remote that
     * {@code GIT_BRANCH} starts with ({@code origin/release/1.2}).
     */
    static String branchName(String variable, String value) {
        if (value.startsWith("refs/heads/")) {
            return value.substring("refs/heads/".length());
        }
        if (value.startsWith("refs/remotes/")) {
            return afterFirstSlash(value.substring("refs/remotes/".length()));
        }
        if ("GIT_BRANCH".equals(variable)) {
            return afterFirstSlash(value);
        }
        return value;
    }

    private static String afterFirstSlash(String value) {
        int slash = value.indexOf('/');
        return slash > 0 && slash < value.length() - 1 ? value.substring(slash + 1) : value;
    }

    private static String getTriggerType(Run<?, ?> run) {
        List<Cause> causes = run.getCauses();
        if (causes.isEmpty()) return "UNKNOWN";
        return triggerType(causes.get(0));
    }

    /**
     * One of USER, UPSTREAM, TIMER, SCM, REMOTE or OTHER. Plugins bring their own causes, so
     * the ones for source control events (multibranch indexing and branch events, pushes and
     * webhooks from the hosting plugins) are recognised by name and counted as SCM.
     */
    static String triggerType(Cause cause) {
        if (cause instanceof Cause.UserIdCause) return "USER";
        if (cause instanceof Cause.UpstreamCause) return "UPSTREAM";
        if (cause instanceof Cause.RemoteCause) return "REMOTE";
        String className = cause.getClass().getSimpleName();
        if (className.contains("Timer")) return "TIMER";
        if (className.contains("SCM") || className.startsWith("Branch") || className.contains("Push")
                || className.contains("WebHook") || className.contains("Webhook")
                || className.contains("PullRequest") || className.contains("MergeRequest")) {
            return "SCM";
        }
        return "OTHER";
    }
}
