package io.jenkins.plugins.dorametrics.collectors;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import io.jenkins.plugins.dorametrics.store.MetricsStore;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks job renames and moves so stored metrics data stays linked
 * to the current job name, and detaches it when the job is deleted.
 */
@Extension
public class JobRenameListener extends ItemListener {

    private static final Logger LOGGER = Logger.getLogger(JobRenameListener.class.getName());

    /**
     * Rows can still be stored under this name from an earlier job: deleted before
     * the detach existed, deleted while the plugin was off, or detached by a write
     * that failed. They must not become the new job's history. Copies arrive here
     * too, through {@link ItemListener#onCopied}, and so do branch jobs a
     * multibranch project creates.
     */
    @Override
    public void onCreated(Item item) {
        try {
            MetricsStore.getInstance().detachDeletedJob(item.getFullName(), System.currentTimeMillis());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to detach earlier metrics stored under: " + item.getFullName(), e);
        }
    }

    @Override
    public void onDeleted(Item item) {
        try {
            MetricsStore.getInstance().detachDeletedJob(item.getFullName(), System.currentTimeMillis());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to detach metrics of deleted job: " + item.getFullName(), e);
        }
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        try {
            MetricsStore.getInstance().renameJob(oldFullName, newFullName);
            LOGGER.info("DORA Metrics: renamed job data from " + oldFullName + " to " + newFullName);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to rename job metrics: " + oldFullName + " -> " + newFullName, e);
        }
    }
}
