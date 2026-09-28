package io.jenkins.plugins.dorametrics.ui;

import io.jenkins.plugins.dorametrics.store.MetricsStore;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The API answers for any date range, buckets days in the viewer's time zone, gives every day
 * of the range a point, and carries the four DORA metrics per day, so a chart can show what
 * the cards show.
 */
public class ApiRangeTest {

    private static final long MIN = 60_000L;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
    }

    private JSONObject get(String path) throws Exception {
        return JSONObject.fromObject(j.createWebClient().goTo(path, "application/json").getWebResponse().getContentAsString());
    }

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    @Test
    public void overviewCoversTheRequestedDates() throws Exception {
        store.insertBuild("app", 1, at("2026-03-10T10:00:00Z"), 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("app", 2, at("2026-03-11T10:00:00Z"), 1000, "FAILURE", "SCM", "main");
        store.insertBuild("app", 3, at("2026-05-01T10:00:00Z"), 1000, "SUCCESS", "SCM", "main");

        JSONObject march = get("dora-api/overview?from=2026-03-10&to=2026-03-11&tz=UTC");
        assertEquals(2, march.getInt("period_days"));
        assertEquals("50.0%", march.getJSONObject("change_failure_rate").getString("value"));
    }

    @Test
    public void everyDayOfTheRangeHasAPoint() throws Exception {
        store.insertBuild("app", 1, at("2026-03-10T10:00:00Z"), 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("app", 2, at("2026-03-13T10:00:00Z"), 1000, "SUCCESS", "SCM", "main");

        JSONArray trends = get("dora-api/trends?from=2026-03-09&to=2026-03-14&tz=UTC").getJSONArray("trends");
        assertEquals(6, trends.size());
        assertEquals("2026-03-09", trends.getJSONObject(0).getString("date"));
        assertEquals("2026-03-14", trends.getJSONObject(5).getString("date"));
        assertEquals(0, trends.getJSONObject(0).getInt("total_builds"));
        assertEquals(1, trends.getJSONObject(1).getInt("total_builds"));
        assertEquals(0, trends.getJSONObject(2).getInt("total_builds"));
    }

    @Test
    public void daysFollowTheViewersTimeZone() throws Exception {
        store.insertBuild("app", 1, at("2026-03-10T20:00:00Z"), 1000, "SUCCESS", "SCM", "main");

        JSONArray utc = get("dora-api/trends?from=2026-03-10&to=2026-03-11&tz=UTC").getJSONArray("trends");
        JSONArray kolkata = get("dora-api/trends?from=2026-03-10&to=2026-03-11&tz=Asia/Kolkata").getJSONArray("trends");
        assertEquals("20:00 UTC is still the 10th", 1, utc.getJSONObject(0).getInt("total_builds"));
        assertEquals("but already the 11th in India", 1, kolkata.getJSONObject(1).getInt("total_builds"));
        assertEquals(0, kolkata.getJSONObject(0).getInt("total_builds"));
    }

    @Test
    public void eachDayCarriesTheFourMetrics() throws Exception {
        long fail = store.insertBuild("app", 1, at("2026-03-10T09:00:00Z"), MIN, "FAILURE", "SCM", "main");
        store.insertCommit(fail, "c1", "dev", at("2026-03-10T08:00:00Z"));
        store.insertBuild("app", 2, at("2026-03-10T10:00:00Z"), 10 * MIN, "SUCCESS", "SCM", "main");
        store.insertBuild("app", 3, at("2026-03-10T11:00:00Z"), MIN, "ABORTED", "SCM", "main");

        JSONObject day = get("dora-api/trends?from=2026-03-10&to=2026-03-10&tz=UTC").getJSONArray("trends").getJSONObject(0);
        assertEquals(1, day.getInt("deployments"));
        assertEquals("1 failed of 2 deployments", 50.0, day.getDouble("change_failure_rate"), 0.01);
        assertEquals("08:00 commit, deployed by the build that ended 10:10", 130 * MIN, day.getLong("lead_time_ms"));
        assertEquals("failed at 09:00, fixed by 10:10", 70 * MIN, day.getLong("restore_time_ms"));
    }

    @Test
    public void aDayWithoutDeploymentsHasNoRates() throws Exception {
        JSONObject day = get("dora-api/trends?from=2026-03-10&to=2026-03-10&tz=UTC").getJSONArray("trends").getJSONObject(0);
        assertEquals(0, day.getInt("deployments"));
        assertTrue(day.get("change_failure_rate") instanceof net.sf.json.JSONNull);
        assertTrue(day.get("lead_time_ms") instanceof net.sf.json.JSONNull);
        assertTrue(day.get("restore_time_ms") instanceof net.sf.json.JSONNull);
    }
}
