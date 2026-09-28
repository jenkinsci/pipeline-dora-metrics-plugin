package io.jenkins.plugins.dorametrics;

import hudson.util.FormValidation;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator;
import io.jenkins.plugins.dorametrics.dora.DoraCalculator.DoraBand;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The default bands follow the 2024 DORA report, the form accepts the fractional deploy
 * frequency thresholds it ships with, and an untouched install does not show them as edited.
 */
public class BandThresholdsTest {

    private static final long HOUR = 3600;
    private static final long DAY = 24 * HOUR;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void leadTimeDefaultsFollowTheDoraBands() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        assertEquals("Elite is under a day", DAY, config.getLtEliteSeconds());
        assertEquals("High is under a week", 7 * DAY, config.getLtHighSeconds());
        assertEquals("Medium is under a month", 30 * DAY, config.getLtMediumSeconds());
    }

    @Test
    public void leadTimeIsBandedLikeDoraReportsIt() {
        assertEquals(DoraBand.ELITE, leadTimeBand(12 * HOUR));
        assertEquals(DoraBand.HIGH, leadTimeBand(3 * DAY));
        assertEquals(DoraBand.MEDIUM, leadTimeBand(14 * DAY));
        assertEquals(DoraBand.LOW, leadTimeBand(60 * DAY));
    }

    private DoraBand leadTimeBand(long leadTimeSeconds) {
        MetricsStore.setInstance(null);
        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        long id = store.insertBuild("app", 1, now - 1000, 0, "SUCCESS", "SCM", "main");
        store.insertCommit(id, "abc", "dev", now - 1000 - leadTimeSeconds * 1000);
        return new DoraCalculator(store, DoraGlobalConfiguration.get(), Collections.emptySet())
                .leadTimeForChanges(now - 90 * DAY * 1000, now, ".*").band;
    }

    @Test
    public void savedOldDefaultsMoveToTheDoraBands() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setLtEliteSeconds(3600);
        config.setLtHighSeconds(86400);
        config.setLtMediumSeconds(604800);
        config.save();

        DoraGlobalConfiguration reloaded = new DoraGlobalConfiguration();
        assertEquals(DAY, reloaded.getLtEliteSeconds());
        assertEquals(7 * DAY, reloaded.getLtHighSeconds());
        assertEquals(30 * DAY, reloaded.getLtMediumSeconds());
    }

    @Test
    public void leadTimeBandsSomeoneChangedAreKept() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setLtEliteSeconds(3600);
        config.setLtHighSeconds(2 * DAY);
        config.setLtMediumSeconds(604800);
        config.save();

        DoraGlobalConfiguration reloaded = new DoraGlobalConfiguration();
        assertEquals(3600, reloaded.getLtEliteSeconds());
        assertEquals(2 * DAY, reloaded.getLtHighSeconds());
        assertEquals(604800, reloaded.getLtMediumSeconds());
    }

    @Test
    public void anUntouchedInstallDoesNotShowTheThresholdsAsEdited() throws Exception {
        HtmlPage page = j.createWebClient().goTo("configure");
        for (DomElement info : page.getElementsByTagName("div")) {
            if (info.getAttribute("class").contains("advanced-customized-fields-info")) {
                String fields = info.getAttribute("data-customized-fields");
                assertFalse("shown as edited: " + fields, fields.contains("Threshold")
                        || fields.contains("Seconds") || fields.contains("Percent"));
            }
        }
    }

    @Test
    public void fractionalThresholdsCanBeEntered() throws Exception {
        HtmlPage page = j.createWebClient().goTo("configure");
        for (String field : new String[] {"dfEliteThreshold", "dfHighThreshold", "dfMediumThreshold",
                "cfrElitePercent", "cfrHighPercent", "cfrMediumPercent"}) {
            DomElement input = page.getElementsByName("_." + field).get(0);
            assertEquals(field + " must accept decimals", "any", input.getAttribute("step"));
        }
    }

    @Test
    public void bandsInTheWrongOrderAreFlagged() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        assertEquals(FormValidation.Kind.OK, config.doCheckDfHighThreshold("0.142", "1", "0.033").kind);
        assertEquals(FormValidation.Kind.WARNING, config.doCheckDfHighThreshold("2", "1", "0.033").kind);
        assertEquals(FormValidation.Kind.OK, config.doCheckLtHighSeconds("604800", "86400", "2592000").kind);
        assertEquals(FormValidation.Kind.WARNING, config.doCheckLtHighSeconds("3600", "86400", "2592000").kind);
        assertEquals(FormValidation.Kind.WARNING, config.doCheckMttrHighSeconds("99999999", "3600", "604800").kind);
        assertEquals(FormValidation.Kind.WARNING, config.doCheckCfrHighPercent("20", "5", "15").kind);
        assertEquals(FormValidation.Kind.ERROR, config.doCheckCfrHighPercent("120", "5", "15").kind);
        assertTrue(config.doCheckCfrHighPercent("10", "5", "15").kind == FormValidation.Kind.OK);
    }
}
