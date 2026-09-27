package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;

/**
 * The build history import calls {@link BuildRecorder#record} directly, without the listener,
 * and can reach a run after its job is gone. The name check has to hold on that path too.
 */
public class BuildRecorderNameTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
    }

    @Test
    public void recordStillStoresARunOfAnExistingJob() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("still-here");
        FreeStyleBuild run = j.buildAndAssertSuccess(job);
        assertEquals(1, rows("still-here"));

        BuildRecorder.record(run);
        assertEquals("re-recording a live job's run is an upsert", 1, rows("still-here"));
    }

    @Test
    public void recordSkipsARunWhoseJobWasDeleted() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gone");
        FreeStyleBuild run = j.buildAndAssertSuccess(job);
        job.delete();
        assertEquals("detached at delete time", 0, rows("gone"));

        BuildRecorder.record(run);
        assertEquals("a run of a deleted job must not come back under its name", 0, rows("gone"));
    }

    @Test
    public void recordSkipsARunWhoseJobNameNowBelongsToAnotherJob() throws Exception {
        FreeStyleProject old = j.createFreeStyleProject("taken");
        FreeStyleBuild oldRun = j.buildAndAssertSuccess(old);
        old.delete();
        j.createFreeStyleProject("taken");

        BuildRecorder.record(oldRun);
        assertEquals("nothing may be written under the new job's name", 0, rows("taken"));
    }

    private int rows(String name) {
        long now = System.currentTimeMillis();
        return MetricsStore.getInstance().getBuilds(name, now - 600000, now + 600000).size();
    }
}
