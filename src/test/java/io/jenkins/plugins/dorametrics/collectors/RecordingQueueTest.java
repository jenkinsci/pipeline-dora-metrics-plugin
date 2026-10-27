package io.jenkins.plugins.dorametrics.collectors;

import hudson.init.Terminator;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build never stays running because its metrics cannot be written: Jenkins counts a build as
 * running until the completion listeners return, and the next build of a job without concurrent
 * builds waits for that.
 */
@WithJenkins
class RecordingQueueTest {

    private JenkinsRule j;
    private Consumer<Run<?, ?>> original;
    private LogRecorder logs;
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> started = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
        original = RecordingQueue.recorder;
        logs = new LogRecorder().record(RecordingQueue.class, Level.WARNING).capture(20);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        RecordingQueue.recorder = original;
        System.clearProperty(RecordingQueue.WAIT_PROPERTY);
        System.clearProperty(RecordingQueue.CAPACITY_PROPERTY);
        logs.close();
    }

    /** A recorder that hangs like a write on stalled storage, until the test lets it go. */
    private void recorderThatHangs() {
        RecordingQueue.recorder = run -> {
            started.add(run.getFullDisplayName());
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    private void awaitStarted(int count) throws InterruptedException {
        for (int i = 0; i < 100 && started.size() < count; i++) {
            Thread.sleep(100);
        }
    }

    @Test
    void aStuckWriteDoesNotKeepTheBuildRunning() throws Exception {
        System.setProperty(RecordingQueue.WAIT_PROPERTY, "1");
        recorderThatHangs();
        FreeStyleProject app = j.createFreeStyleProject("app");

        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            FreeStyleBuild first = j.buildAndAssertSuccess(app);
            FreeStyleBuild second = j.buildAndAssertSuccess(app);
            assertFalse(first.isBuilding());
            assertFalse(second.isBuilding());
        });
        assertEquals(List.of("app #1"), started, "the second build waits in line behind the stuck write");

        release.countDown();
        awaitStarted(2);
        assertEquals(List.of("app #1", "app #2"), started, "both are written once the storage answers");
    }

    @Test
    void onceTheWriterIsBehindBuildsStopWaiting() throws Exception {
        System.setProperty(RecordingQueue.WAIT_PROPERTY, "8");
        recorderThatHangs();
        FreeStyleProject app = j.createFreeStyleProject("app");

        long t0 = System.nanoTime();
        j.buildAndAssertSuccess(app);
        long first = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        long t1 = System.nanoTime();
        j.buildAndAssertSuccess(app);
        long second = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);

        assertTrue(first >= 8000, "the first build waits for its write, " + first + "ms");
        assertTrue(second < 8000, "the second does not wait behind a write that is already late, " + second + "ms");
        assertTrue(logs.getMessages().stream().anyMatch(m -> m.contains("app #1") && m.contains("longer than 8s")),
                String.valueOf(logs.getMessages()));
    }

    @Test
    void oneSlowWriteCostsOneBuildTheWaitNotEveryBuild() throws Exception {
        // writes that each finish, just slowly, as when another process holds the database
        System.setProperty(RecordingQueue.WAIT_PROPERTY, "2");
        RecordingQueue.recorder = run -> {
            started.add(run.getFullDisplayName());
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        FreeStyleProject app = j.createFreeStyleProject("app");
        long t0 = System.nanoTime();
        j.buildAndAssertSuccess(app);
        long first = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        awaitStarted(1);
        Thread.sleep(1500);
        long t1 = System.nanoTime();
        j.buildAndAssertSuccess(app);
        long second = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);

        assertTrue(first >= 2000, "the first build waits out its write, " + first + "ms");
        assertTrue(second < 2000, "the next one does not wait again, " + second + "ms");
        assertEquals(1, logs.getMessages().stream().filter(m -> m.contains("took longer than")).count(),
                String.valueOf(logs.getMessages()));
    }

    @Test
    void aFullQueueSkipsTheBuildAndSaysSo() throws Exception {
        System.setProperty(RecordingQueue.WAIT_PROPERTY, "0");
        System.setProperty(RecordingQueue.CAPACITY_PROPERTY, "1");
        recorderThatHangs();
        FreeStyleProject app = j.createFreeStyleProject("app");

        j.buildAndAssertSuccess(app);
        awaitStarted(1);
        j.buildAndAssertSuccess(app);
        j.buildAndAssertSuccess(app);

        assertTrue(logs.getMessages().stream().anyMatch(m -> m.contains("app #3") && m.contains("not recorded")),
                String.valueOf(logs.getMessages()));
        release.countDown();
        awaitStarted(2);
        assertEquals(List.of("app #1", "app #2"), started);
    }

    @Test
    void aStuckWriteWarnsOnceNotForEveryBuild() throws Exception {
        System.setProperty(RecordingQueue.WAIT_PROPERTY, "1");
        recorderThatHangs();
        FreeStyleProject app = j.createFreeStyleProject("app");
        for (int i = 0; i < 4; i++) {
            j.buildAndAssertSuccess(app);
        }
        long warnings = logs.getMessages().stream().filter(m -> m.contains("took longer than")).count();
        assertEquals(1, warnings, String.valueOf(logs.getMessages()));
    }

    @Test
    void excludedJobsAreNotQueued() throws Exception {
        DoraGlobalConfiguration.get().setExcludedJobPattern("app");
        recorderThatHangs();
        FreeStyleProject app = j.createFreeStyleProject("app");
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> j.buildAndAssertSuccess(app));
        assertEquals(List.of(), started);
    }

    @Test
    void stoppingDoesNotHangOnAStuckWrite() throws Exception {
        System.setProperty(RecordingQueue.WAIT_PROPERTY, "0");
        recorderThatHangs();
        FreeStyleProject app = j.createFreeStyleProject("app");
        j.buildAndAssertSuccess(app);
        awaitStarted(1);
        // the drain gives up after 10 s and the store does not wait on the stuck write
        assertTimeoutPreemptively(Duration.ofSeconds(30), RecordingQueue::shutdown);
        RecordingQueue.start();
    }

    @Test
    void jenkinsCanCallTheShutdownHook() throws Exception {
        // Jenkins calls a @Terminator reflectively and fails with IllegalAccessException on a non-public class
        Method shutdown = RecordingQueue.class.getMethod("shutdown");
        assertTrue(shutdown.isAnnotationPresent(Terminator.class));
        assertTrue(Modifier.isPublic(RecordingQueue.class.getModifiers()));
        assertTrue(Modifier.isStatic(shutdown.getModifiers()));
        Method start = RecordingQueue.class.getMethod("start");
        assertTrue(start.isAnnotationPresent(hudson.init.Initializer.class));
        assertTrue(Modifier.isStatic(start.getModifiers()));
    }

    @Test
    void shuttingDownWritesWhatIsQueuedAndClosesTheStore() throws Exception {
        recorderThatHangs();
        System.setProperty(RecordingQueue.WAIT_PROPERTY, "0");
        FreeStyleProject app = j.createFreeStyleProject("app");
        j.buildAndAssertSuccess(app);
        j.buildAndAssertSuccess(app);
        awaitStarted(1);
        release.countDown();
        RecordingQueue.shutdown();
        RecordingQueue.start();
        assertEquals(List.of("app #1", "app #2"), started, "both queued builds are written before it stops");
    }

    @Test
    void onHealthyStorageTheBuildIsStoredWhenItFinishes() throws Exception {
        FreeStyleProject app = j.createFreeStyleProject("app");
        j.buildAndAssertSuccess(app);
        assertEquals(1, MetricsStore.getInstance().getBuilds("app", 0, Long.MAX_VALUE).size());
    }
}
