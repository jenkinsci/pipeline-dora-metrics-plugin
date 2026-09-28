package io.jenkins.plugins.dorametrics.dora;

import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * A changelog only lists what changed since the previous build, so a commit first built by a
 * build that failed is not in the changelog of the build that finally deployed it. Its lead
 * time runs until that deployment all the same.
 */
public class LeadTimeTest {

    private static final long MIN = 60_000L;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;
    private DoraGlobalConfiguration config;
    private long t0;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        config = DoraGlobalConfiguration.get();
        t0 = System.currentTimeMillis() - 12 * 60 * MIN;
    }

    private long build(String job, int number, long start, long duration, String result, String branch) {
        return store.insertBuild(job, number, start, duration, result, "SCM", branch);
    }

    private double leadTime() {
        return new DoraCalculator(store, config, Collections.emptySet())
                .leadTimeForChanges(t0 - 60 * MIN, System.currentTimeMillis(), ".*").rawValue;
    }

    @Test
    public void aCommitFirstBuiltByAFailedBuildCountsUntilItIsDeployed() {
        long failed = build("app", 1, t0 + 10 * MIN, MIN, "FAILURE", "main");
        store.insertCommit(failed, "c1", "dev", t0);
        build("app", 2, t0 + 20 * MIN, 5 * MIN, "SUCCESS", "main"); // empty changelog: nothing new since #1

        assertEquals(25 * MIN, leadTime(), 1.0);
    }

    @Test
    public void theEarliestCommitSinceTheLastDeploymentSetsTheLeadTime() {
        long failed = build("app", 1, t0 + 10 * MIN, MIN, "FAILURE", "main");
        store.insertCommit(failed, "c1", "dev", t0);
        long aborted = build("app", 2, t0 + 15 * MIN, MIN, "ABORTED", "main");
        store.insertCommit(aborted, "c2", "dev", t0 + 12 * MIN);
        long deployed = build("app", 3, t0 + 30 * MIN, 5 * MIN, "SUCCESS", "main");
        store.insertCommit(deployed, "c3", "dev", t0 + 25 * MIN);

        assertEquals(35 * MIN, leadTime(), 1.0);
    }

    @Test
    public void anEarlierDeploymentClosesTheWindow() {
        long first = build("app", 1, t0 + 10 * MIN, MIN, "SUCCESS", "main");
        store.insertCommit(first, "c1", "dev", t0);
        long second = build("app", 2, t0 + 60 * MIN, MIN, "SUCCESS", "main");
        store.insertCommit(second, "c2", "dev", t0 + 50 * MIN);

        // (10 + 1) and (60 + 1 - 50) minutes
        assertEquals((11 * MIN + 11 * MIN) / 2.0, leadTime(), 1.0);
    }

    @Test
    public void otherJobsDoNotMix() {
        long other = build("other", 1, t0 + 10 * MIN, MIN, "FAILURE", "main");
        store.insertCommit(other, "x", "dev", t0 - 600 * MIN);
        long app = build("app", 1, t0 + 20 * MIN, MIN, "SUCCESS", "main");
        store.insertCommit(app, "c1", "dev", t0 + 10 * MIN);

        assertEquals(11 * MIN, leadTime(), 1.0);
    }

    @Test
    public void aFailureOnAnotherBranchIsNotPartOfAProductionDeployment() {
        config.setTrackAllBranches(false);
        config.setProductionBranchPattern("main");
        long feature = build("app", 1, t0 + 10 * MIN, MIN, "FAILURE", "feature-x");
        store.insertCommit(feature, "f", "dev", t0 - 600 * MIN);
        long deployed = build("app", 2, t0 + 20 * MIN, MIN, "SUCCESS", "main");
        store.insertCommit(deployed, "c1", "dev", t0 + 10 * MIN);

        assertEquals(11 * MIN, leadTime(), 1.0);
    }
}
