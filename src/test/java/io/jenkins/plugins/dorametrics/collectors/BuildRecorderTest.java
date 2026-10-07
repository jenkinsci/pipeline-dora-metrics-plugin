package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildRecorder#record} is the entry point the planned build history import (issue #12)
 * will call directly, once per build already on disk, without going through
 * {@link BuildDataCollector}. These tests exercise that call on its own.
 */
@WithJenkins
class BuildRecorderTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
    }

    /**
     * The case issue #12 opens with: a build that did not match the job filter when it ran is
     * never stored, and widening the filter afterwards does not bring it back, because nothing
     * revisits history. Calling record() directly is what will bring it back.
     */
    @Test
    void recordImportsABuildTheListenerNeverSaw() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("late-import-test");

        FreeStyleProject job = j.createFreeStyleProject("late-import-test");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        MetricsStore store = MetricsStore.getInstance();
        long from = System.currentTimeMillis() - 60000;
        long to = System.currentTimeMillis() + 60000;
        assertTrue(store.getBuilds("late-import-test", from, to).isEmpty(),
                "the listener should have skipped the excluded job");

        // The operator widens the filter. Nothing happens on its own.
        config.setExcludedJobPattern("");
        assertTrue(store.getBuilds("late-import-test", from, to).isEmpty(),
                "widening the filter alone must not recover the build");

        // This is the call an import will make, per build already on disk.
        BuildRecorder.record(build);

        List<MetricsStore.BuildRecord> builds = store.getBuilds("late-import-test", from, to);
        assertEquals(1, builds.size(), "record() should store the build without the listener");
        assertEquals("SUCCESS", builds.get(0).result);
        assertEquals(build.getNumber(), builds.get(0).buildNumber);
    }

    /**
     * An import must not become a way around the job filter, so record() applies it on every
     * call, not only on the listener's.
     */
    @Test
    void recordAppliesTheJobFilterJustLikeTheListenerDoes() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setExcludedJobPattern("filtered-record-test");

        FreeStyleProject job = j.createFreeStyleProject("filtered-record-test");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        BuildRecorder.record(build);

        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        assertTrue(store.getBuilds("filtered-record-test", now - 60000, now + 60000).isEmpty(),
                "an excluded job should stay excluded when recorded directly");
    }

    /**
     * Recording a build that the listener already stored must not duplicate it, because an
     * import will inevitably cover builds that are already in the store.
     */
    @Test
    void recordingAnAlreadyStoredBuildDoesNotDuplicateIt() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("reimport-test");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        MetricsStore store = MetricsStore.getInstance();
        long from = System.currentTimeMillis() - 60000;
        long to = System.currentTimeMillis() + 60000;
        assertEquals(1,
                store.getBuilds("reimport-test", from, to).size(), "the listener should have stored it once");

        BuildRecorder.record(build);

        assertEquals(1,
                store.getBuilds("reimport-test", from, to).size(), "re-recording must upsert, not duplicate");
    }
}
