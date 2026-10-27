package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class BuildDataCollectorTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
    }

    @Test
    void runListenerRegistered() {
        BuildDataCollector collector = Jenkins.get().getExtensionList(RunListener.class)
                .get(BuildDataCollector.class);
        assertNotNull(collector, "BuildDataCollector should be registered as RunListener");
    }

    @Test
    void capturesFreestyleBuild() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("freestyle-test");
        FreeStyleBuild build = j.buildAndAssertSuccess(job);

        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        List<MetricsStore.BuildRecord> builds = store.getBuilds("freestyle-test", now - 60000, now + 1000);
        assertEquals(1, builds.size(), "Should capture freestyle build");
        assertEquals("freestyle-test", builds.get(0).jobName);
        assertEquals("SUCCESS", builds.get(0).result);
        assertNotNull(builds.get(0).triggerType, "Trigger type should not be null");
    }

    @Test
    void capturesMultipleBuilds() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("multi-test");
        j.buildAndAssertSuccess(job);
        j.buildAndAssertSuccess(job);
        j.buildAndAssertSuccess(job);

        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        List<MetricsStore.BuildRecord> builds = store.getBuilds("multi-test", now - 60000, now + 1000);
        assertEquals(3, builds.size(), "Should capture 3 builds");
        assertEquals(1, builds.get(0).buildNumber);
        assertEquals(2, builds.get(1).buildNumber);
        assertEquals(3, builds.get(2).buildNumber);
    }

    @Test
    void buildDurationIsPositive() throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("duration-test");
        j.buildAndAssertSuccess(job);

        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        List<MetricsStore.BuildRecord> builds = store.getBuilds("duration-test", now - 60000, now + 1000);
        assertTrue(builds.get(0).durationMs >= 0, "Duration should be positive");
    }
}
