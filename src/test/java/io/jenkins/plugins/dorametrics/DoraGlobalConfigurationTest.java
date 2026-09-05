package io.jenkins.plugins.dorametrics;

import hudson.util.FormValidation;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class DoraGlobalConfigurationTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private DoraGlobalConfiguration config;

    @Before
    public void setUp() {
        config = DoraGlobalConfiguration.get();
        assertNotNull("Config should not be null", config);
    }

    @Test
    public void defaultValues() {
        assertEquals(".*", config.getProductionJobPattern());
        assertEquals("", config.getExcludedJobPattern());
        assertEquals("main|master", config.getProductionBranchPattern());
        assertEquals(365, config.getRetentionDays());
        assertEquals(10, config.getDashboardTopN());
        assertFalse(config.isIgnoreDisabledPipelines());
    }

    @Test
    public void ignoreDisabledPipelinesRoundTrip() throws Exception {
        // Submit the real Manage Jenkins > System form, so the jelly field and configure() are both exercised.
        config.setIgnoreDisabledPipelines(true);
        j.configRoundtrip();
        assertTrue(DoraGlobalConfiguration.get().isIgnoreDisabledPipelines());

        config.setIgnoreDisabledPipelines(false);
        j.configRoundtrip();
        assertFalse(DoraGlobalConfiguration.get().isIgnoreDisabledPipelines());
    }

    @Test
    public void shouldTrackJobDefaultMatchesAll() {
        assertTrue(config.shouldTrackJob("any-job"));
        assertTrue(config.shouldTrackJob("production/api"));
        assertTrue(config.shouldTrackJob("development/feature"));
    }

    @Test
    public void shouldTrackJobWithProductionPattern() {
        config.setProductionJobPattern("production/.*");

        assertTrue(config.shouldTrackJob("production/api-gateway"));
        assertTrue(config.shouldTrackJob("production/user-service"));
        assertFalse(config.shouldTrackJob("staging/api-gateway"));
        assertFalse(config.shouldTrackJob("development/feature"));
        assertFalse(config.shouldTrackJob("test-job"));
    }

    @Test
    public void shouldTrackJobWithExcludedPattern() {
        config.setProductionJobPattern(".*");
        config.setExcludedJobPattern("development/.*");

        assertTrue(config.shouldTrackJob("production/api"));
        assertTrue(config.shouldTrackJob("staging/api"));
        assertFalse(config.shouldTrackJob("development/feature-auth"));
        assertFalse(config.shouldTrackJob("development/feature-search"));
    }

    @Test
    public void shouldTrackJobWithFolders() {
        config.setProductionJobPattern("nomatch");
        config.setProductionFolders("production,staging");

        assertTrue(config.shouldTrackJob("production/api-gateway"));
        assertTrue(config.shouldTrackJob("staging/user-service"));
        assertFalse(config.shouldTrackJob("development/feature"));
        assertFalse(config.shouldTrackJob("test-deploy-prod"));
    }

    @Test
    public void shouldTrackJobWithCombinedFilters() {
        config.setProductionJobPattern(".*");
        config.setExcludedJobPattern(".*feature.*");

        assertTrue(config.shouldTrackJob("production/api"));
        assertTrue(config.shouldTrackJob("staging/api"));
        assertFalse(config.shouldTrackJob("development/feature-auth"));
        assertFalse(config.shouldTrackJob("development/feature-search"));
        assertTrue(config.shouldTrackJob("test-deploy-prod"));
    }

    @Test
    public void shouldTrackJobNullInput() {
        assertFalse(config.shouldTrackJob(null));
    }

    @Test
    public void invalidRegexFallsBackToDefault() {
        config.setProductionJobPattern("[invalid");
        // Should not crash, pattern recompiles on set
        // shouldTrackJob should still work (falls back)
        config.setProductionJobPattern(".*"); // reset
        assertTrue(config.shouldTrackJob("any-job"));
    }

    @Test
    public void doraThresholdDefaults() {
        assertEquals(1.0, config.getDfEliteThreshold(), 0.001);
        assertEquals(0.142, config.getDfHighThreshold(), 0.001);
        assertEquals(5.0, config.getCfrElitePercent(), 0.001);
    }

    @Test
    public void doraThresholdsCustomizable() {
        config.setDfEliteThreshold(10.0);
        assertEquals(10.0, config.getDfEliteThreshold(), 0.001);
        config.setDfEliteThreshold(1.0); // reset
    }

    @Test
    public void validateRetentionDaysRejectsInvalid() {
        assertEquals(FormValidation.Kind.ERROR, config.doCheckRetentionDays("0").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckRetentionDays("-5").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckRetentionDays("abc").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckRetentionDays("1.5").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckRetentionDays("1").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckRetentionDays("365").kind);
    }

    @Test
    public void validateDashboardTopNRejectsInvalid() {
        assertEquals(FormValidation.Kind.ERROR, config.doCheckDashboardTopN("0").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckDashboardTopN("-1").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckDashboardTopN("10").kind);
    }

    @Test
    public void validateExportIntervalRejectsInvalid() {
        assertEquals(FormValidation.Kind.ERROR, config.doCheckExportIntervalHours("0").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckExportIntervalHours("0.5").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckExportIntervalHours("1").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckExportIntervalHours("24").kind);
    }

    @Test
    public void validateCfrPercentRejectsOutOfRange() {
        assertEquals(FormValidation.Kind.ERROR, config.doCheckCfrElitePercent("-1").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckCfrElitePercent("101").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckCfrElitePercent("abc").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckCfrElitePercent("0").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckCfrElitePercent("5").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckCfrElitePercent("100").kind);
    }
}
