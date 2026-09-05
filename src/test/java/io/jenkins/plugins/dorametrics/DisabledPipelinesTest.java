package io.jenkins.plugins.dorametrics;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.store.MetricsExporter;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Set;

import static org.junit.Assert.*;

public class DisabledPipelinesTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;
    private DoraGlobalConfiguration config;

    @Before
    public void setUp() throws Exception {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        config = DoraGlobalConfiguration.get();
        config.setIgnoreDisabledPipelines(false);

        j.createFreeStyleProject("active");
        FreeStyleProject disabled = j.createFreeStyleProject("disabled");
        disabled.disable();

        long now = System.currentTimeMillis();
        store.insertBuild("active", 1, now, 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("disabled", 1, now, 1000, "SUCCESS", "SCM", "main");
        // Recorded while it existed, deleted from Jenkins since.
        store.insertBuild("gone", 1, now, 1000, "SUCCESS", "SCM", "main");
    }

    @Test
    public void nothingExcludedWhileOptionIsOff() {
        assertTrue(DisabledPipelines.names(config, store).isEmpty());
    }

    @Test
    public void onlyCurrentlyDisabledJobsExcluded() {
        config.setIgnoreDisabledPipelines(true);
        Set<String> names = DisabledPipelines.names(config, store);
        assertEquals(Set.of("disabled"), names);
    }

    @Test
    public void disabledPipelineJobIsExcludedToo() throws Exception {
        WorkflowJob pipeline = j.createProject(WorkflowJob.class, "pipeline-disabled");
        pipeline.setDisabled(true);
        store.insertBuild("pipeline-disabled", 1, System.currentTimeMillis(), 1000, "SUCCESS", "SCM", "main");

        config.setIgnoreDisabledPipelines(true);
        assertEquals(Set.of("disabled", "pipeline-disabled"), DisabledPipelines.names(config, store));
    }

    @Test
    public void reenablingBringsTheJobBack() throws Exception {
        config.setIgnoreDisabledPipelines(true);
        j.jenkins.getItemByFullName("disabled", FreeStyleProject.class).enable();
        assertTrue(DisabledPipelines.names(config, store).isEmpty());
    }

    @Test
    public void nullConfigOrStoreExcludesNothing() {
        assertTrue(DisabledPipelines.names(null, store).isEmpty());
        assertTrue(DisabledPipelines.names(config, null).isEmpty());
    }

    @Test
    public void exportLeavesDisabledJobsOut() {
        String withEverything = MetricsExporter.exportFullDump(1);
        assertTrue(withEverything.contains("\"disabled\""));

        config.setIgnoreDisabledPipelines(true);
        String filtered = MetricsExporter.exportFullDump(1);
        assertFalse(filtered.contains("\"disabled\""));
        assertTrue(filtered.contains("\"active\""));
        assertTrue(filtered.contains("\"gone\""));
    }
}
