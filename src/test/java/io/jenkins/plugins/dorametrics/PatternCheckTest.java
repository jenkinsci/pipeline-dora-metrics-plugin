package io.jenkins.plugins.dorametrics;

import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A pattern that is not a valid regex used to be replaced by the default on save without a
 * word. The form now says so while it is being typed.
 */
@WithJenkins
class PatternCheckTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private String check(String field, String value) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.setThrowExceptionOnFailingStatusCode(false);
        WebRequest req = new WebRequest(new URL(j.getURL() + "descriptorByName/"
                + DoraGlobalConfiguration.class.getName() + "/check" + field
                + "?value=" + URLEncoder.encode(value, StandardCharsets.UTF_8)), HttpMethod.POST);
        wc.addCrumb(req);
        WebResponse res = wc.getPage(req).getWebResponse();
        assertEquals(200, res.getStatusCode(), "the form has a check for " + field);
        return res.getContentAsString();
    }

    @Test
    void anInvalidPatternIsFlaggedInEachPatternField() throws Exception {
        for (String field : new String[] {"ProductionJobPattern", "ExcludedJobPattern", "ProductionBranchPattern"}) {
            assertTrue(check(field, "prod-(api").contains("error"), field);
            assertFalse(check(field, "prod-(api|web)").contains("error"), field);
            assertFalse(check(field, "").contains("error"), field);
        }
    }
}
