package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The build history import calls {@link BuildRecorder#record} directly, without the listener,
 * and can reach a run after its job is gone. The name check has to hold on that path too.
 */
@WithJenkins
class BuildRecorderNameTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
    }

    @Test
    void recordStillStoresARunOfAnExistingJob() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("still-here");
        FreeStyleBuild run = j.buildAndAssertSuccess(job);
        assertEquals(1, rows("still-here"));

        BuildRecorder.record(run);
        assertEquals(1, rows("still-here"), "re-recording a live job's run is an upsert");
    }

    @Test
    void recordSkipsARunWhoseJobWasDeleted() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gone");
        FreeStyleBuild run = j.buildAndAssertSuccess(job);
        job.delete();
        assertEquals(0, rows("gone"), "detached at delete time");

        BuildRecorder.record(run);
        assertEquals(0, rows("gone"), "a run of a deleted job must not come back under its name");
    }

    @Test
    void recordSkipsARunWhoseJobNameNowBelongsToAnotherJob() throws Exception {
        FreeStyleProject old = j.createFreeStyleProject("taken");
        FreeStyleBuild oldRun = j.buildAndAssertSuccess(old);
        old.delete();
        j.createFreeStyleProject("taken");

        BuildRecorder.record(oldRun);
        assertEquals(0, rows("taken"), "nothing may be written under the new job's name");
    }

    private int rows(String name) {
        long now = System.currentTimeMillis();
        return MetricsStore.getInstance().getBuilds(name, now - 600000, now + 600000).size();
    }
}
