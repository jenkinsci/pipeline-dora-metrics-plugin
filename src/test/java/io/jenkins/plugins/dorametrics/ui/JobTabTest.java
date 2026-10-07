package io.jenkins.plugins.dorametrics.ui;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A job the settings leave out has nothing to show, so it gets no DORA tab of zeros. */
@WithJenkins
class JobTabTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void onlyTrackedJobsHaveTheTab() throws Exception {
        DoraGlobalConfiguration.get().setExcludedJobPattern("scratch-.*");
        FreeStyleProject tracked = j.createFreeStyleProject("app");
        FreeStyleProject excluded = j.createFreeStyleProject("scratch-test");

        assertNotNull(tracked.getAction(JobMetricsAction.class));
        assertNull(excluded.getAction(JobMetricsAction.class));
    }
}
