package io.jenkins.plugins.dorametrics.dora;

import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Change failure rate is failed deployments over deployments. A build that was aborted or
 * never built did not deploy anything, so it must not count toward either.
 */
@WithJenkins
class ChangeFailureRateTest {

    private JenkinsRule j;

    private MetricsStore store;
    private long now;
    private int number;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
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
    void abortedBuildsDoNotDiluteTheRate() {
        builds(10, "SUCCESS");
        builds(2, "FAILURE");
        builds(8, "ABORTED");
        assertEquals("16.7%", cfr(), "2 failed of 12 deployments");
    }

    @Test
    void notBuiltAndUnknownResultsAreNotDeploymentsEither() {
        builds(3, "SUCCESS");
        builds(1, "FAILURE");
        builds(2, "NOT_BUILT");
        builds(1, "UNKNOWN");
        assertEquals("25.0%", cfr());
    }

    @Test
    void anUnstableBuildIsADeploymentThatDidNotFail() {
        builds(1, "SUCCESS");
        builds(1, "UNSTABLE");
        builds(1, "FAILURE");
        assertEquals("33.3%", cfr());
    }

    @Test
    void onlyAbortedBuildsMeansNoDeploymentsYet() {
        builds(4, "ABORTED");
        assertEquals("N/A", cfr());
    }
}
