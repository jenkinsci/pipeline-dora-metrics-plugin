package io.jenkins.plugins.dorametrics.store;

import hudson.model.TaskListener;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A retention of zero or less never reaches the cleanup, whichever way it was set, so the next
 * cleanup cannot delete every stored build.
 */
@WithJenkins
class RetentionFloorTest {

    private static final long HOUR = 3_600_000L;

    private MetricsStore store;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        MetricsStore.setInstance(null);
        store = MetricsStore.getInstance();
    }

    @Test
    void zeroFromConfigurationAsCodeKeepsTheLastDay(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("jenkins.yaml");
        Files.writeString(yaml, "unclassified:\n  doraGlobalConfiguration:\n    retentionDays: 0\n", StandardCharsets.UTF_8);
        ConfigurationAsCode.get().configure(yaml.toUri().toString());
        assertEquals(1, DoraGlobalConfiguration.get().getRetentionDays());

        long now = System.currentTimeMillis();
        store.insertBuild("app", 1, now - 3 * 24 * HOUR, 1000, "SUCCESS", "SCM", "main");
        store.insertBuild("app", 2, now - HOUR, 1000, "SUCCESS", "SCM", "main");
        new MetricsMaintenanceTask().execute(TaskListener.NULL);

        assertEquals(1, store.getAllBuilds(0, Long.MAX_VALUE).size(), "only the build older than a day goes");
        assertEquals(2, store.getAllBuilds(0, Long.MAX_VALUE).get(0).buildNumber);
    }

    @Test
    void aNegativeValueIsOneDay() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        config.setRetentionDays(-5);
        assertEquals(1, config.getRetentionDays());
    }

    @Test
    void zeroThatBypassedTheSetterIsOneDay() throws Exception {
        // what a hand-edited config file leaves behind
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        Field field = DoraGlobalConfiguration.class.getDeclaredField("retentionDays");
        field.setAccessible(true);
        field.setInt(config, 0);
        assertEquals(1, config.getRetentionDays());
    }
}
