package io.jenkins.plugins.dorametrics.rankings;

import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.Assert.*;

public class PipelineRankerTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;
    private PipelineRanker ranker;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        ranker = new PipelineRanker();
    }

    @Test
    public void slowestPipelinesRankedCorrectly() {
        long now = System.currentTimeMillis();
        store.insertBuild("fast-job", 1, now, 2000, "SUCCESS", "USER", null);
        store.insertBuild("slow-job", 1, now, 30000, "SUCCESS", "USER", null);
        store.insertBuild("medium-job", 1, now, 10000, "SUCCESS", "USER", null);

        List<PipelineRanker.RankedPipeline> slowest = ranker.slowestPipelines(now - 1000, now + 1000, 10);
        assertEquals(3, slowest.size());
        assertEquals("slow-job", slowest.get(0).jobName);
        assertEquals("medium-job", slowest.get(1).jobName);
        assertEquals("fast-job", slowest.get(2).jobName);
    }

    @Test
    public void mostFailingPipelinesRankedCorrectly() {
        long now = System.currentTimeMillis();
        // good-job: 0% failure
        store.insertBuild("good-job", 1, now, 5000, "SUCCESS", "USER", null);
        store.insertBuild("good-job", 2, now, 5000, "SUCCESS", "USER", null);
        // bad-job: 100% failure
        store.insertBuild("bad-job", 1, now, 5000, "FAILURE", "USER", null);
        store.insertBuild("bad-job", 2, now, 5000, "FAILURE", "USER", null);

        List<PipelineRanker.RankedPipeline> failing = ranker.mostFailingPipelines(now - 1000, now + 1000, 10);
        assertEquals("bad-job", failing.get(0).jobName);
        assertEquals(100.0, failing.get(0).value, 0.1);
    }

    @Test
    public void flakiestPipelinesDetected() {
        long now = System.currentTimeMillis();
        // Flaky: pass-fail-pass-fail = 100% transitions
        store.insertBuild("flaky-job", 1, now - 4000, 1000, "SUCCESS", "USER", null);
        store.insertBuild("flaky-job", 2, now - 3000, 1000, "FAILURE", "USER", null);
        store.insertBuild("flaky-job", 3, now - 2000, 1000, "SUCCESS", "USER", null);
        store.insertBuild("flaky-job", 4, now - 1000, 1000, "FAILURE", "USER", null);

        // Stable: all pass
        store.insertBuild("stable-job", 1, now - 4000, 1000, "SUCCESS", "USER", null);
        store.insertBuild("stable-job", 2, now - 3000, 1000, "SUCCESS", "USER", null);
        store.insertBuild("stable-job", 3, now - 2000, 1000, "SUCCESS", "USER", null);

        List<PipelineRanker.RankedPipeline> flaky = ranker.flakiestPipelines(now - 5000, now + 1000, 10);
        assertFalse(flaky.isEmpty());
        assertEquals("flaky-job", flaky.get(0).jobName);
        assertEquals(100.0, flaky.get(0).value, 0.1);
    }

    @Test
    public void slowestStagesRanked() {
        long now = System.currentTimeMillis();
        long b1 = store.insertBuild("stage-rank-job", 1, now, 10000, "SUCCESS", "USER", null);
        store.insertStage(b1, "Build", 2000, "SUCCESS");
        store.insertStage(b1, "Test", 6000, "SUCCESS");
        store.insertStage(b1, "Deploy", 1000, "SUCCESS");

        List<PipelineRanker.RankedStage> stages = ranker.slowestStages(now - 1000, now + 1000, 10);
        assertEquals("Test", stages.get(0).stageName);
        assertEquals("Build", stages.get(1).stageName);
        assertEquals("Deploy", stages.get(2).stageName);
    }

    @Test
    public void mostFailingStagesRanked() {
        long now = System.currentTimeMillis();
        long b1 = store.insertBuild("fail-stage-job", 1, now, 10000, "FAILURE", "USER", null);
        store.insertStage(b1, "Build", 2000, "SUCCESS");
        store.insertStage(b1, "Test", 6000, "FAILURE");

        long b2 = store.insertBuild("fail-stage-job", 2, now, 10000, "FAILURE", "USER", null);
        store.insertStage(b2, "Build", 2000, "SUCCESS");
        store.insertStage(b2, "Test", 6000, "FAILURE");

        List<PipelineRanker.RankedStage> stages = ranker.mostFailingStages(now - 1000, now + 1000, 10);
        assertEquals("Test", stages.get(0).stageName);
        assertEquals(100.0, stages.get(0).value, 0.1);
    }

    @Test
    public void limitRespected() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            store.insertBuild("limit-job-" + i, 1, now, 5000 + i * 1000, "SUCCESS", "USER", null);
        }

        List<PipelineRanker.RankedPipeline> slowest = ranker.slowestPipelines(now - 1000, now + 1000, 5);
        assertEquals(5, slowest.size());
    }

    @Test
    public void ignoresDisabledPipelinesWhenConfigured() throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        j.createFreeStyleProject("rank-active");
        FreeStyleProject disabled = j.createFreeStyleProject("rank-disabled");
        disabled.disable();

        long now = System.currentTimeMillis();
        // The disabled job is the slowest, the flakiest and has the slowest stage.
        store.insertBuild("rank-active", 1, now - 3000, 1000, "SUCCESS", "USER", null);
        store.insertBuild("rank-active", 2, now - 2000, 1000, "SUCCESS", "USER", null);
        store.insertBuild("rank-active", 3, now - 1000, 1000, "SUCCESS", "USER", null);
        long b1 = store.insertBuild("rank-disabled", 1, now - 3000, 90000, "SUCCESS", "USER", null);
        store.insertBuild("rank-disabled", 2, now - 2000, 90000, "FAILURE", "USER", null);
        store.insertBuild("rank-disabled", 3, now - 1000, 90000, "SUCCESS", "USER", null);
        store.insertStage(b1, "deploy-disabled", 80000, "SUCCESS");

        long from = now - 10000;
        long to = now + 1000;

        config.setIgnoreDisabledPipelines(false);
        PipelineRanker everything = new PipelineRanker();
        assertEquals("rank-disabled", everything.slowestPipelines(from, to, 10).get(0).jobName);
        assertEquals("rank-disabled", everything.flakiestPipelines(from, to, 10).get(0).jobName);
        assertEquals("deploy-disabled", everything.slowestStages(from, to, 10).get(0).stageName);

        config.setIgnoreDisabledPipelines(true);
        PipelineRanker filtered = new PipelineRanker();
        List<PipelineRanker.RankedPipeline> slowest = filtered.slowestPipelines(from, to, 10);
        assertEquals(1, slowest.size());
        assertEquals("rank-active", slowest.get(0).jobName);
        List<PipelineRanker.RankedPipeline> flakiest = filtered.flakiestPipelines(from, to, 10);
        assertEquals(1, flakiest.size());
        assertEquals("rank-active", flakiest.get(0).jobName);
        assertTrue(filtered.slowestStages(from, to, 10).isEmpty());
        config.setIgnoreDisabledPipelines(false); // reset
    }
}
