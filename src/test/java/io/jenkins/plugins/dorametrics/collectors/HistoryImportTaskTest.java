package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleProject;
import hudson.model.TaskListener;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The task decides whether the first import runs and whether it is recorded as having run.
 * Getting that wrong either loses the import or repeats it hourly forever.
 */
@WithJenkins
class HistoryImportTaskTest {

    private JenkinsRule j;

    private MetricsStore store;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
    }

    private HistoryImportTask task() {
        HistoryImportTask task = Jenkins.get().getExtensionList(HistoryImportTask.class).get(0);
        assertNotNull(task, "HistoryImportTask should be registered");
        return task;
    }

    private int storedBuilds(String jobName) {
        long now = System.currentTimeMillis();
        return store.getBuilds(jobName, now - 86_400_000L, now + 86_400_000L).size();
    }

    @Test
    void runsAndMarksDoneWhenTheFlagIsOff() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("task-on");
        FreeStyleProject job = j.createFreeStyleProject("task-on");
        j.buildAndAssertSuccess(job);
        config.setExcludedJobPattern("");

        assertFalse(config.isHistoryImportDone(), "precondition: the import has not run");
        assertEquals(0, storedBuilds("task-on"), "precondition: the listener skipped it");

        task().execute(TaskListener.NULL);

        assertEquals(1, storedBuilds("task-on"), "the task should have imported the build");
        assertTrue(DoraGlobalConfiguration.get().isHistoryImportDone(), "and recorded that it ran");
    }

    @Test
    void doesNothingWhenTheFlagIsAlreadySet() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("task-off");
        FreeStyleProject job = j.createFreeStyleProject("task-off");
        j.buildAndAssertSuccess(job);
        config.setExcludedJobPattern("");

        config.markHistoryImportDone();

        task().execute(TaskListener.NULL);

        assertEquals(0, storedBuilds("task-off"), "a second run must not import anything");
    }

    /**
     * A run that wrote nothing at all is not the import having happened, so the flag stays
     * off and the next hour tries again. A partial failure does count, because walking the
     * whole instance every hour over one unreadable build costs more than it saves.
     */
    @Test
    void marksDoneOnlyWhenSomethingWasWritten() {
        assertFalse(HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(2, 0, 0, 5, 10, true)),
                "nothing written and failures: leave it to run again");

        assertTrue(HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(2, 3, 0, 1, 10, true)),
                "some written, some failed: the run happened");

        assertTrue(HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(2, 0, 4, 0, 10, true)),
                "nothing to do at all: the run still happened");
    }

    /**
     * The case that made this necessary: interrupting the thread before the task runs makes
     * the loop stop on its first check, so every counter is zero. Before completion was
     * tracked the flag was set anyway and the import never happened on that instance.
     */
    @Test
    void anInterruptedRunLeavesTheFlagOff() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("interrupted-job");
        j.buildAndAssertSuccess(j.createFreeStyleProject("interrupted-job"));
        config.setExcludedJobPattern("");

        assertFalse(config.isHistoryImportDone(), "precondition");

        Thread.currentThread().interrupt();
        try {
            task().execute(TaskListener.NULL);
        } finally {
            Thread.interrupted(); // clear it so the rest of the suite is unaffected
        }

        assertFalse(DoraGlobalConfiguration.get().isHistoryImportDone(),
                "an interrupted run must not count as the import having happened");
        assertEquals(0, storedBuilds("interrupted-job"), "and must not have imported anything");
    }

    /**
     * A run that stopped because Jenkins was going down reports zero of everything, which
     * reads exactly like a run that found nothing to import. Marking that done would mean
     * the import never happens on that instance, so completion is tracked separately from
     * the counters.
     */
    @Test
    void neverMarksDoneWhenTheRunDidNotFinish() {
        assertFalse(HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(0, 0, 0, 0, 5, false)),
                "stopped before it walked anything");

        assertFalse(HistoryImportTask.shouldMarkDone(new BuildHistoryImporter.Result(3, 7, 2, 0, 5, false)),
                "stopped part way, even having recorded plenty");
    }
}
