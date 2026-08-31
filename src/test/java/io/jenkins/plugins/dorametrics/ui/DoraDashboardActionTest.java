package io.jenkins.plugins.dorametrics.ui;

import hudson.model.FreeStyleProject;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.rankings.PipelineRanker.RankedPipeline;

import java.util.List;

import static org.junit.Assert.*;

public class DoraDashboardActionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void extensionRegistered() {
        DoraDashboardAction action = Jenkins.get().getExtensionList(RootAction.class)
                .get(DoraDashboardAction.class);
        assertNotNull("DoraDashboardAction should be registered", action);
        assertEquals("dora-metrics", action.getUrlName());
        assertEquals("DORA Metrics", action.getDisplayName());
        assertNotNull(action.getIconFileName());
    }

    @Test
    public void doraMetricsNotNull() {
        DoraDashboardAction action = new DoraDashboardAction();
        assertNotNull(action.getDeploymentFrequency());
        assertNotNull(action.getLeadTime());
        assertNotNull(action.getMttr());
        assertNotNull(action.getChangeFailureRate());
    }

    @Test
    public void rankingsNotNull() {
        DoraDashboardAction action = new DoraDashboardAction();
        assertNotNull(action.getSlowestPipelines());
        assertNotNull(action.getMostFailingPipelines());
        assertNotNull(action.getMostImprovedPipelines());
        assertNotNull(action.getFlakiestPipelines());
        assertNotNull(action.getSlowestStages());
        assertNotNull(action.getMostFailingStages());
    }

    @Test
    public void jobUrlConvertsCorrectly() {
        DoraDashboardAction action = new DoraDashboardAction();
        assertEquals("job/production/job/api-gateway", action.jobUrl("production/api-gateway"));
        assertEquals("job/simple-job", action.jobUrl("simple-job"));
        assertEquals("job/a/job/b/job/c", action.jobUrl("a/b/c"));
        assertEquals("", action.jobUrl(null));
    }

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
    }

    private WebClient noJsClient() {
        WebClient wc = j.createWebClient();
        wc.setJavaScriptEnabled(false);
        return wc;
    }

    @Test
    public void dashboardPageLoads() throws Exception {
        HtmlPage page = noJsClient().goTo("dora-metrics");
        assertNotNull("Dashboard page should load", page);
        assertEquals(200, page.getWebResponse().getStatusCode());
    }

    /**
     * The KPI cards are rendered server-side, and dora-dashboard.js refreshes them
     * by id when the date range changes. Without these ids the cards silently kept
     * showing the initial period while the trend charts updated (issue #3), so the
     * ids are part of the contract between the view and the script.
     */
    @Test
    public void kpiCardsExposeIdsForDateRangeRefresh() throws Exception {
        HtmlPage page = noJsClient().goTo("dora-metrics");
        String html = page.getWebResponse().getContentAsString();
        for (String slug : new String[] {"df", "lt", "mttr", "cfr"}) {
            assertTrue("KPI value element id=kpi-" + slug + "-value must exist so the "
                            + "date-range handler can refresh it",
                    html.contains("id=\"kpi-" + slug + "-value\""));
            assertTrue("KPI band element id=kpi-" + slug + "-band must exist so the "
                            + "date-range handler can refresh it",
                    html.contains("id=\"kpi-" + slug + "-band\""));
        }
    }

    @Test
    public void tablesHaveSmallClass() throws Exception {
        HtmlPage page = noJsClient().goTo("dora-metrics");
        List<DomElement> tables = page.getByXPath("//table[contains(@class, 'jenkins-table')]");
        assertFalse("Dashboard should have tables", tables.isEmpty());
        for (DomElement table : tables) {
            String classes = table.getAttribute("class");
            assertTrue("Table should have jenkins-table--small: " + classes,
                    classes.contains("jenkins-table--small"));
        }
    }

    @Test
    public void dashboardHasAppBar() throws Exception {
        HtmlPage page = noJsClient().goTo("dora-metrics");
        // l:app-bar renders with id="view-message" or class containing "jenkins-app-bar"
        List<DomElement> appBars = page.getByXPath("//*[contains(@class, 'jenkins-app-bar')]");
        assertFalse("Dashboard should have an app bar", appBars.isEmpty());
    }

    @Test
    public void activeButtonHasPrimaryClass() throws Exception {
        HtmlPage page = noJsClient().goTo("dora-metrics");
        // The default active button (30d) should have jenkins-button--primary
        List<DomElement> primaryBtns = page.getByXPath(
                "//button[contains(@class, 'dora-date-btn') and contains(@class, 'jenkins-button--primary')]");
        assertEquals("Exactly one date button should be primary", 1, primaryBtns.size());
        assertEquals("30", primaryBtns.get(0).getAttribute("data-days"));

        // All other date buttons should be tertiary
        List<DomElement> tertiaryBtns = page.getByXPath(
                "//button[contains(@class, 'dora-date-btn') and contains(@class, 'jenkins-button--tertiary')]");
        assertEquals("Four date buttons should be tertiary", 4, tertiaryBtns.size());
    }

    @Test
    public void dashboardHasDoraSections() throws Exception {
        HtmlPage page = noJsClient().goTo("dora-metrics");
        List<DomElement> sections = page.getByXPath("//*[contains(@class, 'dora-toggle')]");
        assertTrue("Dashboard should have collapsible sections", sections.size() >= 3);
    }

    @Test
    public void tableLinksHaveLinkClass() throws Exception {
        // Create a real job AND seed build data so the filter passes
        j.createFreeStyleProject("test-link-job");
        MetricsStore.getInstance().insertBuild("test-link-job", 1,
                System.currentTimeMillis(), 5000, "SUCCESS", "USER", "main");
        HtmlPage page = noJsClient().goTo("dora-metrics");
        List<DomElement> tableLinks = page.getByXPath(
                "//table[contains(@class, 'jenkins-table')]//a[contains(@class, 'jenkins-table__link')]");
        // With one build, at least the slowest pipelines table should have a link
        assertFalse("Table links should have jenkins-table__link class", tableLinks.isEmpty());
    }

    @Test
    public void rankingsFilterByJobPermission() throws Exception {
        // Create jobs and seed build data
        j.createFreeStyleProject("visible-job");
        j.createFreeStyleProject("hidden-job");

        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        store.insertBuild("visible-job", 1, now, 5000, "SUCCESS", "USER", "main");
        store.insertBuild("hidden-job", 1, now, 5000, "SUCCESS", "USER", "main");
        // Also seed a non-existent job (simulates renamed/deleted job)
        store.insertBuild("deleted-job", 1, now, 5000, "SUCCESS", "USER", "main");

        // Without security, all existing jobs visible, deleted-job filtered out
        DoraDashboardAction action = new DoraDashboardAction();
        List<RankedPipeline> slowest = action.getSlowestPipelines();
        assertTrue("Should see visible-job",
                slowest.stream().anyMatch(p -> p.jobName.equals("visible-job")));
        assertTrue("Should see hidden-job",
                slowest.stream().anyMatch(p -> p.jobName.equals("hidden-job")));
        assertFalse("Should NOT see deleted-job (no longer exists in Jenkins)",
                slowest.stream().anyMatch(p -> p.jobName.equals("deleted-job")));
    }

    @Test
    public void collapsibleSectionsHaveChevrons() throws Exception {
        HtmlPage page = noJsClient().goTo("dora-metrics");
        List<DomElement> toggles = page.getByXPath("//*[contains(@class, 'dora-toggle')]");
        for (DomElement toggle : toggles) {
            List<DomElement> chevrons = toggle.getByXPath(".//span[contains(@class, 'dora-chevron')]");
            assertFalse("Each toggle section should have a chevron: " + toggle.getTextContent().trim(),
                    chevrons.isEmpty());
        }
    }
}
