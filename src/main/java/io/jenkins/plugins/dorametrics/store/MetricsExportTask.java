package io.jenkins.plugins.dorametrics.store;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.util.AtomicFileWriter;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks every hour whether the export interval has passed since the last export that went
 * through and, if so, exports every build that finished since then. A failed export leaves
 * that moment where it was, so the next hour tries the same window again and nothing is lost.
 * The moment is kept on disk, so a restart neither exports again early nor skips a window.
 *
 * <p>Kept apart from {@link MetricsMaintenanceTask} so that retention does not wait on an
 * export endpoint.
 */
@Extension
public class MetricsExportTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(MetricsExportTask.class.getName());
    private static final long HOUR_MS = 3600_000;
    private static final long DAY_MS = 24 * HOUR_MS;

    public MetricsExportTask() {
        super("DORA Metrics Export");
    }

    @Override
    public long getRecurrencePeriod() {
        return HOUR_MS;
    }

    @Override
    protected void execute(TaskListener listener) {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        if (config == null || !config.isExportEnabled()) return;

        long now = System.currentTimeMillis();
        long intervalMs = (long) Math.max(1, config.getExportIntervalHours()) * HOUR_MS;
        long last = lastSuccess();
        if (now - last < intervalMs) return;

        // The first export covers one interval, and at least a day as it always has. After
        // that, everything since the last one. Nothing older than retention is left to send.
        long after = last > 0 ? last : now - Math.max(intervalMs, DAY_MS);
        after = Math.max(after, now - (long) Math.max(1, config.getRetentionDays()) * DAY_MS);
        try {
            MetricsExporter.exportFinishedBetween(after, now);
            recordSuccess(now);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "DORA metrics export failed, it will be tried again within the hour", e);
        }
    }

    /** When the last export went through, or 0 if none has. */
    static long lastSuccess() {
        File file = stateFile();
        if (!file.isFile()) return 0;
        try {
            return Long.parseLong(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            LOGGER.log(Level.WARNING, "Could not read " + file + ", exporting from scratch", e);
            return 0;
        }
    }

    static void recordSuccess(long whenMs) throws IOException {
        File file = stateFile();
        File dir = file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir);
        }
        try (AtomicFileWriter writer = new AtomicFileWriter(file.toPath(), StandardCharsets.UTF_8)) {
            try {
                writer.write(Long.toString(whenMs));
                writer.commit();
            } finally {
                writer.abort();
            }
        }
    }

    private static File stateFile() {
        return new File(new File(Jenkins.get().getRootDir(), "pipeline-dora-metrics"), "last-export");
    }
}
