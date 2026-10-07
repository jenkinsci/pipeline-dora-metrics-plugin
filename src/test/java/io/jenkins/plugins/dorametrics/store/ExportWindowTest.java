package io.jenkins.plugins.dorametrics.store;

import hudson.model.Descriptor;
import hudson.model.TaskListener;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.export.ExportStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every export covers everything that finished since the last export that went through, a
 * failed one is retried with nothing lost, and when the last export happened survives a restart.
 */
@WithJenkins
class ExportWindowTest {

    private static final long HOUR = 3600_000L;

    private JenkinsRule j;

    private MetricsStore store;
    private DoraGlobalConfiguration config;
    private Recording storage;
    private long now;

    public static class Recording extends ExportStorageConfig {
        final List<String> names = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        boolean failing;

        @Override public String getStorageType() { return "Recording"; }
        @Override public String getCredentialsId() { return null; }

        @Override
        public void upload(String data, String fileName) throws IOException {
            if (failing) throw new IOException("endpoint is down");
            names.add(fileName);
            bodies.add(data);
        }

        @TestExtension
        public static class DescriptorImpl extends Descriptor<ExportStorageConfig> {
        }
    }

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
        config = DoraGlobalConfiguration.get();
        storage = new Recording();
        config.setExportStorage(storage);
        config.setExportEnabled(true);
        config.setExportIntervalHours(24);
        now = System.currentTimeMillis();
    }

    private void runTask() throws Exception {
        runTask(new MetricsExportTask());
    }

    private static void runTask(MetricsExportTask task) throws Exception {
        java.lang.reflect.Method execute = MetricsExportTask.class.getDeclaredMethod("execute", TaskListener.class);
        execute.setAccessible(true);
        execute.invoke(task, TaskListener.NULL);
    }

    @Test
    void aFailedExportIsRetriedWithItsBuilds() throws Exception {
        store.insertBuild("app", 1, now - 2 * HOUR, 1000, "SUCCESS", "SCM", "main");
        MetricsExportTask task = new MetricsExportTask();

        storage.failing = true;
        runTask(task);
        assertEquals(0, storage.bodies.size());

        storage.failing = false;
        runTask(task);
        assertEquals(1, storage.bodies.size(), "the next run tries again");
        assertTrue(storage.bodies.get(0).contains("\"job\": \"app\""));
    }

    @Test
    void anExportCoversEverythingSinceTheLastOne() throws Exception {
        config.setExportIntervalHours(48);
        MetricsExportTask.recordSuccess(now - 48 * HOUR);
        store.insertBuild("app", 1, now - 30 * HOUR, 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("app", 2, now - HOUR, 1000, "SUCCESS", "SCM", "main");

        runTask();

        assertEquals(1, storage.bodies.size());
        assertTrue(storage.bodies.get(0).contains("\"build\": 1"), "finished 30 hours ago, after the last export");
        assertTrue(storage.bodies.get(0).contains("\"build\": 2"));
    }

    @Test
    void aBuildThatFinishedAfterTheLastExportIsIncludedEvenIfItStartedBefore() throws Exception {
        MetricsExportTask.recordSuccess(now - 25 * HOUR);
        store.insertBuild("long", 1, now - 26 * HOUR, 2 * HOUR, "SUCCESS", "SCM", "main");

        runTask();

        assertEquals(1, storage.bodies.size());
        assertTrue(storage.bodies.get(0).contains("\"job\": \"long\""));
    }

    @Test
    void twoExportsOnOneDayDoNotOverwriteEachOther() throws Exception {
        // an hour apart, the shortest interval there is, on the same UTC day
        long first = java.time.Instant.parse("2026-09-28T01:00:00Z").toEpochMilli();
        long second = first + HOUR;
        store.insertBuild("app", 1, first - HOUR / 2, 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("app", 2, second - HOUR / 2, 1000, "SUCCESS", "SCM", "main");

        MetricsExporter.exportFinishedBetween(first - HOUR, first);
        MetricsExporter.exportFinishedBetween(first, second);

        assertEquals(2, storage.names.size());
        assertNotEquals(storage.names.get(0), storage.names.get(1));
        assertTrue(storage.names.get(0).startsWith("dora-metrics/2026-09-28/"));
    }

    @Test
    void theLastExportSurvivesARestart() throws Exception {
        store.insertBuild("app", 1, now - HOUR, 1000, "SUCCESS", "SCM", "main");
        runTask(new MetricsExportTask());
        assertEquals(1, storage.bodies.size());

        runTask(new MetricsExportTask()); // a fresh task, as after a restart
        assertEquals(1, storage.bodies.size(), "the interval has not passed since the last export");
    }
}
