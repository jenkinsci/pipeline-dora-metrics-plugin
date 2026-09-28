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
 * Time to restore runs from the first failure until the build that fixed it finished, and a
 * failure that started before the window still counts when it is fixed inside it.
 */
public class TimeToRestoreTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;
    private DoraGlobalConfiguration config;
    private long from;
    private long to;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        config = DoraGlobalConfiguration.get();
        to = System.currentTimeMillis();
        from = to - 24 * HOUR;
    }

    private int number;

    private void build(long start, long duration, String result, String branch) {
        store.insertBuild("svc", ++number, start, duration, result, "SCM", branch);
    }

    private DoraCalculator.DoraMetric mttr() {
        return new DoraCalculator(store, config, Collections.emptySet()).meanTimeToRestore(from, to, ".*");
    }

    @Test
    public void runsUntilTheFixingBuildFinished() {
        build(from + HOUR, MIN, "FAILURE", "main");
        build(from + HOUR + 10 * MIN, 20 * MIN, "SUCCESS", "main");
        assertEquals(30 * MIN, mttr().rawValue, 1.0);
    }

    @Test
    public void aFailureFromBeforeTheWindowCountsWhenItIsFixedInside() {
        build(from - 2 * HOUR, MIN, "FAILURE", "main");
        build(from - HOUR, MIN, "FAILURE", "main");
        build(from + HOUR, 0, "SUCCESS", "main");
        assertEquals("from the first failure of the streak", 3 * HOUR, mttr().rawValue, 1.0);
    }

    @Test
    public void aSuccessBeforeTheWindowClosesTheEarlierStreak() {
        build(from - 3 * HOUR, MIN, "FAILURE", "main");
        build(from - 2 * HOUR, 0, "SUCCESS", "main");
        build(from + HOUR, 0, "SUCCESS", "main");
        assertEquals("N/A", mttr().displayValue);
    }

    @Test
    public void anAbortedRetryDoesNotRestartTheClock() {
        build(from + HOUR, 0, "FAILURE", "main");
        build(from + HOUR + 5 * MIN, 0, "ABORTED", "main");
        build(from + HOUR + 15 * MIN, 0, "SUCCESS", "main");
        assertEquals(15 * MIN, mttr().rawValue, 1.0);
    }

    @Test
    public void aFailureThatIsStillOpenIsNotCounted() {
        build(from + HOUR, 0, "FAILURE", "main");
        assertEquals("N/A", mttr().displayValue);
    }

    @Test
    public void anEarlierFailureOnAnotherBranchIsIgnoredWhenOnlyProductionCounts() {
        config.setTrackAllBranches(false);
        config.setProductionBranchPattern("main");
        build(from - 2 * HOUR, 0, "FAILURE", "feature-x");
        build(from + HOUR, 0, "FAILURE", "main");
        build(from + 2 * HOUR, 0, "SUCCESS", "main");
        assertEquals(HOUR, mttr().rawValue, 1.0);
    }
}
