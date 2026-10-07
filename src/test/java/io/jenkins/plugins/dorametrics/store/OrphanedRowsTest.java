package io.jenkins.plugins.dorametrics.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Re-recording a build used to leave its old stages and commits behind, pointing at a build
 * id that no longer existed, and nothing ever deleted them. These read the tables directly
 * rather than through getStages, because the symptom is invisible through the API: the
 * dashboard reads stages by the current build id and looks perfectly correct while the rows
 * pile up underneath.
 */
@WithJenkins
class OrphanedRowsTest {

    private JenkinsRule j;

    private MetricsStore store;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
    }

    private int count(String sql) throws Exception {
        String db = new java.io.File(j.jenkins.getRootDir(), "pipeline-dora-metrics/metrics.db").getAbsolutePath();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int orphanedStages() throws Exception {
        return count("SELECT COUNT(*) FROM stages WHERE build_id NOT IN (SELECT id FROM builds)");
    }

    private int orphanedCommits() throws Exception {
        return count("SELECT COUNT(*) FROM commits WHERE build_id NOT IN (SELECT id FROM builds)");
    }

    private List<MetricsStore.StageRow> threeStages() {
        return Arrays.asList(
                new MetricsStore.StageRow("Build", 1000, "SUCCESS"),
                new MetricsStore.StageRow("Test", 2000, "SUCCESS"),
                new MetricsStore.StageRow("Deploy", 500, "SUCCESS"));
    }

    @Test
    void reRecordingKeepsTheBuildIdAndReplacesItsRows() throws Exception {
        long now = System.currentTimeMillis();
        long first = store.recordBuild("job", 1, now, 5000, "SUCCESS", "SCM", "main",
                threeStages(),
                Collections.singletonList(new MetricsStore.CommitRow("abc", "dev", now - 60_000)));

        assertEquals(3, count("SELECT COUNT(*) FROM stages"), "three stages after the first record");

        long second = store.recordBuild("job", 1, now, 9999, "FAILURE", "SCM", "main",
                threeStages(),
                Collections.singletonList(new MetricsStore.CommitRow("abc", "dev", now - 60_000)));

        assertEquals(first, second, "the build row must keep its id");
        assertEquals(3, count("SELECT COUNT(*) FROM stages"), "its stages must be replaced, not appended");
        assertEquals(1, count("SELECT COUNT(*) FROM commits"), "and its commits too");
        assertEquals(0, orphanedStages(), "nothing orphaned");
        assertEquals(0, orphanedCommits(), "nothing orphaned");
        assertEquals(1, count("SELECT COUNT(*) FROM builds"), "one build row");
    }

    /** The upsert has to update the row, not silently keep the old values. */
    @Test
    void reRecordingUpdatesTheBuildRow() {
        long now = System.currentTimeMillis();
        store.recordBuild("upd", 1, now, 5000, "SUCCESS", "SCM", "main",
                Collections.emptyList(), Collections.emptyList());
        store.recordBuild("upd", 1, now, 9999, "FAILURE", "USER", "release",
                Collections.emptyList(), Collections.emptyList());

        List<MetricsStore.BuildRecord> builds =
                store.getBuilds("upd", now - 60_000, now + 60_000);
        assertEquals(1, builds.size());
        assertEquals("FAILURE", builds.get(0).result);
        assertEquals(9999, builds.get(0).durationMs);
        assertEquals("USER", builds.get(0).triggerType);
    }

    /** Ten passes is what an import re-run looks like. */
    @Test
    void repeatedRecordingDoesNotGrowTheTables() throws Exception {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            store.recordBuild("loop", 1, now, 5000, "SUCCESS", "SCM", "main",
                    threeStages(), Collections.emptyList());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM builds"), "still one build");
        assertEquals(3, count("SELECT COUNT(*) FROM stages"), "still three stages");
        assertEquals(0, orphanedStages());
    }

    /**
     * Databases written by an earlier version already carry orphans, and retention cannot
     * reach them: it deletes children by looking them up through their parent.
     */
    @Test
    void cleanupRemovesOrphansLeftByOlderVersions() throws Exception {
        long now = System.currentTimeMillis();
        long id = store.recordBuild("sweep", 1, now, 5000, "SUCCESS", "SCM", "main",
                threeStages(), Collections.emptyList());

        // Strand them the way INSERT OR REPLACE used to.
        String db = new java.io.File(j.jenkins.getRootDir(), "pipeline-dora-metrics/metrics.db").getAbsolutePath();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE stages SET build_id = " + (id + 9999) + " WHERE build_id = " + id);
            s.executeUpdate("INSERT INTO commits (build_id, commit_sha, author, timestamp) "
                    + "VALUES (" + (id + 9999) + ", 'dead', 'dev', " + (now - 1000) + ")");
        }
        assertEquals(3, orphanedStages(), "precondition: three orphaned stages");
        assertEquals(1, orphanedCommits(), "precondition: one orphaned commit");

        // A retention pass that deletes nothing else should still sweep them.
        store.cleanup(now - 86_400_000L);

        assertEquals(0, orphanedStages(), "orphaned stages should be gone");
        assertEquals(0, orphanedCommits(), "orphaned commits should be gone");
        assertEquals(1, store.getBuilds("sweep", now - 60_000, now + 60_000).size(), "and the build itself is untouched");
    }
}
