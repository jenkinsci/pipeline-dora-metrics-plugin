package io.jenkins.plugins.dorametrics;

import io.jenkins.plugins.dorametrics.store.MetricsStore;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Applies the job settings (production pattern, production folders, excluded pattern) when
 * data is read. They are also checked when a build is recorded, but applying them only
 * there meant a change never reached builds already stored, and folder jobs were dropped
 * again by the metric queries, which only knew the pattern.
 */
public final class JobFilter {

    private JobFilter() {
    }

    /** Full names of recorded jobs that the current job settings leave out. */
    public static Set<String> untracked(DoraGlobalConfiguration config, MetricsStore store) {
        if (config == null || store == null) {
            return Collections.emptySet();
        }
        Set<String> untracked = new HashSet<>();
        for (String name : store.getAllJobNames()) {
            if (name != null && !config.shouldTrackJob(originalName(name))) {
                untracked.add(name);
            }
        }
        return untracked;
    }

    /**
     * The name a job had before it was deleted. Its history is kept under the old name plus
     * a marker, and it should keep counting exactly as it did.
     */
    static String originalName(String recordedName) {
        int marker = recordedName.indexOf(MetricsStore.DELETED_MARKER);
        return marker >= 0 ? recordedName.substring(0, marker) : recordedName;
    }
}
