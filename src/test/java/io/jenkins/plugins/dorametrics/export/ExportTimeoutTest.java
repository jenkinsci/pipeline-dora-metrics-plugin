package io.jenkins.plugins.dorametrics.export;

import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsMaintenanceTask;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import hudson.model.TaskListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * An export endpoint that accepts the connection and never answers must not hold the
 * plugin's maintenance hostage: the upload gives up, and retention runs regardless.
 */
public class ExportTimeoutTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private ServerSocket silent;
    private final List<Socket> held = new ArrayList<>();
    private ExecutorService pool;

    @Before
    public void setUp() throws Exception {
        System.setProperty(ExportHttp.TIMEOUT_PROPERTY, "2");
        silent = new ServerSocket(0);
        pool = Executors.newCachedThreadPool();
        pool.submit(() -> {
            while (!silent.isClosed()) {
                try {
                    held.add(silent.accept()); // read nothing, answer nothing
                } catch (IOException e) {
                    return null;
                }
            }
            return null;
        });
    }

    @After
    public void tearDown() throws Exception {
        System.clearProperty(ExportHttp.TIMEOUT_PROPERTY);
        silent.close();
        for (Socket s : held) {
            s.close();
        }
        pool.shutdownNow();
    }

    private String silentUrl() {
        return "http://127.0.0.1:" + silent.getLocalPort();
    }

    @Test
    public void anHttpUploadToASilentEndpointGivesUp() throws Exception {
        HttpExportConfig http = new HttpExportConfig();
        http.setUrl(silentUrl() + "/hook");
        Future<?> upload = pool.submit(() -> {
            http.upload("{}", "snapshot.json");
            return null;
        });
        try {
            upload.get(30, TimeUnit.SECONDS);
            fail("a silent endpoint cannot have accepted the upload");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        } catch (java.util.concurrent.TimeoutException hung) {
            fail("the upload is still waiting on an endpoint that will never answer");
        }
    }

    @Test
    public void anS3UploadToASilentEndpointGivesUp() throws Exception {
        S3ExportConfig s3 = new S3ExportConfig();
        s3.setEndpoint(silentUrl());
        s3.setBucket("dora");
        Future<?> upload = pool.submit(() -> {
            s3.upload("{}", "snapshot.json");
            return null;
        });
        try {
            upload.get(30, TimeUnit.SECONDS);
            fail("a silent endpoint cannot have accepted the upload");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        } catch (java.util.concurrent.TimeoutException hung) {
            fail("the upload is still waiting on an endpoint that will never answer");
        }
    }

    @Test
    public void retentionRunsEvenWhileTheExportEndpointHangs() throws Exception {
        System.setProperty(ExportHttp.TIMEOUT_PROPERTY, "600");
        MetricsStore.setInstance(null);
        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        store.insertBuild("old", 1, now - 400L * 86_400_000L, 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("new", 1, now - 60_000L, 1000, "SUCCESS", "SCM", "main");

        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        HttpExportConfig http = new HttpExportConfig();
        http.setUrl(silentUrl() + "/hook");
        config.setExportStorage(http);
        config.setExportEnabled(true);
        config.setRetentionDays(365);

        MetricsMaintenanceTask task = j.jenkins.getExtensionList(MetricsMaintenanceTask.class).get(0);
        Future<?> run = pool.submit(() -> {
            java.lang.reflect.Method execute = MetricsMaintenanceTask.class.getDeclaredMethod("execute", TaskListener.class);
            execute.setAccessible(true);
            execute.invoke(task, TaskListener.NULL);
            return null;
        });
        try {
            run.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException hung) {
            fail("maintenance is stuck behind the export");
        }
        assertEquals("the year-old build is past retention", 0, store.getBuilds("old", 0, now).size());
    }
}
