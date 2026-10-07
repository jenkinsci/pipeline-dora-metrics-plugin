package io.jenkins.plugins.dorametrics.collectors;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FakeChangeLogSCM;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A change log entry has no commit id unless its SCM gives it one, and commit_sha is NOT NULL.
 * Now that a build and its children go in as one transaction, such an entry would fail the
 * insert and roll the build back with it, so the build would never be stored at all.
 */
@WithJenkins
class CommitsWithoutIdTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
    }

    private FreeStyleBuild buildWithTwoIdlessChanges(String jobName) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject(jobName);
        FakeChangeLogSCM scm = new FakeChangeLogSCM();
        scm.addChange().withAuthor("dev").withMsg("first");
        scm.addChange().withAuthor("dev").withMsg("second");
        job.setScm(scm);
        return j.buildAndAssertSuccess(job);
    }

    @Test
    void theBuildIsStoredWhenNoChangeHasACommitId() throws Exception {
        FreeStyleBuild run = buildWithTwoIdlessChanges("no-commit-ids");

        int entries = 0;
        for (hudson.scm.ChangeLogSet.Entry entry : run.getChangeSets().get(0)) {
            assertNull(entry.getCommitId(), "core gives an entry no id of its own");
            entries++;
        }
        assertEquals(2, entries, "the change log had two entries");
        assertEquals(1,
                count("SELECT COUNT(*) FROM builds WHERE job_name = 'no-commit-ids'"), "the build must be stored even though its changes carry no id");
    }

    @Test
    void entriesWithoutACommitIdAreLeftOut() throws Exception {
        buildWithTwoIdlessChanges("skipped-commits");

        assertEquals(1, count("SELECT COUNT(*) FROM builds WHERE job_name = 'skipped-commits'"),
                "the build is there");
        assertEquals(0, count("SELECT COUNT(*) FROM commits"), "an entry with no id is not a commit row");
    }

    @Test
    void reRecordingSuchABuildStillKeepsOneRow() throws Exception {
        FreeStyleBuild run = buildWithTwoIdlessChanges("re-recorded");
        BuildRecorder.record(run);

        assertEquals(1,
                count("SELECT COUNT(*) FROM builds WHERE job_name = 're-recorded'"), "re-recording is still an upsert on the same build");
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
}
