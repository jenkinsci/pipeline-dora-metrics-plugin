package io.jenkins.plugins.dorametrics.store;

import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.List;

import static org.junit.Assert.*;

public class MetricsStoreTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private MetricsStore store;

    @Before
    public void setUp() {
        // Reset singleton so it re-initializes with current JenkinsRule's JENKINS_HOME
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
    }

    @Test
    public void insertAndQueryBuild() {
        long now = System.currentTimeMillis();
        long id = store.insertBuild("test-job", 1, now, 5000, "SUCCESS", "USER", "main");
        assertTrue("Build ID should be positive", id > 0);

        List<MetricsStore.BuildRecord> builds = store.getBuilds("test-job", now - 1000, now + 1000);
        assertEquals(1, builds.size());
        assertEquals("test-job", builds.get(0).jobName);
        assertEquals(1, builds.get(0).buildNumber);
        assertEquals(5000, builds.get(0).durationMs);
        assertEquals("SUCCESS", builds.get(0).result);
        assertEquals("main", builds.get(0).branch);
    }

    @Test
    public void insertDuplicateBuildMerges() {
        long now = System.currentTimeMillis();
        store.insertBuild("test-job", 1, now, 5000, "SUCCESS", "USER", "main");
        store.insertBuild("test-job", 1, now, 6000, "FAILURE", "SCM", "develop");

        List<MetricsStore.BuildRecord> builds = store.getBuilds("test-job", now - 1000, now + 1000);
        assertEquals("Duplicate should merge", 1, builds.size());
    }

    @Test
    public void insertAndQueryStages() {
        long now = System.currentTimeMillis();
        long buildId = store.insertBuild("stage-job", 1, now, 10000, "SUCCESS", "USER", null);
        store.insertStage(buildId, "Build", 3000, "SUCCESS");
        store.insertStage(buildId, "Test", 5000, "FAILURE");
        store.insertStage(buildId, "Deploy", 2000, "SUCCESS");

        List<MetricsStore.StageRecord> stages = store.getStages(buildId);
        assertEquals(3, stages.size());
        assertEquals("Build", stages.get(0).stageName);
        assertEquals(3000, stages.get(0).durationMs);
        assertEquals("Test", stages.get(1).stageName);
        assertEquals("FAILURE", stages.get(1).result);
    }

    @Test
    public void insertAndQueryCommits() {
        long now = System.currentTimeMillis();
        long commitTime = now - 1800000; // 30 min before
        long buildId = store.insertBuild("commit-job", 1, now, 5000, "SUCCESS", "SCM", "main");
        store.insertCommit(buildId, "abc123", "dev@test.com", commitTime);
        store.insertCommit(buildId, "def456", "dev@test.com", commitTime - 600000);

        long earliest = store.getEarliestCommitTimestamp(buildId);
        assertEquals(commitTime - 600000, earliest);
    }

    @Test
    public void countBuilds() {
        long now = System.currentTimeMillis();
        store.insertBuild("count-job-a", 1, now, 5000, "SUCCESS", "USER", null);
        store.insertBuild("count-job-a", 2, now, 5000, "FAILURE", "USER", null);
        store.insertBuild("count-job-b", 1, now, 5000, "SUCCESS", "USER", null);

        assertEquals(3, store.countTotalBuilds(now - 1000, now + 1000, ".*"));
        assertEquals(2, store.countSuccessfulBuilds(now - 1000, now + 1000, ".*"));
        assertEquals(1, store.countFailedBuilds(now - 1000, now + 1000, ".*"));
    }

    @Test
    public void countBuildsWithPattern() {
        long now = System.currentTimeMillis();
        store.insertBuild("prod/api", 1, now, 5000, "SUCCESS", "USER", null);
        store.insertBuild("prod/web", 1, now, 5000, "SUCCESS", "USER", null);
        store.insertBuild("dev/test", 1, now, 5000, "FAILURE", "USER", null);

        assertEquals(2, store.countTotalBuilds(now - 1000, now + 1000, "prod/.*"));
        assertEquals(2, store.countSuccessfulBuilds(now - 1000, now + 1000, "prod/.*"));
        assertEquals(0, store.countFailedBuilds(now - 1000, now + 1000, "prod/.*"));
    }

    @Test
    public void countBuildsWithExactPattern() {
        long now = System.currentTimeMillis();
        store.insertBuild("api-gateway", 1, now, 5000, "SUCCESS", "USER", null);
        store.insertBuild("api-gateway", 2, now, 5000, "FAILURE", "USER", null);
        store.insertBuild("other-job", 1, now, 5000, "SUCCESS", "USER", null);

        // Pattern.quote produces ^\Qapi-gateway\E$
        String pattern = "^" + java.util.regex.Pattern.quote("api-gateway") + "$";
        assertEquals(2, store.countTotalBuilds(now - 1000, now + 1000, pattern));
        assertEquals(1, store.countSuccessfulBuilds(now - 1000, now + 1000, pattern));
        assertEquals(1, store.countFailedBuilds(now - 1000, now + 1000, pattern));
    }

    @Test
    public void getJobStats() {
        long now = System.currentTimeMillis();
        store.insertBuild("slow-job", 1, now, 30000, "SUCCESS", "USER", null);
        store.insertBuild("slow-job", 2, now, 20000, "SUCCESS", "USER", null);
        store.insertBuild("fast-job", 1, now, 2000, "SUCCESS", "USER", null);

        List<MetricsStore.JobStats> stats = store.getJobStats(now - 1000, now + 1000, 10, "avg_dur DESC");
        assertFalse(stats.isEmpty());
        assertEquals("slow-job", stats.get(0).jobName);
        assertEquals(25000.0, stats.get(0).avgDurationMs, 1.0);
        assertEquals(2, stats.get(0).buildCount);
    }

    @Test
    public void getStageStats() {
        long now = System.currentTimeMillis();
        long b1 = store.insertBuild("stage-stats-job", 1, now, 10000, "SUCCESS", "USER", null);
        long b2 = store.insertBuild("stage-stats-job", 2, now, 10000, "SUCCESS", "USER", null);
        store.insertStage(b1, "Build", 3000, "SUCCESS");
        store.insertStage(b1, "Test", 5000, "FAILURE");
        store.insertStage(b2, "Build", 4000, "SUCCESS");
        store.insertStage(b2, "Test", 6000, "SUCCESS");

        List<MetricsStore.StageStats> stats = store.getStageStats(now - 1000, now + 1000, 10, "avg_dur DESC");
        assertEquals(2, stats.size());
        assertEquals("Test", stats.get(0).stageName);
        assertEquals(5500.0, stats.get(0).avgDurationMs, 1.0);
        assertEquals(1, stats.get(0).failureCount);
    }

    @Test
    public void getJobStatsRejectsInvalidOrderBy() {
        long now = System.currentTimeMillis();
        List<MetricsStore.JobStats> stats = store.getJobStats(now - 1000, now + 1000, 10, "DROP TABLE builds; --");
        assertTrue("Invalid orderBy should return empty list", stats.isEmpty());
    }

    @Test
    public void getAllJobNames() {
        long now = System.currentTimeMillis();
        store.insertBuild("alpha-job", 1, now, 5000, "SUCCESS", "USER", null);
        store.insertBuild("beta-job", 1, now, 5000, "SUCCESS", "USER", null);

        List<String> names = store.getAllJobNames();
        assertTrue(names.contains("alpha-job"));
        assertTrue(names.contains("beta-job"));
    }

    @Test
    public void cleanup() {
        long now = System.currentTimeMillis();
        long oldTime = now - 400 * 86400000L; // 400 days ago
        store.insertBuild("old-job", 1, oldTime, 5000, "SUCCESS", "USER", null);
        store.insertBuild("new-job", 1, now, 5000, "SUCCESS", "USER", null);

        store.cleanup(now - 365 * 86400000L); // retain last 365 days

        List<MetricsStore.BuildRecord> all = store.getAllBuilds(0, now + 1000);
        boolean hasOld = all.stream().anyMatch(b -> b.jobName.equals("old-job"));
        boolean hasNew = all.stream().anyMatch(b -> b.jobName.equals("new-job"));
        assertFalse("Old build should be cleaned", hasOld);
        assertTrue("New build should remain", hasNew);
    }

    @Test
    public void renameJob() {
        long now = System.currentTimeMillis();
        store.insertBuild("old-name", 1, now, 5000, "SUCCESS", "USER", "main");
        store.insertBuild("old-name", 2, now, 6000, "FAILURE", "USER", "main");

        store.renameJob("old-name", "new-name");

        List<MetricsStore.BuildRecord> oldBuilds = store.getBuilds("old-name", now - 1000, now + 1000);
        List<MetricsStore.BuildRecord> newBuilds = store.getBuilds("new-name", now - 1000, now + 1000);
        assertTrue("Old name should have no builds", oldBuilds.isEmpty());
        assertEquals("New name should have 2 builds", 2, newBuilds.size());
    }

    @Test
    public void avgLeadTimeMs() {
        long now = System.currentTimeMillis();
        long commitTime = now - 1800000; // 30 min before
        long buildId = store.insertBuild("lead-job", 1, now, 5000, "SUCCESS", "SCM", "main");
        store.insertCommit(buildId, "abc123", "dev@test.com", commitTime);

        double avgLt = store.avgLeadTimeMs(now - 1000, now + 1000, ".*");
        assertTrue("Lead time should be ~30 min", avgLt > 1700000 && avgLt < 1900000);
    }

    @Test
    public void queriesLeaveExcludedJobsOut() {
        long now = System.currentTimeMillis();
        long a = store.insertBuild("keep", 1, now, 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("keep", 2, now, 1000, "FAILURE", "SCM", "main");
        long b = store.insertBuild("drop", 1, now, 5000, "FAILURE", "SCM", "main");
        store.insertBuild("drop-too", 1, now, 5000, "SUCCESS", "SCM", "main");
        store.insertStage(a, "build", 500, "SUCCESS");
        store.insertStage(b, "build", 4000, "FAILURE");
        long from = now - 1000;
        long to = now + 1000;
        Set<String> excluded = Set.of("drop", "drop-too");

        assertEquals(4, store.getAllBuilds(from, to).size());
        assertEquals(2, store.getAllBuilds(from, to, excluded).size());
        assertEquals(4, store.countTotalBuilds(from, to, ".*"));
        assertEquals(2, store.countTotalBuilds(from, to, ".*", excluded));
        assertEquals(1, store.countFailedBuilds(from, to, ".*", excluded));
        assertEquals(1, store.countSuccessfulBuilds(from, to, ".*", excluded));
        // Pattern and exclusion combine.
        assertEquals(1, store.countTotalBuilds(from, to, "drop.*", java.util.Collections.emptySet()) - 1);
        assertEquals(0, store.countTotalBuilds(from, to, "drop.*", excluded));

        List<MetricsStore.JobStats> stats = store.getJobStats(from, to, 10, "avg_dur DESC", excluded);
        assertEquals(1, stats.size());
        assertEquals("keep", stats.get(0).jobName);

        List<MetricsStore.StageStats> stages = store.getStageStats(from, to, 10, "avg_dur DESC", excluded);
        assertEquals(1, stages.size());
        assertEquals(500, stages.get(0).avgDurationMs, 0.001);

        // An empty set changes nothing.
        assertEquals(4, store.getAllBuilds(from, to, java.util.Collections.emptySet()).size());
    }
}
