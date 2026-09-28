package io.jenkins.plugins.dorametrics.dora;

import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraBand;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraMetric;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.ui.DoraDashboardAction;
import io.jenkins.plugins.dorametrics.util.DurationFormatter;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

import java.util.Collections;
import java.util.Locale;
import java.util.logging.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DisplayValuesTest {

    private static final long DAY = 86_400_000L;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public LoggerRule logs = new LoggerRule().record(DoraDashboardAction.class, Level.WARNING).capture(10);

    private MetricsStore store;
    private long now;
    private Locale before;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        now = System.currentTimeMillis();
        before = Locale.getDefault();
    }

    @After
    public void restoreLocale() {
        Locale.setDefault(before);
    }

    private DoraCalculator calc() {
        return new DoraCalculator(store, DoraGlobalConfiguration.get(), Collections.emptySet());
    }

    private String deploymentFrequency(int deploys, int days) {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        for (int i = 0; i < deploys; i++) {
            store.insertBuild("app", i + 1, now - i * 60_000L, 1000, "SUCCESS", "SCM", "main");
        }
        return calc().deploymentFrequency(now - days * DAY, now, ".*").displayValue;
    }

    @Test
    public void deploymentFrequencyIsShownInAUnitThatIsNotZero() {
        assertEquals("1.0/month", deploymentFrequency(1, 30));
        assertEquals("3.0/week", deploymentFrequency(3, 7));
        assertEquals("2.0/day", deploymentFrequency(60, 30));
    }

    @Test
    public void numbersUseADotWhateverTheServerLocale() {
        Locale.setDefault(Locale.GERMANY);
        assertEquals("1.5h", DurationFormatter.format(90 * 60_000L));
        assertEquals("2.5d", DurationFormatter.format(60 * 3_600_000L));
        store.insertBuild("app", 1, now - 60_000L, 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("app", 2, now - 50_000L, 1000, "FAILURE", "SCM", "main");
        store.insertBuild("app", 3, now - 40_000L, 1000, "SUCCESS", "SCM", "main");
        assertEquals("33.3%", calc().changeFailureRate(now - DAY, now, ".*").displayValue);
    }

    @Test
    public void noDataIsNotRatedAsAnyBand() {
        assertEquals(DoraBand.NONE, calc().leadTimeForChanges(now - DAY, now, ".*").band);
        assertEquals(DoraBand.NONE, calc().meanTimeToRestore(now - DAY, now, ".*").band);
        assertEquals(DoraBand.NONE, calc().changeFailureRate(now - DAY, now, ".*").band);
        assertEquals("N/A", DoraBand.NONE.label);
    }

    @Test
    public void aMetricThatCannotBeWorkedOutIsLoggedAndShownAsNA() {
        DoraMetric shown = DoraDashboardAction.orNotAvailable("Lead Time for Changes", () -> {
            throw new IllegalStateException("database is gone");
        });
        assertEquals("N/A", shown.displayValue);
        assertEquals(DoraBand.NONE, shown.band);
        assertTrue(logs.getMessages().stream().anyMatch(m -> m.contains("Lead Time for Changes")));
    }
}
