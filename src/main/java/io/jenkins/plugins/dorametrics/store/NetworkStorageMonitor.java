package io.jenkins.plugins.dorametrics.store;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;

import java.io.File;

/**
 * Tells administrators when the metrics database is on a network filesystem, where SQLite's
 * locking and syncing are unreliable and slow writes can delay builds. It only looks at a store
 * that is already open, so showing Manage Jenkins never waits on the database.
 */
@Extension
public class NetworkStorageMonitor extends AdministrativeMonitor {

    @Override
    public String getDisplayName() {
        return "Pipeline DORA Metrics database on network storage";
    }

    @Override
    public boolean isActivated() {
        return getFileSystem() != null;
    }

    /** The network filesystem the database is on, or null. */
    public String getFileSystem() {
        MetricsStore store = MetricsStore.existingInstance();
        return store != null ? store.getNetworkFileSystem() : null;
    }

    public String getDatabasePath() {
        MetricsStore store = MetricsStore.existingInstance();
        File file = store != null ? store.getDatabaseFile() : null;
        return file != null ? file.getPath() : "";
    }

    public String getDirectoryProperty() {
        return MetricsStore.DIRECTORY_PROPERTY;
    }
}
