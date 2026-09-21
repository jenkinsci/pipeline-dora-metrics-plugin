package io.jenkins.plugins.dorametrics;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.dorametrics.export.HttpExportConfig;
import io.jenkins.plugins.dorametrics.export.S3ExportConfig;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Recorded build data must follow the Item/Read permission of the job it came
 * from. alice may read only "visible", bob has Overall/Read and nothing else,
 * carol may discover "secret" but not read it.
 */
public class JobVisibilityTest {

    private static final String SECRET_STAGE = "deploy-to-customer-x";
    private static final String VISIBLE_STAGE = "unit-tests";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;

    @Before
    public void setUp() throws Exception {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();

        j.createFreeStyleProject("visible");
        MockFolder team = j.createFolder("team");
        team.createProject(FreeStyleProject.class, "secret");

        long now = System.currentTimeMillis();
        for (int i = 1; i <= 3; i++) {
            long id = store.insertBuild("visible", i, now - i * 60_000L, 5000, "SUCCESS", "USER", "main");
            store.insertStage(id, VISIBLE_STAGE, 1000, "SUCCESS");
            long sid = store.insertBuild("team/secret", i, now - i * 60_000L, 9000,
                    i == 2 ? "FAILURE" : "SUCCESS", "USER", "release-x");
            store.insertStage(sid, SECRET_STAGE, 8000, i == 2 ? "FAILURE" : "SUCCESS");
        }

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("alice", "bob", "carol")
                .grant(Item.READ).onItems(j.jenkins.getItem("visible")).to("alice")
                .grant(Item.DISCOVER).onItems(team, team.getItem("secret")).to("carol"));
    }

    private JenkinsRule.WebClient as(String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login(user);
        wc.setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        return wc;
    }

    private String get(String user, String path) throws Exception {
        Page p = as(user).goTo(path, null);
        return p.getWebResponse().getContentAsString();
    }

    private int status(String user, String path) throws Exception {
        return as(user).goTo(path, null).getWebResponse().getStatusCode();
    }

    @Test
    public void exportContainsOnlyReadableJobs() throws Exception {
        for (String format : new String[] {"csv", "json"}) {
            String alice = get("alice", "dora-api/export?format=" + format);
            assertTrue(format + ": alice sees her job", alice.contains("visible"));
            assertFalse(format + ": alice must not see the secret job", alice.contains("secret"));
            assertFalse(format + ": alice must not see its branch", alice.contains("release-x"));

            String bob = get("bob", "dora-api/export?format=" + format);
            assertFalse(format + ": bob must not see any job", bob.contains("visible") || bob.contains("secret"));

            String admin = get("admin", "dora-api/export?format=" + format);
            assertTrue(format + ": admin sees both", admin.contains("visible") && admin.contains("team/secret"));
        }
        JSONObject bobJson = JSONObject.fromObject(get("bob", "dora-api/export"));
        assertEquals(0, bobJson.getInt("total_builds"));
    }

    @Test
    public void trendsOfUnreadableJobLooksLikeMissingJob() throws Exception {
        assertEquals(200, status("alice", "dora-api/trends?job=visible"));
        assertEquals(200, status("admin", "dora-api/trends?job=team/secret"));

        int hidden = status("alice", "dora-api/trends?job=team/secret");
        int missing = status("alice", "dora-api/trends?job=no/such/job");
        assertEquals(404, hidden);
        assertEquals("no way to tell a hidden job from a missing one", missing, hidden);
        assertEquals(get("alice", "dora-api/trends?job=no/such/job").length(),
                get("alice", "dora-api/trends?job=team/secret").length());

        // Item/Discover without Item/Read is still not enough
        assertEquals(404, status("carol", "dora-api/trends?job=team/secret"));
    }

    @Test
    public void aggregatesCountOnlyReadableJobs() throws Exception {
        assertEquals(3, totalBuilds(get("alice", "dora-api/trends")));
        assertEquals(0, totalBuilds(get("bob", "dora-api/trends")));
        assertEquals(6, totalBuilds(get("admin", "dora-api/trends")));

        JSONObject bob = JSONObject.fromObject(get("bob", "dora-api/overview"));
        assertEquals(0.0, bob.getJSONObject("deployment_frequency").getDouble("raw_value"), 0.0001);
        assertEquals("N/A", bob.getJSONObject("change_failure_rate").getString("value"));

        // alice's job never failed, the failure belongs to the secret job
        JSONObject alice = JSONObject.fromObject(get("alice", "dora-api/overview"));
        assertEquals(0.0, alice.getJSONObject("change_failure_rate").getDouble("raw_value"), 0.0001);
        JSONObject admin = JSONObject.fromObject(get("admin", "dora-api/overview"));
        assertTrue(admin.getJSONObject("change_failure_rate").getDouble("raw_value") > 0);
    }

    private static int totalBuilds(String trendsJson) {
        JSONArray points = JSONObject.fromObject(trendsJson).getJSONArray("trends");
        int total = 0;
        for (int i = 0; i < points.size(); i++) {
            total += points.getJSONObject(i).getInt("total_builds");
        }
        return total;
    }

    @Test
    public void rankingsApiHidesUnreadableJobsAndSurvivesDiscoverOnly() throws Exception {
        String alice = get("alice", "dora-api/pipelines");
        assertTrue(alice.contains("visible"));
        assertFalse(alice.contains("secret"));

        // Discover-only access used to surface as an error for the whole endpoint
        assertEquals(200, status("carol", "dora-api/pipelines"));
        assertFalse(get("carol", "dora-api/pipelines").contains("secret"));
    }

    @Test
    public void dashboardPageHidesStagesOfUnreadableJobs() throws Exception {
        String alice = get("alice", "dora-metrics/");
        assertTrue("alice sees the stages of her job", alice.contains(VISIBLE_STAGE));
        assertFalse("stage names of the secret job must not leak", alice.contains(SECRET_STAGE));
        assertFalse(alice.contains("team/secret"));

        String carol = get("carol", "dora-metrics/");
        assertFalse(carol.contains(SECRET_STAGE));

        String admin = get("admin", "dora-metrics/");
        assertTrue(admin.contains(SECRET_STAGE) && admin.contains(VISIBLE_STAGE));
    }

    @Test
    public void jobPageStillShowsItsOwnNumbers() throws Exception {
        assertEquals(200, status("alice", "job/visible/dora-metrics/"));
        assertEquals(404, status("alice", "job/team/job/secret/dora-metrics/"));
        assertEquals(200, status("admin", "job/team/job/secret/dora-metrics/"));
    }

    @Test
    public void deletedJobDoesNotHandItsHistoryToANewJobOfTheSameName() throws Exception {
        long from = 0;
        long to = System.currentTimeMillis() + 1000;
        assertEquals(3, store.getBuilds("visible", from, to).size());

        j.jenkins.getItem("visible").delete();
        assertEquals("history is detached from the name", 0, store.getBuilds("visible", from, to).size());
        assertEquals("but kept for the totals", 6, store.getAllBuilds(from, to).size());

        j.createFreeStyleProject("visible");
        assertEquals(0, store.getBuilds("visible", from, to).size());
        assertFalse(store.getAllJobNames().contains("visible"));

        // only administrators still see the detached history
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("alice")
                .grant(Item.READ).onItems(j.jenkins.getItem("visible")).to("alice"));
        assertEquals(0, JSONObject.fromObject(get("alice", "dora-api/export")).getInt("total_builds"));
        assertTrue(get("admin", "dora-api/export").contains("visible" + MetricsStore.DELETED_MARKER));
    }

    @Test
    public void deletingAFolderDetachesEverythingBelowIt() throws Exception {
        long to = System.currentTimeMillis() + 1000;
        j.jenkins.getItem("team").delete();
        assertEquals(0, store.getBuilds("team/secret", 0, to).size());
        assertEquals(3, store.getBuilds("visible", 0, to).size());
        assertEquals(6, store.getAllBuilds(0, to).size());
    }

    @Test
    public void credentialIdsAreListedForAdministratorsOnly() throws Exception {
        Jenkins.MANAGE.setEnabled(true);
        SystemCredentialsProvider.getInstance().getCredentials().add(new UsernamePasswordCredentialsImpl(
                CredentialsScope.GLOBAL, "export-creds", "for the export", "user", "pass"));
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ, Jenkins.MANAGE).everywhere().to("manager"));

        HttpExportConfig.DescriptorImpl http = j.jenkins.getDescriptorByType(HttpExportConfig.DescriptorImpl.class);
        S3ExportConfig.DescriptorImpl s3 = j.jenkins.getDescriptorByType(S3ExportConfig.DescriptorImpl.class);

        try (ACLContext ignored = ACL.as(User.getById("manager", true))) {
            assertFalse(ids(http.doFillCredentialsIdItems("")).contains("export-creds"));
            assertFalse(ids(s3.doFillCredentialsIdItems("")).contains("export-creds"));
        }
        try (ACLContext ignored = ACL.as(User.getById("admin", true))) {
            assertTrue(ids(http.doFillCredentialsIdItems("")).contains("export-creds"));
            assertTrue(ids(s3.doFillCredentialsIdItems("")).contains("export-creds"));
        }
    }

    private static String ids(ListBoxModel model) {
        StringBuilder sb = new StringBuilder();
        for (ListBoxModel.Option o : model) {
            sb.append(o.value).append(' ');
        }
        return sb.toString();
    }
}
