package io.jenkins.plugins.dorametrics.store;

import io.jenkins.plugins.dorametrics.store.MetricsStore.StageRecord;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class StagesByBuildTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void everyBuildGetsItsOwnStagesInOrderAcrossBatches() {
        MetricsStore.setInstance(null);
        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 1203; i++) { // more than two batches
            long id = store.insertBuild("app", i + 1, now - i * 1000L, 1000, "SUCCESS", "SCM", "main");
            ids.add(id);
            if (i % 2 == 0) {
                store.insertStage(id, "build-" + i, 1, "SUCCESS");
                store.insertStage(id, "deploy-" + i, 2, "FAILURE");
            }
        }

        Map<Long, List<StageRecord>> byBuild = store.getStagesByBuild(ids);

        assertEquals(602, byBuild.size());
        for (int i = 0; i < ids.size(); i++) {
            List<StageRecord> stages = byBuild.get(ids.get(i));
            if (i % 2 == 0) {
                assertEquals(store.getStages(ids.get(i)).size(), stages.size());
                assertEquals("build-" + i, stages.get(0).stageName);
                assertEquals("deploy-" + i, stages.get(1).stageName);
            } else {
                assertFalse(byBuild.containsKey(ids.get(i)));
            }
        }
    }
}
