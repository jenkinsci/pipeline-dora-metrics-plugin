package io.jenkins.plugins.dorametrics.rankings;

import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.rankings.PipelineRanker.RankedPipeline;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.ui.DoraDashboardAction;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RankingFixesTest {

    private static final long DAY = 86_400_000L;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;
    private long now;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        now = System.currentTimeMillis();
    }

    private PipelineRanker ranker() {
        return new PipelineRanker(store, Collections.emptySet());
    }

    private static List<String> names(List<RankedPipeline> ranked) {
        return ranked.stream().map(p -> p.jobName).collect(Collectors.toList());
    }

    @Test
    public void mostImprovedListsOnlyPipelinesThatGotFaster() {
        store.insertBuild("faster", 1, now - 40 * DAY, 100_000, "SUCCESS", "SCM", "main");
        store.insertBuild("faster", 2, now - DAY, 50_000, "SUCCESS", "SCM", "main");
        store.insertBuild("slower", 1, now - 40 * DAY, 50_000, "SUCCESS", "SCM", "main");
        store.insertBuild("slower", 2, now - DAY, 100_000, "SUCCESS", "SCM", "main");

        List<RankedPipeline> improved = ranker().mostImproved(now - 30 * DAY, now, now - 60 * DAY, now - 30 * DAY, 10);
        assertEquals(List.of("faster"), names(improved));
    }

    @Test
    public void anAbortedRunIsNotFlakiness() {
        String[] results = {"SUCCESS", "ABORTED", "SUCCESS", "ABORTED", "SUCCESS", "NOT_BUILT", "SUCCESS"};
        for (int i = 0; i < results.length; i++) {
            store.insertBuild("steady", i + 1, now - (10 - i) * 60_000L, 1000, results[i], "SCM", "main");
        }
        String[] flaky = {"SUCCESS", "FAILURE", "SUCCESS", "FAILURE"};
        for (int i = 0; i < flaky.length; i++) {
            store.insertBuild("flaky", i + 1, now - (10 - i) * 60_000L, 1000, flaky[i], "SCM", "main");
        }

        assertEquals("an aborted or skipped run between two successes is not a flip",
                List.of("flaky"), names(ranker().flakiestPipelines(now - DAY, now, 10)));
    }

    @Test
    public void mostFailingRanksByRateNotByCount() {
        for (int i = 0; i < 100; i++) {
            store.insertBuild("busy", i + 1, now - i * 60_000L, 1000, i < 10 ? "FAILURE" : "SUCCESS", "SCM", "main");
            if (i < 90) store.insertBuild("busy-2", i + 1, now - i * 60_000L, 1000, i < 9 ? "FAILURE" : "SUCCESS", "SCM", "main");
        }
        store.insertBuild("broken", 1, now - 60_000L, 1000, "FAILURE", "SCM", "main");

        assertEquals(List.of("broken"), names(ranker().mostFailingPipelines(now - DAY, now, 1)));
    }

    @Test
    public void historyOfDeletedJobsDoesNotTakeRankingSlots() throws Exception {
        DoraGlobalConfiguration.get().setDashboardTopN(2);
        j.createFreeStyleProject("a");
        j.createFreeStyleProject("b");
        store.insertBuild("gone" + MetricsStore.DELETED_MARKER + "1", 1, now - 60_000L, 900_000, "SUCCESS", "SCM", "main");
        store.insertBuild("a", 1, now - 60_000L, 20_000, "SUCCESS", "SCM", "main");
        store.insertBuild("b", 1, now - 60_000L, 10_000, "SUCCESS", "SCM", "main");

        assertEquals(List.of("a", "b"), names(new DoraDashboardAction().getSlowestPipelines()));
    }

    @Test
    public void linksWorkForNamesThatNeedEncoding() {
        DoraDashboardAction dashboard = new DoraDashboardAction();
        assertEquals("job/team/job/feature%252Fx", dashboard.jobUrl("team/feature%2Fx"));
        assertEquals("job/my%20app", dashboard.jobUrl("my app"));
        assertEquals("job/prod/job/api", dashboard.jobUrl("prod/api"));
    }
}
