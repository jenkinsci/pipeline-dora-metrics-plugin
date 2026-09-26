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

import java.net.URL;

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
                .grant(jenkins.model.Jenkins.READ).everywhere().to("viewer"));
    }

    @Test
    public void importRejectsGet() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("boss");
        wc.setThrowExceptionOnFailingStatusCode(false);

        WebResponse res = wc.getPage(new WebRequest(
                new URL(j.getURL() + "dora-api/importHistory"), HttpMethod.GET)).getWebResponse();

        assertTrue("GET must not be able to start an import, got " + res.getStatusCode(),
                res.getStatusCode() == 405 || res.getStatusCode() == 404);
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

    @Test
    public void statusExposesCountersOnly() throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login("boss");
        j.createFreeStyleProject("a-private-job-name");

        String body = wc.getPage(new WebRequest(
                new URL(j.getURL() + "dora-api/importStatus"), HttpMethod.GET))
                .getWebResponse().getContentAsString();

        assertTrue("must not leak job names", !body.contains("a-private-job-name"));
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
    }
}
