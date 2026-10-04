package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleProject;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A job in an ordinary folder has no branch unless the build sets one, so a deploy job without
 * SCM still counts when only production branches do.
 */
@WithJenkins
class FolderJobBranchTest {

    private JenkinsRule j;
    private MetricsStore store;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        // A build's environment starts from the controller's own, and a CI run of these tests
        // has BRANCH_NAME set. Clear every variable a branch is read from, for these builds.
        EnvironmentVariablesNodeProperty.Entry[] none = new EnvironmentVariablesNodeProperty.Entry[] {
            new EnvironmentVariablesNodeProperty.Entry("BRANCH_NAME", ""),
            new EnvironmentVariablesNodeProperty.Entry("GIT_BRANCH", ""),
            new EnvironmentVariablesNodeProperty.Entry("GIT_LOCAL_BRANCH", ""),
            new EnvironmentVariablesNodeProperty.Entry("SVN_BRANCH", ""),
            new EnvironmentVariablesNodeProperty.Entry("CHANGE_BRANCH", ""),
            new EnvironmentVariablesNodeProperty.Entry("BRANCH", "")
        };
        j.jenkins.getGlobalNodeProperties().add(new EnvironmentVariablesNodeProperty(none));
    }

    @Test
    void aFolderJobWithoutABranchVariableHasNoBranch() throws Exception {
        MockFolder deploy = j.createFolder("deploy");
        FreeStyleProject prod = deploy.createProject(FreeStyleProject.class, "prod");
        j.buildAndAssertSuccess(prod);

        List<MetricsStore.BuildRecord> builds = store.getBuilds("deploy/prod", 0, Long.MAX_VALUE);
        assertEquals(1, builds.size());
        assertNull(builds.get(0).branch);
    }

    @Test
    void itStillCountsWhenOnlyProductionBranchesDo() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setTrackAllBranches(false);
        config.setProductionBranchPattern("main|master");
        MockFolder deploy = j.createFolder("deploy");
        FreeStyleProject prod = deploy.createProject(FreeStyleProject.class, "prod");
        j.buildAndAssertSuccess(prod);

        long now = System.currentTimeMillis();
        double perDay = new DoraCalculator().deploymentFrequency(now - 86_400_000L, now + 60_000L, ".*").rawValue;
        assertEquals(1.0, perDay, 0.01, "the one deploy counts");
    }
}
