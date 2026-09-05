package io.jenkins.plugins.dorametrics.dora;

import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class DoraCalculatorTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;
    private DoraCalculator calc;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        calc = new DoraCalculator();
    }

    @Test
    public void deploymentFrequencyElite() {
        long now = System.currentTimeMillis();
        // 30+ successful builds in 30 days = 1+/day = Elite
        for (int i = 0; i < 35; i++) {
            store.insertBuild("df-job", i + 1, now - (i * 86400000L), 5000, "SUCCESS", "SCM", "main");
        }

        DoraCalculator.DoraMetric df = calc.deploymentFrequency(now - 30L * 86400000, now + 1000, ".*");
        assertEquals(DoraCalculator.DoraBand.ELITE, df.band);
        assertTrue(df.rawValue >= 1.0);
    }

    @Test
    public void deploymentFrequencyLow() {
        long now = System.currentTimeMillis();
        // 1 build in 365 days = ~0.003/day = Low (below 0.033 medium threshold)
        store.insertBuild("df-low", 1, now, 5000, "SUCCESS", "SCM", "main");

        DoraCalculator.DoraMetric df = calc.deploymentFrequency(now - 365L * 86400000, now + 1000, "df-low");
        assertEquals(DoraCalculator.DoraBand.LOW, df.band);
    }

    @Test
    public void changeFailureRateElite() {
        long now = System.currentTimeMillis();
        // 20 success, 0 failures = 0% = Elite
        for (int i = 0; i < 20; i++) {
            store.insertBuild("cfr-elite", i + 1, now, 5000, "SUCCESS", "SCM", "main");
        }

        DoraCalculator.DoraMetric cfr = calc.changeFailureRate(now - 1000, now + 1000, ".*");
        assertEquals(DoraCalculator.DoraBand.ELITE, cfr.band);
        assertEquals(0.0, cfr.rawValue, 0.1);
    }

    @Test
    public void changeFailureRateLow() {
        long now = System.currentTimeMillis();
        // 4 success, 6 failures = 60% = Low
        for (int i = 0; i < 4; i++) {
            store.insertBuild("cfr-low", i + 1, now, 5000, "SUCCESS", "SCM", "main");
        }
        for (int i = 0; i < 6; i++) {
            store.insertBuild("cfr-low", i + 100, now, 5000, "FAILURE", "SCM", "main");
        }

        DoraCalculator.DoraMetric cfr = calc.changeFailureRate(now - 1000, now + 1000, "cfr-low");
        assertEquals(DoraCalculator.DoraBand.LOW, cfr.band);
        assertEquals(60.0, cfr.rawValue, 0.1);
    }

    @Test
    public void changeFailureRateNoBuilds() {
        long now = System.currentTimeMillis();
        DoraCalculator.DoraMetric cfr = calc.changeFailureRate(now - 1000, now + 1000, "nonexistent");
        assertEquals("N/A", cfr.displayValue);
    }

    @Test
    public void leadTimeWithCommits() {
        long now = System.currentTimeMillis();
        long commitTime = now - 1800000; // 30 min before
        long buildId = store.insertBuild("lt-job", 1, now, 5000, "SUCCESS", "SCM", "main");
        store.insertCommit(buildId, "abc", "dev@test.com", commitTime);

        DoraCalculator.DoraMetric lt = calc.leadTimeForChanges(now - 1000, now + 1000, ".*");
        assertNotEquals("N/A", lt.displayValue);
        assertEquals(DoraCalculator.DoraBand.ELITE, lt.band); // 30 min < 1 hour = Elite
    }

    @Test
    public void leadTimeNoCommits() {
        long now = System.currentTimeMillis();
        store.insertBuild("lt-no-commit", 1, now, 5000, "SUCCESS", "SCM", "main");

        DoraCalculator.DoraMetric lt = calc.leadTimeForChanges(now - 1000, now + 1000, "lt-no-commit");
        assertEquals("N/A", lt.displayValue);
    }

    @Test
    public void mttrWithRecovery() {
        long now = System.currentTimeMillis();
        // Failure at T, success at T+10min
        store.insertBuild("mttr-job", 1, now - 600000, 5000, "FAILURE", "SCM", "main");
        store.insertBuild("mttr-job", 2, now, 5000, "SUCCESS", "SCM", "main");

        DoraCalculator.DoraMetric mttr = calc.meanTimeToRestore(now - 700000, now + 1000, ".*");
        assertNotEquals("N/A", mttr.displayValue);
        assertEquals(DoraCalculator.DoraBand.ELITE, mttr.band); // 10 min < 1 hour = Elite
    }

    @Test
    public void mttrNoFailures() {
        long now = System.currentTimeMillis();
        store.insertBuild("mttr-ok", 1, now, 5000, "SUCCESS", "SCM", "main");

        DoraCalculator.DoraMetric mttr = calc.meanTimeToRestore(now - 1000, now + 1000, "mttr-ok");
        assertEquals("N/A", mttr.displayValue);
        assertEquals(DoraCalculator.DoraBand.ELITE, mttr.band); // No failures = Elite
    }

    @Test
    public void metricsWithJobPattern() {
        long now = System.currentTimeMillis();
        store.insertBuild("prod/api", 1, now, 5000, "SUCCESS", "SCM", "main");
        store.insertBuild("prod/web", 1, now, 5000, "SUCCESS", "SCM", "main");
        store.insertBuild("dev/test", 1, now, 5000, "FAILURE", "SCM", "main");

        // Only count prod jobs
        DoraCalculator.DoraMetric cfr = calc.changeFailureRate(now - 1000, now + 1000, "prod/.*");
        assertEquals(0.0, cfr.rawValue, 0.1); // No failures in prod
    }

    @Test
    public void ignoresDisabledPipelinesWhenConfigured() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        j.createFreeStyleProject("cfr-active");
        FreeStyleProject disabled = j.createFreeStyleProject("cfr-disabled");
        disabled.disable();

        long now = System.currentTimeMillis();
        long day = 86400000L;
        for (int i = 0; i < 3; i++) {
            store.insertBuild("cfr-active", i + 1, now - i * day, 1000, "SUCCESS", "SCM", "main");
        }
        // Three failures, then a recovery a day later: contributes failures and a restore time.
        for (int i = 0; i < 3; i++) {
            store.insertBuild("cfr-disabled", i + 1, now - (4 - i) * day, 1000, "FAILURE", "SCM", "main");
        }
        store.insertBuild("cfr-disabled", 4, now, 1000, "SUCCESS", "SCM", "main");

        long from = now - 30 * day;
        long to = now + 1000;

        config.setIgnoreDisabledPipelines(false);
        DoraCalculator everything = new DoraCalculator();
        assertEquals(42.857, everything.changeFailureRate(from, to, ".*").rawValue, 0.01);
        assertTrue(everything.meanTimeToRestore(from, to, ".*").rawValue > 0);

        config.setIgnoreDisabledPipelines(true);
        DoraCalculator filtered = new DoraCalculator();
        assertEquals(0.0, filtered.changeFailureRate(from, to, ".*").rawValue, 0.001);
        assertEquals(0.0, filtered.meanTimeToRestore(from, to, ".*").rawValue, 0.001);
        assertEquals(3.0 / 30, filtered.deploymentFrequency(from, to, ".*").rawValue, 0.01);
        config.setIgnoreDisabledPipelines(false); // reset
    }
}
