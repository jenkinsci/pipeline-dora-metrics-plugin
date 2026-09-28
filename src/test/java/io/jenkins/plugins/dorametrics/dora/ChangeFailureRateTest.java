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
 * Change failure rate is failed deployments over deployments. A build that was aborted or
 * never built did not deploy anything, so it must not count toward either.
 */
public class ChangeFailureRateTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;
    private long now;
    private int number;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        now = System.currentTimeMillis();
    }

    private void builds(int count, String result) {
        for (int i = 0; i < count; i++) {
            number++;
            store.insertBuild("svc", number, now - number * 1000L, 1000, result, "USER", "main");
        }
    }

    private String cfr() {
        return new DoraCalculator(store, DoraGlobalConfiguration.get(), Collections.emptySet())
                .changeFailureRate(now - 86_400_000L, now, ".*").displayValue;
    }

    @Test
    public void abortedBuildsDoNotDiluteTheRate() {
        builds(10, "SUCCESS");
        builds(2, "FAILURE");
        builds(8, "ABORTED");
        assertEquals("2 failed of 12 deployments", "16.7%", cfr());
    }

    @Test
    public void notBuiltAndUnknownResultsAreNotDeploymentsEither() {
        builds(3, "SUCCESS");
        builds(1, "FAILURE");
        builds(2, "NOT_BUILT");
        builds(1, "UNKNOWN");
        assertEquals("25.0%", cfr());
    }

    @Test
    public void anUnstableBuildIsADeploymentThatDidNotFail() {
        builds(1, "SUCCESS");
        builds(1, "UNSTABLE");
        builds(1, "FAILURE");
        assertEquals("33.3%", cfr());
    }

    @Test
    public void onlyAbortedBuildsMeansNoDeploymentsYet() {
        builds(4, "ABORTED");
        assertEquals("N/A", cfr());
    }
}
