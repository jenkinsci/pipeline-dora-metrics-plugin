package io.jenkins.plugins.dorametrics.dora;

import hudson.model.FreeStyleProject;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * "Production Branch Pattern" and "Track All Branches" decide which builds count for the four
 * DORA metrics. With Track All Branches off, a build counts only when its branch matches the
 * pattern, or when it has no branch at all, such as a deploy job without SCM.
 */
public class BranchSettingsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;
    private DoraGlobalConfiguration config;
    private long now;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        config = DoraGlobalConfiguration.get();
        now = System.currentTimeMillis();
    }

    private long build(int number, String branch, String result) {
        return store.insertBuild("app", number, now - (10 - number) * 60_000L, 1000, result, "SCM", branch);
    }

    private DoraCalculator calc() {
        return new DoraCalculator(store, config, Collections.emptySet());
    }

    private void onlyProductionBranches(String pattern) {
        config.setTrackAllBranches(false);
        config.setProductionBranchPattern(pattern);
    }

    @Test
    public void onlyProductionBranchesCountWhenTrackAllBranchesIsOff() {
        long mainBuild = build(1, "main", "SUCCESS");
        build(2, "feature-x", "FAILURE");
        build(3, "PR-7", "FAILURE");
        long featureFix = build(4, "feature-x", "SUCCESS");
        store.insertCommit(mainBuild, "a", "dev", now - 10 * 60_000L - 3_600_000L);
        store.insertCommit(featureFix, "b", "dev", now - 10 * 60_000L - 86_400_000L * 5);
        onlyProductionBranches("main");
        long from = now - 86_400_000L;

        assertEquals("0.0%", calc().changeFailureRate(from, now, ".*").displayValue);
        assertEquals(1.0, calc().deploymentFrequency(from, now, ".*").rawValue, 1e-9);
        assertEquals("N/A", calc().meanTimeToRestore(from, now, ".*").displayValue);
        assertEquals("only the main build's commit counts", 3_600_000L + 60_000L + 1000,
                calc().leadTimeForChanges(from, now, ".*").rawValue, 1.0);
    }

    @Test
    public void everyBranchCountsWhenTrackAllBranchesIsOn() {
        build(1, "main", "SUCCESS");
        build(2, "feature-x", "FAILURE");
        build(3, "PR-7", "FAILURE");
        config.setTrackAllBranches(true);
        config.setProductionBranchPattern("main");

        assertEquals("66.7%", calc().changeFailureRate(now - 86_400_000L, now, ".*").displayValue);
    }

    @Test
    public void aBuildWithoutABranchStillCounts() {
        build(1, null, "SUCCESS");
        build(2, "feature-x", "FAILURE");
        onlyProductionBranches("main");

        assertEquals("0.0%", calc().changeFailureRate(now - 86_400_000L, now, ".*").displayValue);
        assertEquals(1.0, calc().deploymentFrequency(now - 86_400_000L, now, ".*").rawValue, 1e-9);
    }

    @Test
    public void aPatternWithSlashesMatchesTheWholeBranchName() {
        build(1, "main", "SUCCESS");
        build(2, "release/1.2", "FAILURE");
        build(3, "feature/login", "FAILURE");
        onlyProductionBranches("main|release/.*");

        assertEquals("50.0%", calc().changeFailureRate(now - 86_400_000L, now, ".*").displayValue);
    }

    @Test
    public void theRecordedBranchKeepsItsPath() throws Exception {
        // A build's environment starts from the controller's own, and a CI run of these tests
        // has BRANCH_NAME set, which is read before GIT_BRANCH. Clear it for this build.
        EnvironmentVariablesNodeProperty env = new EnvironmentVariablesNodeProperty(
                new EnvironmentVariablesNodeProperty.Entry("BRANCH_NAME", ""),
                new EnvironmentVariablesNodeProperty.Entry("GIT_BRANCH", "origin/release/1.2"));
        j.jenkins.getGlobalNodeProperties().add(env);
        FreeStyleProject p = j.createFreeStyleProject("git-job");
        j.buildAndAssertSuccess(p);

        assertEquals("release/1.2", store.getBuilds("git-job", 0, Long.MAX_VALUE).get(0).branch);
    }
}
