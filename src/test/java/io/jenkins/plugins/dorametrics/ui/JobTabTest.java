package io.jenkins.plugins.dorametrics.ui;

import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** A job the settings leave out has nothing to show, so it gets no DORA tab of zeros. */
public class JobTabTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void onlyTrackedJobsHaveTheTab() throws Exception {
        DoraGlobalConfiguration.get().setExcludedJobPattern("scratch-.*");
        FreeStyleProject tracked = j.createFreeStyleProject("app");
        FreeStyleProject excluded = j.createFreeStyleProject("scratch-test");

        assertNotNull(tracked.getAction(JobMetricsAction.class));
        assertNull(excluded.getAction(JobMetricsAction.class));
    }
}
