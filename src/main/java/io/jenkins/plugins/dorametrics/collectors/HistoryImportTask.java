package io.jenkins.plugins.dorametrics.collectors;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the build history import once, shortly after the plugin is first installed.
 *
 * <p>Whether it has run is kept in the global configuration rather than inferred from the
 * store being empty: an instance whose jobs do not match the filters, or which had no
 * builds in the window, would leave the store empty and be rescanned on every restart.
 *
 * <p>This is periodic rather than one-shot so that an instance still starting up, or one
 * whose configuration was unavailable on the first pass, gets another attempt.
 */
@Extension
public class HistoryImportTask extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(HistoryImportTask.class.getName());
    private static final long HOUR_MS = 3_600_000L;

    public HistoryImportTask() {
        super("DORA Build History Import");
    }

    @Override
    public long getRecurrencePeriod() {
        return HOUR_MS;
    }

    @Override
    public long getInitialDelay() {
        // Let the instance finish loading its jobs before walking all of them.
        return 5 * 60_000L;
    }

    @Override
    protected void execute(TaskListener listener) {
        try {
            DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
            if (config == null || config.isHistoryImportDone()) {
                return;
            }

            BuildHistoryImporter.Result result =
                    BuildHistoryImporter.importHistory(BuildHistoryImporter.resolveDays(config));
            if (result == null) {
                return; // an import was already running, try again next hour
            }

            // Mark it done even when some builds failed: the run happened, and retrying the
            // whole instance every hour would cost more than the few builds it missed.
            config.setHistoryImportDone(true);
            LOGGER.info("First build history import complete: " + result);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Build history import failed", e);
        }
    }
}
