package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BuildHistoryImporterTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
    }

    private List<MetricsStore.BuildRecord> stored(String jobName) {
        long now = System.currentTimeMillis();
        return store.getBuilds(jobName, now - 86_400_000L, now + 86_400_000L);
    }

    /**
     * The case the feature exists for: a build that did not match the job filter when it
     * ran is never stored, and widening the filter afterwards does not bring it back on
     * its own. The import is what recovers it.
     */
    @Test
    public void importsABuildThatWasFilteredOutWhenItRan() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("late-job");

        FreeStyleProject job = j.createFreeStyleProject("late-job");
        j.buildAndAssertSuccess(job);
        assertTrue("the listener should have skipped it", stored("late-job").isEmpty());

        config.setExcludedJobPattern("");
        assertTrue("widening the filter alone must not recover it", stored("late-job").isEmpty());

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertNotNull(result);
        assertEquals("should have recorded the one build", 1, result.recorded);
        assertEquals(0, result.failed);
        assertEquals(1, stored("late-job").size());
        assertEquals("SUCCESS", stored("late-job").get(0).result);
    }

    /**
     * An import must not become a way around the job filter.
     */
    @Test
    public void leavesExcludedJobsExcluded() throws Exception {
        DoraGlobalConfiguration.get().setExcludedJobPattern("secret-job");

        FreeStyleProject job = j.createFreeStyleProject("secret-job");
        j.buildAndAssertSuccess(job);

        BuildHistoryImporter.importHistory(30);

        assertTrue("an excluded job must stay excluded", stored("secret-job").isEmpty());
    }

    /**
     * Builds the listener already stored are left alone. Re-recording them would be wasted
     * work, and insertBuild replaces the build row with a new id, stranding its stage rows.
     */
    @Test
    public void skipsBuildsThatAreAlreadyStored() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("already-stored");
        j.buildAndAssertSuccess(job);
        assertEquals("the listener stored it", 1, stored("already-stored").size());

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertEquals("nothing left to record", 0, result.recorded);
        assertTrue("it should be counted as skipped", result.skipped >= 1);
        assertEquals("and still stored exactly once", 1, stored("already-stored").size());
    }

    /**
     * Running the import twice must not change the store the second time.
     */
    @Test
    public void importingTwiceIsANoOpTheSecondTime() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("twice-job");
        FreeStyleProject job = j.createFreeStyleProject("twice-job");
        j.buildAndAssertSuccess(job);
        config.setExcludedJobPattern("");

        BuildHistoryImporter.Result first = BuildHistoryImporter.importHistory(30);
        assertEquals(1, first.recorded);
        assertEquals(1, stored("twice-job").size());

        BuildHistoryImporter.Result second = BuildHistoryImporter.importHistory(30);
        assertEquals("second run should record nothing", 0, second.recorded);
        assertEquals("and must not duplicate the build", 1, stored("twice-job").size());
    }

    /**
     * The window is a cutoff on build time, so a build older than it is left alone.
     * Backdating needs reflection because Run exposes no setter for its timestamp.
     */
    @Test
    public void ignoresBuildsOlderThanTheWindow() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("old-job");
        FreeStyleProject job = j.createFreeStyleProject("old-job");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);
        config.setExcludedJobPattern("");

        java.lang.reflect.Field timestamp = hudson.model.Run.class.getDeclaredField("timestamp");
        timestamp.setAccessible(true);
        timestamp.setLong(build, System.currentTimeMillis() - (60L * 86_400_000L));
        assertTrue("sanity: the build now reads as 60 days old",
                build.getTimeInMillis() < System.currentTimeMillis() - (59L * 86_400_000L));

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertEquals("a build outside the window must not be imported", 0, result.recorded);
        long now = System.currentTimeMillis();
        assertTrue("and nothing should have been written for it",
                store.getBuilds("old-job", now - (90L * 86_400_000L), now).isEmpty());
    }

    /**
     * The point of the import is the stage breakdown, not just a row per build. If an
     * import only wrote the build row, the dashboard would show the build with no stages
     * and nothing would fail, so this asserts the stages explicitly.
     */
    @Test
    public void recordsStagesForImportedBuilds() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("staged-job");

        WorkflowJob job = j.createProject(WorkflowJob.class, "staged-job");
        job.setDefinition(new CpsFlowDefinition(
                "node { stage('Checkout') { echo 'a' }; stage('Build') { echo 'b' }; "
                + "stage('Test') { parallel('unit': { echo 'c' }, 'integration': { echo 'd' }) } }",
                true));
        j.buildAndAssertSuccess(job);
        assertTrue("the listener skipped it", stored("staged-job").isEmpty());

        config.setExcludedJobPattern("");
        BuildHistoryImporter.importHistory(30);

        List<MetricsStore.BuildRecord> builds = stored("staged-job");
        assertEquals(1, builds.size());
        List<MetricsStore.StageRecord> stages = store.getStages(builds.get(0).id);
        assertEquals("three stages plus the two parallel branches", 5, stages.size());

        List<String> names = stages.stream().map(st -> st.stageName).collect(Collectors.toList());
        assertTrue("Checkout should be recorded, got " + names, names.contains("Checkout"));
        assertTrue("Build should be recorded, got " + names, names.contains("Build"));
        assertTrue("Test should be recorded, got " + names, names.contains("Test"));
    }

    /**
     * An import covers builds it has already seen, so it must not keep appending stage rows
     * for them. The build row is upserted, but stages are plain inserts, so a re-import that
     * re-recorded would double them.
     */
    @Test
    public void importingTwiceDoesNotDuplicateStages() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("restage-job");

        WorkflowJob job = j.createProject(WorkflowJob.class, "restage-job");
        job.setDefinition(new CpsFlowDefinition(
                "node { stage('Only') { echo 'x' } }", true));
        j.buildAndAssertSuccess(job);

        config.setExcludedJobPattern("");
        BuildHistoryImporter.importHistory(30);

        long buildId = stored("restage-job").get(0).id;
        int afterFirst = store.getStages(buildId).size();
        assertEquals("one stage from the first import", 1, afterFirst);

        BuildHistoryImporter.importHistory(30);

        assertEquals("the build must not be duplicated", 1, stored("restage-job").size());
        assertEquals("and its stages must not be appended to again",
                afterFirst, store.getStages(stored("restage-job").get(0).id).size());
    }

    /**
     * A build still in progress has no final result or duration, so importing it would
     * store a half-finished row. The listener records it properly when it completes.
     */
    @Test
    public void skipsBuildsThatAreStillRunning() throws Exception {
        DoraGlobalConfiguration.get().setExcludedJobPattern("");

        FreeStyleProject job = j.createFreeStyleProject("running-job");
        job.getBuildersList().add(new SleepBuilder(60_000));
        FreeStyleBuild build = job.scheduleBuild2(0).waitForStart();
        try {
            assertTrue("the build should still be running", build.isBuilding());

            BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

            assertEquals("a running build must not be imported", 0, result.recorded);
            assertTrue("and it should be counted as skipped", result.skipped >= 1);
            assertTrue("nothing should have been stored for it", stored("running-job").isEmpty());
        } finally {
            build.doStop();
            j.waitForCompletion(build);
        }
    }

    /**
     * The window must never exceed retention, or the import would write rows the next
     * cleanup pass would delete.
     */
    @Test
    public void capsTheWindowAtTheRetentionPeriod() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setRetentionDays(7);
        config.setHistoryImportDays(90);

        assertEquals("should be capped at retention", 7, BuildHistoryImporter.resolveDays(config));

        config.setRetentionDays(365);
        assertEquals("and left alone when it already fits", 90, BuildHistoryImporter.resolveDays(config));
    }

    @Test
    public void reportsCountersAndIsNotRunningAfterwards() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("counted-job");
        j.buildAndAssertSuccess(job);

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertNotNull(result);
        assertTrue("should have walked at least the one job", result.jobs >= 1);
        assertEquals(0, result.failed);
        assertTrue("duration should be recorded", result.durationMs >= 0);
        assertEquals("last result should be kept for the status endpoint",
                result.toString(), BuildHistoryImporter.getLastResult().toString());
        assertTrue("the flag must be cleared when the run ends", !BuildHistoryImporter.isRunning());
    }
}
