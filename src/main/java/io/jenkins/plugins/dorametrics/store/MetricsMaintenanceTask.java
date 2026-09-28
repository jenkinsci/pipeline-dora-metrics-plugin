package io.jenkins.plugins.dorametrics.store;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic task that runs every hour and removes data older than retentionDays.
 * Exports run in {@link MetricsExportTask}, so an export endpoint that is slow or down
 * never holds this one up.
 */
@Extension
public class MetricsMaintenanceTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(MetricsMaintenanceTask.class.getName());
    private static final long HOUR_MS = 3600_000;

    public MetricsMaintenanceTask() {
        super("DORA Metrics Maintenance");
    }

    @Override
    public long getRecurrencePeriod() {
        return HOUR_MS; // check every hour
    }

    @Override
    protected void execute(TaskListener listener) {
        try {
            DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
            if (config == null) return;

            // Cleanup old data
            long retainAfter = System.currentTimeMillis()
                    - ((long) config.getRetentionDays() * 86400_000);
            MetricsStore.getInstance().cleanup(retainAfter);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "DORA metrics maintenance failed", e);
        }
    }
}
