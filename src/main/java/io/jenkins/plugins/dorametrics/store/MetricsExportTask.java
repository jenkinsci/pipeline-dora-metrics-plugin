package io.jenkins.plugins.dorametrics.store;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks every hour whether the export interval has passed and, if so, exports a snapshot.
 * Kept apart from {@link MetricsMaintenanceTask} so that retention does not wait on an
 * export endpoint.
 */
@Extension
public class MetricsExportTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(MetricsExportTask.class.getName());
    private static final long HOUR_MS = 3600_000;
    private long lastExportTime = 0;

    public MetricsExportTask() {
        super("DORA Metrics Export");
    }

    @Override
    public long getRecurrencePeriod() {
        return HOUR_MS;
    }

    @Override
    protected void execute(TaskListener listener) {
        try {
            DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
            if (config == null || !config.isExportEnabled()) return;

            long intervalMs = (long) Math.max(1, config.getExportIntervalHours()) * HOUR_MS;
            if (System.currentTimeMillis() - lastExportTime >= intervalMs) {
                MetricsExporter.exportDailySnapshot();
                lastExportTime = System.currentTimeMillis();
                LOGGER.info("DORA metrics export completed");
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "DORA metrics export failed", e);
        }
    }
}
