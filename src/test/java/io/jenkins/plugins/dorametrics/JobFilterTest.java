package io.jenkins.plugins.dorametrics;

import io.jenkins.plugins.dorametrics.rankings.PipelineRanker;
import io.jenkins.plugins.dorametrics.store.MetricsExporter;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import io.jenkins.plugins.dorametrics.ui.DoraDashboardAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The job settings (production pattern, production folders, excluded pattern) decide what
 * every part of the plugin shows, and they are applied when data is read, so changing them
 * also applies to builds recorded before the change.
 */
@WithJenkins
class JobFilterTest {

    private JenkinsRule j;

    private MetricsStore store;
    private DoraGlobalConfiguration config;
    private long now;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        config = DoraGlobalConfiguration.get();
        now = System.currentTimeMillis();
    }

    private void build(String job, int number, String result) {
        store.insertBuild(job, number, now - number * 60_000L, 1000, result, "SCM", "main");
    }

    @Test
    void aRegexWithAlternationCountsInEveryMetric() {
        build("api-prod", 1, "SUCCESS");
        build("api-prod", 2, "SUCCESS");
        build("web-prod", 1, "FAILURE");
        build("api-staging", 1, "SUCCESS");
        long from = now - 86_400_000L;

        assertEquals(2, store.countSuccessfulBuilds(from, now, "(api|web)-prod"));
        assertEquals(3, store.countTotalBuilds(from, now, "(api|web)-prod"));
        assertEquals(1, store.countFailedBuilds(from, now, "(api|web)-prod"));
    }

    @Test
    void anAnchoredPatternMatchesLikeEverywhereElse() {
        build("prod-api", 1, "SUCCESS");
        build("api", 1, "SUCCESS");
        assertEquals(1, store.countSuccessfulBuilds(now - 86_400_000L, now, "^prod.*$"));
    }

    @Test
    void leadTimeUsesTheSamePattern() {
        long id = store.insertBuild("api-prod", 1, now - 60_000L, 1000, "SUCCESS", "SCM", "main");
        store.insertCommit(id, "abc", "dev", now - 3_600_000L);
        assertTrue(store.avgLeadTimeMs(now - 86_400_000L, now, "(api|web)-prod") > 0);
    }

    @Test
    void jobsInAProductionFolderReachTheCards() {
        config.setProductionJobPattern("nothing-matches-this");
        config.setProductionFolders("production");
        build("production/app", 1, "SUCCESS");
        build("sandbox/app", 1, "SUCCESS");

        DoraDashboardAction dashboard = new DoraDashboardAction();
        assertEquals(1.0 / 30,
                dashboard.getDeploymentFrequency().rawValue, 1e-9, "the folder job is tracked, so it counts");
    }

    @Test
    void excludingAJobLaterHidesItsHistoryEverywhere() {
        build("legacy-job", 1, "FAILURE");
        build("legacy-job", 2, "FAILURE");
        build("legacy-job", 3, "FAILURE");
        build("app", 1, "SUCCESS");

        config.setExcludedJobPattern("legacy-.*");

        DoraDashboardAction dashboard = new DoraDashboardAction();
        assertEquals("0.0%", dashboard.getChangeFailureRate().displayValue);
        assertTrue(dashboard.getMostFailingPipelines().stream().noneMatch(p -> p.jobName.equals("legacy-job")));
        assertTrue(new PipelineRanker().flakiestPipelines(now - 86_400_000L, now, 10).isEmpty());
        assertFalse(MetricsExporter.exportFullDump(30).contains("legacy-job"),
                "the scheduled export follows the same settings");
    }

    @Test
    void aDeletedJobsHistoryStillCountsUnderItsOriginalName() {
        config.setProductionJobPattern(".*-prod");
        build("api-prod" + MetricsStore.DELETED_MARKER + "1700000000000", 1, "SUCCESS");
        build("api-staging", 1, "SUCCESS");

        assertEquals(1.0 / 30,
                new DoraDashboardAction().getDeploymentFrequency().rawValue, 1e-9, "detached history of a production job is still production history");
    }
}
