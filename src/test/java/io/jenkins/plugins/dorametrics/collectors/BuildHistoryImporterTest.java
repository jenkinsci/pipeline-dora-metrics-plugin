package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class BuildHistoryImporterTest {

    private JenkinsRule j;
    
    private MetricsStore store;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
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
    void importsABuildThatWasFilteredOutWhenItRan() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("late-job");

        FreeStyleProject job = j.createFreeStyleProject("late-job");
        j.buildAndAssertSuccess(job);
        assertTrue(stored("late-job").isEmpty(), "the listener should have skipped it");

        config.setExcludedJobPattern("");
        assertTrue(stored("late-job").isEmpty(), "widening the filter alone must not recover it");

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertNotNull(result);
        assertEquals(1, result.recorded, "should have recorded the one build");
        assertEquals(0, result.failed);
        assertEquals(1, stored("late-job").size());
        assertEquals("SUCCESS", stored("late-job").get(0).result);
    }

    /**
     * An import must not become a way around the job filter.
     */
    @Test
    void leavesExcludedJobsExcluded() throws Exception {
        DoraGlobalConfiguration.get().setExcludedJobPattern("secret-job");

        FreeStyleProject job = j.createFreeStyleProject("secret-job");
        j.buildAndAssertSuccess(job);

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertEquals(0, result.recorded, "a filtered build must not be counted as recorded");
        assertTrue(stored("secret-job").isEmpty(), "an excluded job must stay excluded");
    }

    /**
     * Builds the listener already stored are left alone. Re-recording them would be wasted
     * work, and insertBuild replaces the build row with a new id, stranding its stage rows.
     */
    @Test
    void skipsBuildsThatAreAlreadyStored() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("already-stored");
        j.buildAndAssertSuccess(job);
        assertEquals(1, stored("already-stored").size(), "the listener stored it");

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertEquals(0, result.recorded, "nothing left to record");
        assertTrue(result.skipped >= 1, "it should be counted as skipped");
        assertEquals(1, stored("already-stored").size(), "and still stored exactly once");
    }

    /**
     * Running the import twice must not change the store the second time.
     */
    @Test
    void importingTwiceIsANoOpTheSecondTime() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("twice-job");
        FreeStyleProject job = j.createFreeStyleProject("twice-job");
        j.buildAndAssertSuccess(job);
        config.setExcludedJobPattern("");

        BuildHistoryImporter.Result first = BuildHistoryImporter.importHistory(30);
        assertEquals(1, first.recorded);
        assertEquals(1, stored("twice-job").size());

        BuildHistoryImporter.Result second = BuildHistoryImporter.importHistory(30);
        assertEquals(0, second.recorded, "second run should record nothing");
        assertEquals(1, stored("twice-job").size(), "and must not duplicate the build");
    }

    /**
     * The window is a cutoff on build time, so a build older than it is left alone.
     * Backdating needs reflection because Run exposes no setter for its timestamp.
     */
    @Test
    void ignoresBuildsOlderThanTheWindow() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("old-job");
        FreeStyleProject job = j.createFreeStyleProject("old-job");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);
        config.setExcludedJobPattern("");

        java.lang.reflect.Field timestamp = hudson.model.Run.class.getDeclaredField("timestamp");
        timestamp.setAccessible(true);
        timestamp.setLong(build, System.currentTimeMillis() - (60L * 86_400_000L));
        assertTrue(build.getTimeInMillis() < System.currentTimeMillis() - (59L * 86_400_000L),
                "sanity: the build now reads as 60 days old");

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertEquals(0, result.recorded, "a build outside the window must not be imported");
        long now = System.currentTimeMillis();
        assertTrue(store.getBuilds("old-job", now - (90L * 86_400_000L), now).isEmpty(),
                "and nothing should have been written for it");
    }

    /**
     * The point of the import is the stage breakdown, not just a row per build. If an
     * import only wrote the build row, the dashboard would show the build with no stages
     * and nothing would fail, so this asserts the stages explicitly.
     */
    @Test
    void recordsStagesForImportedBuilds() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("staged-job");

        WorkflowJob job = j.createProject(WorkflowJob.class, "staged-job");
        job.setDefinition(new CpsFlowDefinition(
                "node { stage('Checkout') { echo 'a' }; stage('Build') { echo 'b' }; "
                + "stage('Test') { parallel('unit': { echo 'c' }, 'integration': { echo 'd' }) } }",
                true));
        j.buildAndAssertSuccess(job);
        assertTrue(stored("staged-job").isEmpty(), "the listener skipped it");

        config.setExcludedJobPattern("");
        BuildHistoryImporter.importHistory(30);

        List<MetricsStore.BuildRecord> builds = stored("staged-job");
        assertEquals(1, builds.size());
        List<MetricsStore.StageRecord> stages = store.getStages(builds.get(0).id);
        assertEquals(5, stages.size(), "three stages plus the two parallel branches");

        List<String> names = stages.stream().map(st -> st.stageName).toList();
        assertTrue(names.contains("Checkout"), "Checkout should be recorded, got " + names);
        assertTrue(names.contains("Build"), "Build should be recorded, got " + names);
        assertTrue(names.contains("Test"), "Test should be recorded, got " + names);
    }

    /**
     * An import covers builds it has already seen, so it must not keep appending stage rows
     * for them. The build row is upserted, but stages are plain inserts, so a re-import that
     * re-recorded would double them.
     */
    @Test
    void importingTwiceDoesNotDuplicateStages() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("restage-job");

        WorkflowJob job = j.createProject(WorkflowJob.class, "restage-job");
        job.setDefinition(new CpsFlowDefinition(
                "node { stage('Only') { echo 'x' } }", true));
        j.buildAndAssertSuccess(job);

        config.setExcludedJobPattern("");
        BuildHistoryImporter.importHistory(30);

        long idAfterFirst = stored("restage-job").get(0).id;
        int stagesAfterFirst = store.getStages(idAfterFirst).size();
        assertEquals(1, stagesAfterFirst, "one stage from the first import");

        BuildHistoryImporter.importHistory(30);

        assertEquals(1, stored("restage-job").size(), "the build must not be duplicated");

        // Read the id again rather than reusing it. insertBuild replaces the row and hands
        // out a new id, so checking stages under a freshly read id would pass while the old
        // rows sat orphaned under the previous one.
        long idAfterSecond = stored("restage-job").get(0).id;
        assertEquals(idAfterFirst, idAfterSecond, "the build row must not have been replaced");
        assertEquals(stagesAfterFirst,
                store.getStages(idAfterFirst).size(), "and its stages must not be appended to again");
    }

    /**
     * A build still in progress has no final result or duration, so importing it would
     * store a half-finished row. The listener records it properly when it completes.
     */
    @Test
    void skipsBuildsThatAreStillRunning() throws Exception {
        DoraGlobalConfiguration.get().setExcludedJobPattern("");

        FreeStyleProject job = j.createFreeStyleProject("running-job");
        job.getBuildersList().add(new SleepBuilder(60_000));
        FreeStyleBuild build = job.scheduleBuild2(0).waitForStart();
        try {
            assertTrue(build.isBuilding(), "the build should still be running");

            BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

            assertEquals(0, result.recorded, "a running build must not be imported");
            assertTrue(result.skipped >= 1, "and it should be counted as skipped");
            assertTrue(stored("running-job").isEmpty(), "nothing should have been stored for it");
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
    void capsTheWindowAtTheRetentionPeriod() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setRetentionDays(7);
        config.setHistoryImportDays(90);

        assertEquals(7, BuildHistoryImporter.resolveDays(config), "should be capped at retention");

        config.setRetentionDays(365);
        assertEquals(90, BuildHistoryImporter.resolveDays(config), "and left alone when it already fits");
    }

    /**
     * The endpoint reports that an import started, so the slot must be taken by the time
     * it answers. If it were only taken when the submitted task begins, a caller polling
     * straight afterwards would see "not running" and read the previous run's counters as
     * though they were this run's.
     */
    @Test
    void reserveIsHeldUntilReleased() {
        assertTrue(BuildHistoryImporter.reserve(), "the first caller takes the slot");
        assertTrue(BuildHistoryImporter.isRunning(), "and it reads as running");
        assertFalse(BuildHistoryImporter.reserve(), "a second caller is turned away");

        BuildHistoryImporter.release();

        assertFalse(BuildHistoryImporter.isRunning(), "released");
        assertTrue(BuildHistoryImporter.reserve(), "and the slot is free again");
        BuildHistoryImporter.release();
    }

    /** A synchronous import must not start while one is already reserved. */
    @Test
    void importHistoryRefusesWhileAnotherIsRunning() {
        assertTrue(BuildHistoryImporter.reserve());
        try {
            assertNull(BuildHistoryImporter.importHistory(30),
                    "should refuse rather than run concurrently");
        } finally {
            BuildHistoryImporter.release();
        }
    }

    @Test
    void reportsCountersAndIsNotRunningAfterwards() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("counted-job");
        j.buildAndAssertSuccess(job);

        BuildHistoryImporter.Result result = BuildHistoryImporter.importHistory(30);

        assertNotNull(result);
        assertTrue(result.jobs >= 1, "should have walked at least the one job");
        assertEquals(0, result.failed);
        assertTrue(result.durationMs >= 0, "duration should be recorded");
        assertEquals(result.toString(),
                BuildHistoryImporter.getLastResult().toString(), "last result should be kept for the status endpoint");
        assertFalse(BuildHistoryImporter.isRunning(), "the flag must be cleared when the run ends");
    }

    /**
     * An import run from the dashboard has to set the flag as well. Without it the automatic
     * task still walks the whole instance later, for history that has already been imported
     * by hand.
     */
    @Test
    void anOnDemandRunMarksTheImportDone() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("on-demand-job");
        j.buildAndAssertSuccess(job);

        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        assertNotNull(config);
        assertFalse(config.isHistoryImportDone(), "nothing has marked it yet");

        assertTrue(BuildHistoryImporter.startAsync(30), "the import should have started");
        waitForTheImportToFinish();

        assertTrue(config.isHistoryImportDone(),
                "an on demand run that stored something marks the import done");
    }

    /** The flag is set before the slot is released, so this does not race it. */
    private void waitForTheImportToFinish() throws Exception {
        for (int i = 0; i < 100 && BuildHistoryImporter.isRunning(); i++) {
            Thread.sleep(100);
        }
        assertFalse(BuildHistoryImporter.isRunning(), "the import should have finished");
    }
}
