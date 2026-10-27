package io.jenkins.plugins.pipeline_dora_metrics;

import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

/** The example Configuration as Code file applies, setting by setting. */
@WithJenkins
class ConfigurationAsCodeTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void theExampleConfigurationApplies() {
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

        assertTrue(config.shouldTrackJob("deploy/api"), "the job settings take effect");
        assertTrue(config.shouldTrackJob("api-prod"));
        assertFalse(config.shouldTrackJob("api-scratch"));
        assertFalse(config.shouldTrackJob("api-staging"));
    }
}
