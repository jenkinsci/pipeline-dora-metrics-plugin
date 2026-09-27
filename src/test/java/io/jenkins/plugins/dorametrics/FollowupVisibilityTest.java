package io.jenkins.plugins.dorametrics;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.Page;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * History that is still stored under a job name after that job is gone must not
 * be shown to whoever can read a new job created under the same name.
 */
public class FollowupVisibilityTest {

    private static final String OLD_BRANCH = "release-old-secret";

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public LoggerRule logs = new LoggerRule().record(MetricsStore.class, Level.FINE).capture(100);

    private MetricsStore store;

    @Before
    public void setUp() {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
    }

    private void seedOldHistory(String jobName) {
        long now = System.currentTimeMillis();
        for (int i = 1; i <= 2; i++) {
            long id = store.insertBuild(jobName, i, now - i * 60_000L, 5000, "SUCCESS", "USER", OLD_BRANCH);
            store.insertStage(id, "old-secret-stage", 1000, "SUCCESS");
        }
    }

    private void onlyAliceReads(Item... items) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("alice")
                .grant(Item.READ).onItems(items).to("alice"));
    }

    private String get(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login(user);
        wc.setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setJavaScriptEnabled(false);
        Page p = wc.goTo(path, null);
        return p.getWebResponse().getContentAsString();
    }

    private int status(String user, String path) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient().login(user);
        wc.setThrowExceptionOnFailingStatusCode(false);
        return wc.goTo(path, null).getWebResponse().getStatusCode();
    }

    private static int trendBuilds(String json) {
        JSONObject o = JSONObject.fromObject(json);
        if (!o.has("trends")) {
            return 0;
        }
        JSONArray points = o.getJSONArray("trends");
        int total = 0;
        for (int i = 0; i < points.size(); i++) {
            total += points.getJSONObject(i).getInt("total_builds");
        }
        return total;
    }

    private void assertAliceSeesNoOldHistory(String jobName) throws Exception {
        assertEquals("trends?job= must not show the old job's builds",
                0, trendBuilds(get("alice", "dora-api/trends?job=" + jobName)));
        assertFalse("export must not contain the old job's branch",
                get("alice", "dora-api/export?format=csv").contains(OLD_BRANCH));
        assertNoListShowsIt(jobName);
    }

    /** The dashboard's stage tables and the rankings, which read the same rows another way. */
    private void assertNoListShowsIt(String recordedName) throws Exception {
        assertFalse("the stage tables must not show the old job's stages",
                get("alice", "dora-metrics/").contains("old-secret-stage"));
        assertFalse("the rankings must not list the old job's builds",
                get("alice", "dora-api/pipelines?days=30").contains("\"job\":\"" + recordedName + "\""));
    }

    /** Rows left by a job deleted before the detach existed (or while the plugin was off). */
    @Test
    public void leftoverRowsOfAGoneJobAreNotHandedToANewJobOfTheSameName() throws Exception {
        seedOldHistory("team/payments");
        MockFolder team = j.createFolder("team");
        FreeStyleProject fresh = team.createProject(FreeStyleProject.class, "payments");
        onlyAliceReads(team, fresh);
        // control: alice really can read the new job, so a zero below is not a 404
        assertEquals(200, status("alice", "dora-api/trends?job=team/payments"));
        assertAliceSeesNoOldHistory("team/payments");
        assertFalse("nor the job's own DORA tab",
                get("alice", "job/team/job/payments/dora-metrics/").contains("old-secret-stage"));
    }

    /** The detach on delete fails when the database is busy for longer than busy_timeout. */
    @Test
    public void aDetachThatFailsOnABusyDatabaseDoesNotLeaveTheHistoryAttached() throws Exception {
        FreeStyleProject old = j.createFreeStyleProject("busy");
        seedOldHistory("busy");
        File db = new File(j.jenkins.getRootDir(), "pipeline-dora-metrics/metrics.db");
        try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             Statement st = lock.createStatement()) {
            st.execute("BEGIN IMMEDIATE");
            old.delete();
            st.execute("ROLLBACK");
        }
        FreeStyleProject fresh = j.createFreeStyleProject("busy");
        onlyAliceReads(fresh);
        assertAliceSeesNoOldHistory("busy");
    }

    /** Renaming onto a name that still has rows makes the UPDATE hit UNIQUE(job_name, build_number). */
    @Test
    public void renamingOntoANameWithLeftoverRowsDoesNotMixTheHistories() throws Exception {
        seedOldHistory("target");
        FreeStyleProject mover = j.createFreeStyleProject("mover");
        long now = System.currentTimeMillis();
        store.insertBuild("mover", 1, now, 1000, "SUCCESS", "USER", "main");
        mover.renameTo("target");
        onlyAliceReads(mover);
        assertEquals("alice sees the renamed job's own build and nothing else", 1,
                trendBuilds(get("alice", "dora-api/trends?job=target")));
        assertFalse("the old rows must not come along", get("alice", "dora-api/export?format=csv").contains(OLD_BRANCH));
        assertEquals("the renamed job keeps its own build", 1,
                trendBuilds(get("admin", "dora-api/trends?job=target")));
        assertEquals("nothing left under the old name", 0, rows("mover"));
    }

    /** Jenkins resolves item names case-insensitively; stored rows compare exactly. */
    @Test
    public void rowsStoredUnderADifferentCaseAreNotVisibleThroughAReadableJob() throws Exception {
        seedOldHistory("Secret");
        FreeStyleProject readable = j.createFreeStyleProject("secret");
        onlyAliceReads(readable);
        assertEquals("control: alice can read the job she has", 200, status("alice", "dora-api/trends?job=secret"));
        assertEquals("a differently cased name looks like a missing job", 404, status("alice", "dora-api/trends?job=Secret"));
        assertEquals(status("alice", "dora-api/trends?job=no-such-job"), status("alice", "dora-api/trends?job=Secret"));
        assertFalse(get("alice", "dora-api/export?format=csv").contains(OLD_BRANCH));
        assertEquals(0, trendBuilds(get("alice", "dora-api/trends")));
        assertNoListShowsIt("Secret");
    }

    /** The detach on delete runs into a lock, fails once, and succeeds on a retry once the lock is gone. */
    @Test
    public void aDetachHitByABriefLockIsRetried() throws Exception {
        FreeStyleProject old = j.createFreeStyleProject("brief");
        seedOldHistory("brief");
        File db = new File(j.jenkins.getRootDir(), "pipeline-dora-metrics/metrics.db");
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (Connection lock = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
                 Statement st = lock.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
                locked.countDown();
                long until = System.currentTimeMillis() + 30_000;
                while (logs.getMessages().stream().noneMatch(m -> m.startsWith("Database busy, trying again (1/"))
                        && System.currentTimeMillis() < until) {
                    Thread.sleep(20);
                }
                st.execute("ROLLBACK");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        holder.start();
        locked.await();
        old.delete();
        holder.join();
        assertEquals("the first attempt hit the lock", true,
                logs.getMessages().stream().anyMatch(m -> m.startsWith("Database busy, trying again (1/")));
        assertEquals("detached at delete time, before any new job exists", 0, rows("brief"));
    }

    /** A copy is created through onCopied, which Jenkins routes to onCreated. */
    @Test
    public void aCopyDoesNotInheritLeftoverRowsUnderItsName() throws Exception {
        seedOldHistory("copied");
        FreeStyleProject src = j.createFreeStyleProject("template");
        FreeStyleProject copy = (FreeStyleProject) j.jenkins.copy((hudson.model.TopLevelItem) src, "copied");
        onlyAliceReads(copy);
        assertEquals(200, status("alice", "dora-api/trends?job=copied"));
        assertAliceSeesNoOldHistory("copied");
    }

    /** A run finishing after its job is gone and its name reused must not be recorded under that name. */
    @Test
    public void aRunOfADeletedJobIsNotRecordedUnderTheNameOfANewJob() throws Exception {
        FreeStyleProject old = j.createFreeStyleProject("reused");
        hudson.model.FreeStyleBuild oldRun = j.buildAndAssertSuccess(old);
        old.delete();
        j.createFreeStyleProject("reused");
        new io.jenkins.plugins.dorametrics.collectors.BuildDataCollector()
                .onCompleted(oldRun, hudson.model.TaskListener.NULL);
        assertEquals("nothing may be written under the new job's name", 0, rows("reused"));
    }

    /** Deleting a running pipeline aborts it; its record must end up detached, not under the bare name. */
    @Test
    public void aBuildAbortedByDeletingItsJobEndsUpDetached() throws Exception {
        WorkflowJob p = j.createProject(WorkflowJob.class, "late");
        p.setDefinition(new CpsFlowDefinition("stage('s') { sleep 3 }", true));
        WorkflowRun run = p.scheduleBuild2(0).waitForStart();
        Thread.sleep(1000);
        p.delete();
        j.waitForCompletion(run);
        assertEquals(0, rows("late"));
    }

    private long rows(String exactName) throws Exception {
        File db = new File(j.jenkins.getRootDir(), "pipeline-dora-metrics/metrics.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM builds WHERE job_name = ?")) {
            ps.setString(1, exactName);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
