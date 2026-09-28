package io.jenkins.plugins.pipeline_dora_metrics;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The example Configuration as Code file applies, setting by setting. */
public class ConfigurationAsCodeTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void theExampleConfigurationApplies() throws Exception {
        ConfigurationAsCode.get().configure(getClass().getResource("configuration-as-code.yml").toExternalForm());

        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        assertEquals(".*-prod", config.getProductionJobPattern());
        assertEquals(".*-scratch", config.getExcludedJobPattern());
        assertEquals("deploy", config.getProductionFolders());
        assertEquals("main|release/.*", config.getProductionBranchPattern());
        assertFalse(config.isTrackAllBranches());
        assertTrue(config.isIgnoreDisabledPipelines());
        assertEquals(180, config.getRetentionDays());
        assertEquals(15, config.getDashboardTopN());
        assertEquals(2.0, config.getDfEliteThreshold(), 0);
        assertEquals(43200, config.getLtEliteSeconds());
        assertEquals(43200, config.getMttrHighSeconds());
        assertEquals(20.0, config.getCfrMediumPercent(), 0);

        assertTrue("the job settings take effect", config.shouldTrackJob("deploy/api"));
        assertTrue(config.shouldTrackJob("api-prod"));
        assertFalse(config.shouldTrackJob("api-scratch"));
        assertFalse(config.shouldTrackJob("api-staging"));
    }
}
