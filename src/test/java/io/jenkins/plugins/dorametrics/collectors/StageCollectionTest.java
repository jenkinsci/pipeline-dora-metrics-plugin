package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.Result;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.store.MetricsStore.BuildRecord;
import io.jenkins.plugins.dorametrics.store.MetricsStore.StageRecord;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stages are recorded per block that ran, not per name, so a stage name used
 * more than once in a build (parallel branches, a matrix, a loop) keeps every run.
 */
@WithJenkins
class StageCollectionTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
    }

    private List<StageRecord> stagesOf(String script, Result expected) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "p" + System.nanoTime());
        job.setDefinition(new CpsFlowDefinition(script, true));
        WorkflowRun run = j.buildAndAssertStatus(expected, job);
        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        List<BuildRecord> builds = store.getBuilds(job.getFullName(), now - 600_000, now + 1000);
        assertEquals(1, builds.size(), "build " + run.getNumber() + " recorded");
        return store.getStages(builds.get(0).id);
    }

    private static List<StageRecord> named(List<StageRecord> stages, String name) {
        return stages.stream().filter(s -> s.stageName.equals(name)).collect(Collectors.toList());
    }

    @Test
    void sameStageNameInParallelBranchesKeepsEveryRun() throws Exception {
        List<StageRecord> stages = stagesOf(
                """
                        parallel(
                          linux:   { stage('Test') { sleep time: 100, unit: 'MILLISECONDS' } },
                          windows: { stage('Test') { sleep 2 } }
                        )""", Result.SUCCESS);

        List<StageRecord> tests = named(stages, "Test");
        assertEquals(2, tests.size(), "both Test stages are recorded");
        assertEquals(1, named(stages, "Branch: linux").size());
        assertEquals(1, named(stages, "Branch: windows").size());
        assertEquals(4, stages.size());

        // each stage is timed from its own start to its own end
        long shorter = Math.min(tests.get(0).durationMs, tests.get(1).durationMs);
        long longer = Math.max(tests.get(0).durationMs, tests.get(1).durationMs);
        assertTrue(longer >= 2000, "long stage took " + longer + "ms");
        assertTrue(longer - shorter >= 1000, "stages took " + shorter + "ms and " + longer + "ms");
    }

    @Test
    void sameStageNameInALoopKeepsEveryRun() throws Exception {
        List<StageRecord> stages = stagesOf(
                "for (int i = 0; i < 3; i++) { stage('Deploy') { echo \"run ${i}\" } }", Result.SUCCESS);
        assertEquals(3, named(stages, "Deploy").size());
        assertEquals(3, stages.size());
    }

    @Test
    void failureIsRecordedOnTheStageThatFailedOnly() throws Exception {
        List<StageRecord> stages = stagesOf(
                """
                        parallel(
                          good: { stage('Test') { echo 'fine' } },
                          bad:  { stage('Test') { error 'boom' } }
                        )""", Result.FAILURE);

        List<StageRecord> tests = named(stages, "Test");
        assertEquals(2, tests.size());
        assertEquals(1, tests.stream().filter(s -> "FAILURE".equals(s.result)).count());
        assertEquals(1, tests.stream().filter(s -> "SUCCESS".equals(s.result)).count());
        assertEquals("SUCCESS", named(stages, "Branch: good").get(0).result);
        assertEquals("FAILURE", named(stages, "Branch: bad").get(0).result);
    }

    @Test
    void nestedAndSequentialStagesAreUnchanged() throws Exception {
        List<StageRecord> stages = stagesOf(
                "stage('Build') { echo 'b' }\n"
                + "stage('Release') { stage('Publish') { echo 'p' } }", Result.SUCCESS);
        assertEquals(1, named(stages, "Build").size());
        assertEquals(1, named(stages, "Release").size());
        assertEquals(1, named(stages, "Publish").size());
        assertEquals(3, stages.size());
    }

    @Test
    void labelledStepIsNotAStage() throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "labelled");
        job.setDefinition(new CpsFlowDefinition(
                """
                        node { stage('Build') {
                          if (isUnix()) { sh label: 'compile', script: 'true' } else { bat label: 'compile', script: 'exit 0' }
                        } }""", true));
        WorkflowRun run = j.buildAndAssertSuccess(job);

        // the step really carries a label, so this is not passing by accident
        boolean labelled = new DepthFirstScanner().allNodes(run.getExecution()).stream()
                .map(n -> n.getAction(LabelAction.class))
                .anyMatch(a -> a != null && "compile".equals(a.getDisplayName()));
        assertTrue(labelled, "the shell step has a label");

        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        List<StageRecord> stages = store.getStages(store.getBuilds("labelled", now - 600_000, now + 1000).get(0).id);
        assertEquals(1, stages.size());
        assertEquals("Build", stages.get(0).stageName);
    }
}
