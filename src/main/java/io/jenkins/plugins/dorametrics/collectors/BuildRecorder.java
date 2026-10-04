package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.Cause;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.model.Jenkins;
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

    /** What {@link #record} did with a build. */
    enum Outcome {
        /** Stored, with its commits and, for a pipeline, its stages. */
        RECORDED,
        /** Left alone: the job filter excludes it, or its name no longer belongs to this job. */
        FILTERED,
        /** Could not be written. The store logs why. */
        FAILED
    }

    /**
     * Records {@code run}, unless the configured job filter excludes it.
     *
     * <p>Returns what happened rather than nothing, because a caller importing in bulk has
     * to tell a build it stored from one it filtered out or failed to write, and a failed
     * write raises no exception: the store logs it and returns a negative id.
     */
    static Outcome record(Run<?, ?> run) {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        String jobName = run.getParent().getFullName();

        if (config != null && !config.shouldTrackJob(jobName)) {
            return Outcome.FILTERED;
        }

        MetricsStore store = MetricsStore.getInstance();
        int buildNumber = run.getNumber();
        long timestamp = run.getTimeInMillis();
        long durationMs = run.getDuration();
        Result runResult = run.getResult();
        String result = runResult != null ? runResult.toString() : "UNKNOWN";
        String triggerType = getTriggerType(run);
        String branch = getBranch(run);

        // Gathered before anything is written, so the build and everything belonging to it
        // go in as one transaction. Writing the build first and its children afterwards is
        // what let a re-record strand the old ones.
        List<MetricsStore.CommitRow> commits = collectCommitData(run);
        List<MetricsStore.StageRow> stages = run instanceof WorkflowRun
                ? collectStageData((WorkflowRun) run)
                : Collections.emptyList();

        if (!isStillNamed(run.getParent(), jobName)) {
            // the job was deleted or renamed since; its old name may belong to another job now
            LOGGER.fine("Not recording " + run.getFullDisplayName() + ", " + jobName + " is no longer this job");
            return Outcome.FILTERED;
        }
        long buildId = store.recordBuild(jobName, buildNumber, timestamp, durationMs,
                result, triggerType, branch, stages, commits);
        if (buildId < 0) {
            return Outcome.FAILED;
        }

        LOGGER.fine("Collected metrics for " + jobName + "#" + buildNumber
                + " (" + result + ", " + durationMs + "ms)");
        return Outcome.RECORDED;
    }

    /** Whether the name still resolves to this exact job, looked up as SYSTEM. */
    static boolean isStillNamed(Job<?, ?> job, String name) {
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            return Jenkins.get().getItemByFullName(name, Job.class) == job;
        }
    }

    private static List<MetricsStore.CommitRow> collectCommitData(Run<?, ?> run) {
        List<MetricsStore.CommitRow> commits = new ArrayList<>();
        int withoutId = 0;
        try {
            for (ChangeLogSet<? extends ChangeLogSet.Entry> changeSet : getChangeSets(run)) {
                for (ChangeLogSet.Entry entry : changeSet) {
                    // Not every SCM gives an entry an id, and commit_sha is NOT NULL. The entry
                    // is dropped on its own rather than taking the whole build's write with it.
                    String commitId = entry.getCommitId();
                    if (commitId == null) {
                        withoutId++;
                        continue;
                    }
                    commits.add(new MetricsStore.CommitRow(commitId,
                            entry.getAuthor().getFullName(), entry.getTimestamp()));
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not collect commit data for " + run.getFullDisplayName(), e);
        }
        if (withoutId > 0) {
            LOGGER.fine("Skipped " + withoutId + " change log entries without a commit id for "
                    + run.getFullDisplayName());
        }
        return commits;
    }

    private static List<ChangeLogSet<? extends ChangeLogSet.Entry>> getChangeSets(Run<?, ?> run) {
        if (run instanceof RunWithSCM<?, ?> rws) {
            return rws.getChangeSets();
        }
        return Collections.emptyList();
    }

    private static List<MetricsStore.StageRow> collectStageData(WorkflowRun run) {
        List<MetricsStore.StageRow> stages = new ArrayList<>();
        try {
            FlowExecution execution = run.getExecution();
            if (execution == null) return stages;

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

                stages.add(new MetricsStore.StageRow(label.getDisplayName(), duration,
                        hasError ? "FAILURE" : "SUCCESS"));
            }

            LOGGER.fine("Collected " + stages.size() + " stages for " + run.getFullDisplayName());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Could not collect stage data for " + run.getFullDisplayName(), e);
        }
        return stages;
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
            // A multibranch branch job is named after its branch, a job in an ordinary folder is not
            if (inMultibranchProject(run.getParent())) {
                return run.getParent().getName();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Could not determine branch for " + run.getFullDisplayName(), e);
        }
        return null;
    }

    /** Checked by class name, so the plugin does not need branch-api to tell. */
    private static boolean inMultibranchProject(hudson.model.Job<?, ?> job) {
        for (Class<?> c = job.getParent().getClass(); c != null; c = c.getSuperclass()) {
            if ("jenkins.branch.MultiBranchProject".equals(c.getName())) {
                return true;
            }
        }
        return false;
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
