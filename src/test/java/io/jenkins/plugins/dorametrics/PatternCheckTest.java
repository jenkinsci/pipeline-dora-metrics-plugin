package io.jenkins.plugins.dorametrics;

import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A pattern that is not a valid regex used to be replaced by the default on save without a
 * word. The form now says so while it is being typed.
 */
public class PatternCheckTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private String check(String field, String value) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.setThrowExceptionOnFailingStatusCode(false);
        WebRequest req = new WebRequest(new URL(j.getURL() + "descriptorByName/"
                + DoraGlobalConfiguration.class.getName() + "/check" + field
                + "?value=" + URLEncoder.encode(value, StandardCharsets.UTF_8)), HttpMethod.POST);
        wc.addCrumb(req);
        WebResponse res = wc.getPage(req).getWebResponse();
        assertEquals("the form has a check for " + field, 200, res.getStatusCode());
        return res.getContentAsString();
    }

    @Test
    public void anInvalidPatternIsFlaggedInEachPatternField() throws Exception {
        for (String field : new String[] {"ProductionJobPattern", "ExcludedJobPattern", "ProductionBranchPattern"}) {
            assertTrue(field, check(field, "prod-(api").contains("error"));
            assertTrue(field, !check(field, "prod-(api|web)").contains("error"));
            assertTrue(field, !check(field, "").contains("error"));
        }
    }
}
