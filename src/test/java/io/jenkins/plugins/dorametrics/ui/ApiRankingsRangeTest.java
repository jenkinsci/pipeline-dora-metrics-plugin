package io.jenkins.plugins.dorametrics.ui;

import io.jenkins.plugins.dorametrics.store.MetricsStore;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Everything the dashboard lists can be fetched for the range it shows, not a fixed 30 days. */
@WithJenkins
class ApiRankingsRangeTest {

    private JenkinsRule j;

    private MetricsStore store;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        j.createFreeStyleProject("app");
    }

    private JSONObject get(String path) throws Exception {
        return JSONObject.fromObject(j.createWebClient().goTo(path, "application/json").getWebResponse().getContentAsString());
    }

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    @Test
    void mostImprovedComparesWithThePeriodBeforeTheRange() throws Exception {
        store.insertBuild("app", 1, at("2026-02-15T10:00:00Z"), 100_000, "SUCCESS", "SCM", "main");
        store.insertBuild("app", 2, at("2026-03-15T10:00:00Z"), 40_000, "SUCCESS", "SCM", "main");

        JSONArray improved = get("dora-api/pipelines?from=2026-03-01&to=2026-03-31&tz=UTC").getJSONArray("most_improved");
        assertEquals(1, improved.size());
        assertEquals("app", improved.getJSONObject(0).getString("job"));
    }

    @Test
    void stageRankingsFollowTheRange() throws Exception {
        long march = store.insertBuild("app", 1, at("2026-03-15T10:00:00Z"), 1000, "SUCCESS", "SCM", "main");
        store.insertStage(march, "deploy", 5000, "FAILURE");
        long may = store.insertBuild("app", 2, at("2026-05-15T10:00:00Z"), 1000, "SUCCESS", "SCM", "main");
        store.insertStage(may, "test", 9000, "SUCCESS");

        JSONObject stages = get("dora-api/stages?from=2026-03-01&to=2026-03-31&tz=UTC");
        assertEquals(1, stages.getJSONArray("slowest").size());
        assertEquals("deploy", stages.getJSONArray("slowest").getJSONObject(0).getString("stage"));
        assertEquals("deploy", stages.getJSONArray("most_failing").getJSONObject(0).getString("stage"));
    }
}
