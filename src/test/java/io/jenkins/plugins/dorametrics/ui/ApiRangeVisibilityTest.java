package io.jenkins.plugins.dorametrics.ui;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The range endpoints show a user only what the dashboard would: nothing from jobs they cannot
 * read, nothing at all without Overall/Read, and a clear error for a range they half gave.
 */
@WithJenkins
class ApiRangeVisibilityTest {

    private static final long HOUR = 3_600_000L;

    private JenkinsRule j;
    private MetricsStore store;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        FreeStyleProject open = j.createFreeStyleProject("open-app");
        j.createFreeStyleProject("secret-app");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("alice")
                .grant(Item.READ).onItems(open).to("alice"));

        long earlier = System.currentTimeMillis() - 2 * HOUR;
        long open1 = store.insertBuild("open-app", 1, earlier, 1000, "SUCCESS", "SCM", "main");
        store.insertStage(open1, "open-stage", 500, "SUCCESS");
        long secret1 = store.insertBuild("secret-app", 1, earlier, 1000, "FAILURE", "SCM", "main");
        store.insertStage(secret1, "secret-stage", 900, "FAILURE");
        store.insertBuild("secret-app", 2, earlier + HOUR, 1000, "SUCCESS", "SCM", "main");
    }

    private Page get(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        if (user != null) wc.login(user);
        wc.setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        return wc.goTo(path, null);
    }

    private JSONObject json(String user, String path) throws Exception {
        Page page = get(user, path);
        assertEquals(200, page.getWebResponse().getStatusCode(), path);
        return JSONObject.fromObject(page.getWebResponse().getContentAsString());
    }

    private static int sum(JSONArray points, String key) {
        int total = 0;
        for (int i = 0; i < points.size(); i++) total += points.getJSONObject(i).getInt(key);
        return total;
    }

    @Test
    void stagesLeaveOutJobsTheViewerCannotRead() throws Exception {
        String asAlice = json("alice", "dora-api/stages?days=7").toString();
        assertTrue(asAlice.contains("open-stage"));
        assertFalse(asAlice.contains("secret-stage"));
        assertTrue(json("admin", "dora-api/stages?days=7").toString().contains("secret-stage"));
    }

    @Test
    void dailyFiguresCountOnlyJobsTheViewerCanRead() throws Exception {
        JSONArray alice = json("alice", "dora-api/trends?days=7").getJSONArray("trends");
        JSONArray admin = json("admin", "dora-api/trends?days=7").getJSONArray("trends");
        assertEquals(1, sum(alice, "deployments"));
        assertEquals(1, sum(alice, "total_builds"));
        assertEquals(2, sum(admin, "deployments"));
        assertEquals(3, sum(admin, "total_builds"));
        assertFalse(json("alice", "dora-api/pipelines?days=7").toString().contains("secret-app"));
    }

    @Test
    void withoutOverallReadTheRangeEndpointsSendToSignIn() throws Exception {
        for (String path : new String[] {"dora-api/stages?days=7", "dora-api/trends?from=2026-03-01&to=2026-03-02",
                "dora-api/overview?from=2026-03-01&to=2026-03-02", "dora-api/pipelines?days=7"}) {
            Page page = get(null, path);
            assertTrue(page.getUrl().getPath().endsWith("/login"), path + " ended at " + page.getUrl());
            String body = page.getWebResponse().getContentAsString();
            assertFalse(body.contains("open-stage") || body.contains("open-app") || body.contains("period_days"), path);
        }
    }

    @Test
    void aRangeGivenOnlyInPartIsABadRequest() throws Exception {
        assertEquals(400, get("admin", "dora-api/overview?from=2026-03-01").getWebResponse().getStatusCode());
        assertEquals(400, get("admin", "dora-api/trends?from=2026-03-01&to=03/02/2026").getWebResponse().getStatusCode());
        assertEquals(400, get("admin", "dora-api/export?format=csv&from=%2B300000000-01-01&to=%2B300000000-01-02")
                .getWebResponse().getStatusCode());
    }

    @Test
    void daysStillGiveTheWindowEndingNowWithEveryDayFilledIn() throws Exception {
        JSONArray points = json("admin", "dora-api/trends?days=3&tz=UTC").getJSONArray("trends");
        // a window of 3 x 24 hours touches 4 calendar days
        assertEquals(4, points.size());
        String buildDay = Instant.ofEpochMilli(System.currentTimeMillis() - 2 * HOUR).atZone(ZoneOffset.UTC).toLocalDate().toString();
        for (int i = 0; i < points.size(); i++) {
            JSONObject point = points.getJSONObject(i);
            if (!point.getString("date").equals(buildDay)) {
                assertEquals(0, point.getInt("total_builds"), point.getString("date"));
            }
        }
        assertEquals(3, sum(points, "total_builds"));
    }

    @Test
    void rankingsReturnAsManyRowsAsTheDashboardShows() throws Exception {
        long build = store.insertBuild("open-app", 3, System.currentTimeMillis() - HOUR, 1000, "SUCCESS", "SCM", "main");
        for (int i = 0; i < 120; i++) {
            store.insertStage(build, String.format("stage-%03d", i), 100 + i, "SUCCESS");
        }
        assertEquals(100, json("admin", "dora-api/stages?days=7&limit=150").getJSONArray("slowest").size());

        DoraGlobalConfiguration.get().setDashboardTopN(150);
        assertEquals(122, json("admin", "dora-api/stages?days=7&limit=150").getJSONArray("slowest").size());
    }
}
