package io.jenkins.plugins.dorametrics.ui;


import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;

import io.jenkins.plugins.dorametrics.collectors.BuildHistoryImporter;
import net.sf.json.JSONObject;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The import endpoints are the first state-changing ones in this plugin, so the POST-only
 * and permission rules are worth asserting rather than assuming.
 */
public class ImportEndpointsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(jenkins.model.Jenkins.ADMINISTER).everywhere().to("boss")
                .grant(jenkins.model.Jenkins.READ).everywhere().to("viewer")
                .grant(jenkins.model.Jenkins.MANAGE, jenkins.model.Jenkins.READ).everywhere().to("manager"));
    }

    @Test
    public void importRejectsGet() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("boss");
        wc.setThrowExceptionOnFailingStatusCode(false);

        WebResponse res = wc.getPage(new WebRequest(
                new URL(j.getURL() + "dora-api/importHistory"), HttpMethod.GET)).getWebResponse();

        // Stapler makes a @POST method unresolvable rather than method-not-allowed, so this
        // is a 404. Accepting 404 alone would also pass for a mistyped URL, so the same URL
        // is posted to below and must work: that is what proves the path is right.
        assertEquals("GET must not be able to start an import", 404, res.getStatusCode());

        WebRequest post = new WebRequest(
                new URL(j.getURL() + "dora-api/importHistory"), HttpMethod.POST);
        wc.addCrumb(post);
        assertEquals("and the same URL must work as a POST", 200,
                wc.getPage(post).getWebResponse().getStatusCode());
    }

    @Test
    public void importRejectsAUserWithoutAdminister() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("viewer");
        wc.setThrowExceptionOnFailingStatusCode(false);

        // Send a valid crumb, so a 403 here proves the permission check and not CSRF.
        WebRequest req = new WebRequest(
                new URL(j.getURL() + "dora-api/importHistory"), HttpMethod.POST);
        wc.addCrumb(req);
        WebResponse res = wc.getPage(req).getWebResponse();

        assertEquals("a read-only user must not start an import", 403, res.getStatusCode());
    }

    @Test
    public void statusRejectsAUserWithoutAdminister() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("viewer");
        wc.setThrowExceptionOnFailingStatusCode(false);

        WebResponse res = wc.getPage(new WebRequest(
                new URL(j.getURL() + "dora-api/importStatus"), HttpMethod.GET)).getWebResponse();

        assertEquals("the counters are admin only", 403, res.getStatusCode());
    }

    @Test
    public void adminCanReadTheStatus() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("boss");

        WebResponse res = wc.getPage(new WebRequest(
                new URL(j.getURL() + "dora-api/importStatus"), HttpMethod.GET)).getWebResponse();

        assertEquals(200, res.getStatusCode());
        assertTrue("status should report whether an import is running",
                res.getContentAsString().contains("running"));
    }

    /**
     * Result holds only counters, so asserting a job name is absent checks something the type
     * already guarantees. Asserting the key set instead fails the day a field carrying names
     * is added.
     */
    @Test
    public void statusExposesCountersOnly() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("boss");
        j.buildAndAssertSuccess(j.createFreeStyleProject("a-private-job-name"));

        WebRequest start = new WebRequest(
                new URL(j.getURL() + "dora-api/importHistory"), HttpMethod.POST);
        wc.addCrumb(start);
        wc.getPage(start);
        waitForImport();

        String body = wc.getPage(new WebRequest(
                new URL(j.getURL() + "dora-api/importStatus"), HttpMethod.GET))
                .getWebResponse().getContentAsString();

        assertTrue("an import must have run, so there are counters to inspect",
                body.contains("recorded"));
        assertTrue("must not leak job names", !body.contains("a-private-job-name"));

        Set<String> allowed = new HashSet<>(Arrays.asList(
                "running", "jobs", "recorded", "skipped", "failed", "durationMs",
                "completed", "error", "message"));
        JSONObject json = JSONObject.fromObject(body);
        for (Object key : json.keySet()) {
            assertTrue("unexpected key in the status body: " + key, allowed.contains(key.toString()));
        }
    }

    @Test
    public void importRejectsAPostWithoutACrumb() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("boss");
        wc.setThrowExceptionOnFailingStatusCode(false);

        WebResponse res = wc.getPage(new WebRequest(
                new URL(j.getURL() + "dora-api/importHistory"), HttpMethod.POST)).getWebResponse();

        assertEquals("even an admin needs a crumb", 403, res.getStatusCode());
    }

    @Test
    public void adminCanStartAnImport() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("boss");
        j.buildAndAssertSuccess(j.createFreeStyleProject("importable"));

        WebRequest req = new WebRequest(
                new URL(j.getURL() + "dora-api/importHistory"), HttpMethod.POST);
        wc.addCrumb(req);
        WebResponse res = wc.getPage(req).getWebResponse();

        assertEquals(200, res.getStatusCode());
        assertTrue("should report that it started", res.getContentAsString().contains("started"));

        waitForImport(); // otherwise the run outlives this test
    }

    /** Manage sits between Read and Administer, so it is the near miss worth asserting. */
    @Test
    public void manageIsNotEnoughForEitherEndpoint() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("manager");
        wc.setThrowExceptionOnFailingStatusCode(false);

        WebRequest post = new WebRequest(
                new URL(j.getURL() + "dora-api/importHistory"), HttpMethod.POST);
        wc.addCrumb(post);
        assertEquals("Manage must not start an import", 403,
                wc.getPage(post).getWebResponse().getStatusCode());

        assertEquals("nor read the counters", 403, wc.getPage(new WebRequest(
                new URL(j.getURL() + "dora-api/importStatus"), HttpMethod.GET))
                .getWebResponse().getStatusCode());
    }

    private void waitForImport() throws Exception {
        for (int i = 0; i < 100 && BuildHistoryImporter.isRunning(); i++) {
            Thread.sleep(100);
        }
        assertTrue("the import should have finished", !BuildHistoryImporter.isRunning());
    }
}
