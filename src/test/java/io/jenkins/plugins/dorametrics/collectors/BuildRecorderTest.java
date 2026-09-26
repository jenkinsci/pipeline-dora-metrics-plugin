package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link BuildRecorder#record} is the entry point the planned build history import (issue #12)
 * will call directly, once per build already on disk, without going through
 * {@link BuildDataCollector}. These tests exercise that call on its own.
 */
public class BuildRecorderTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
    }

    /**
     * The case issue #12 opens with: a build that did not match the job filter when it ran is
     * never stored, and widening the filter afterwards does not bring it back, because nothing
     * revisits history. Calling record() directly is what will bring it back.
     */
    @Test
    public void recordImportsABuildTheListenerNeverSaw() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("late-import-test");

        FreeStyleProject job = j.createFreeStyleProject("late-import-test");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        MetricsStore store = MetricsStore.getInstance();
        long from = System.currentTimeMillis() - 60000;
        long to = System.currentTimeMillis() + 60000;
        assertTrue("the listener should have skipped the excluded job",
                store.getBuilds("late-import-test", from, to).isEmpty());

        // The operator widens the filter. Nothing happens on its own.
        config.setExcludedJobPattern("");
        assertTrue("widening the filter alone must not recover the build",
                store.getBuilds("late-import-test", from, to).isEmpty());

        // This is the call an import will make, per build already on disk.
        BuildRecorder.record(build);

        List<MetricsStore.BuildRecord> builds = store.getBuilds("late-import-test", from, to);
        assertEquals("record() should store the build without the listener", 1, builds.size());
        assertEquals("SUCCESS", builds.get(0).result);
        assertEquals(build.getNumber(), builds.get(0).buildNumber);
    }

    /**
     * An import must not become a way around the job filter, so record() applies it on every
     * call, not only on the listener's.
     */
    @Test
    public void recordAppliesTheJobFilterJustLikeTheListenerDoes() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("filtered-record-test");

        FreeStyleProject job = j.createFreeStyleProject("filtered-record-test");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        BuildRecorder.record(build);

        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        assertTrue("an excluded job should stay excluded when recorded directly",
                store.getBuilds("filtered-record-test", now - 60000, now + 60000).isEmpty());
    }

    /**
     * Recording a build that the listener already stored must not duplicate it, because an
     * import will inevitably cover builds that are already in the store.
     */
    @Test
    public void recordingAnAlreadyStoredBuildDoesNotDuplicateIt() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("reimport-test");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        MetricsStore store = MetricsStore.getInstance();
        long from = System.currentTimeMillis() - 60000;
        long to = System.currentTimeMillis() + 60000;
        assertEquals("the listener should have stored it once",
                1, store.getBuilds("reimport-test", from, to).size());

        BuildRecorder.record(build);

        assertEquals("re-recording must upsert, not duplicate",
                1, store.getBuilds("reimport-test", from, to).size());
    }
}
