package io.jenkins.plugins.dorametrics.store;

import io.jenkins.plugins.dorametrics.JobVisibility;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.export.ExportStorageConfig;
import io.jenkins.plugins.dorametrics.store.MetricsStore.BuildRecord;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds metrics snapshots and delegates upload to the configured storage backend.
 * Upload logic lives in each ExportStorageConfig implementation, not here.
 */
public class MetricsExporter {

    private static final Logger LOGGER = Logger.getLogger(MetricsExporter.class.getName());

    /**
     * Export a snapshot of the last 24 hours to the configured storage backend, logging rather
     * than throwing if it fails.
     */
    public static void exportDailySnapshot() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        if (config == null || !config.isExportEnabled()) return;
        long now = System.currentTimeMillis();
        try {
            exportFinishedBetween(now - 86400_000, now);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to export metrics snapshot", e);
        }
    }

    /**
     * Exports the builds that finished after {@code afterMs} and up to {@code untilMs}, to a
     * file named after the moment of the export so that no export overwrites another.
     * Throws when the export did not go through, so the caller can try the same window again.
     *
     * @return how many builds were exported; 0 when nothing finished in the window
     */
    public static int exportFinishedBetween(long afterMs, long untilMs) throws Exception {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        ExportStorageConfig storageConfig = config != null ? config.getExportStorage() : null;
        if (storageConfig == null) {
            throw new IllegalStateException("Export enabled but no storage configured");
        }
        MetricsStore store = MetricsStore.getInstance();
        List<BuildRecord> builds = store.getBuildsFinishedBetween(afterMs, untilMs,
                JobVisibility.excludedForEveryone(config, store));
        if (builds.isEmpty()) {
            LOGGER.fine("No builds finished since the last export, nothing to send");
            return 0;
        }

        String jsonData = buildSnapshot(builds, store, untilMs);
        storageConfig.upload(jsonData, fileName(untilMs));
        LOGGER.info("Exported snapshot: " + builds.size() + " builds to " + storageConfig.getStorageType());
        return builds.size();
    }

    static String fileName(long exportedAtMs) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat stamp = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        day.setTimeZone(TimeZone.getTimeZone("UTC"));
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date at = new Date(exportedAtMs);
        return "dora-metrics/" + day.format(at) + "/snapshot-" + stamp.format(at) + ".json";
    }

    /**
     * Export a full dump for testing/debugging.
     */
    public static String exportFullDump(int days) {
        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        long fromMs = now - ((long) days * 86400_000);
        List<BuildRecord> builds = store.getAllBuilds(fromMs, now,
                JobVisibility.excludedForEveryone(DoraGlobalConfiguration.get(), store));
        return buildSnapshot(builds, store, now);
    }

    static String buildSnapshot(List<BuildRecord> builds, MetricsStore store, long timestamp) {
        JSONObject snapshot = new JSONObject();
        snapshot.put("exported_at", timestamp);
        snapshot.put("total_builds", builds.size());

        JSONArray buildsArr = new JSONArray();
        java.util.Map<Long, List<MetricsStore.StageRecord>> stagesByBuild = store.getStagesByBuild(
                builds.stream().map(b -> b.id).collect(java.util.stream.Collectors.toList()));
        for (BuildRecord b : builds) {
            JSONObject bj = new JSONObject();
            bj.put("job", b.jobName);
            bj.put("build", b.buildNumber);
            bj.put("timestamp", b.timestamp);
            bj.put("duration_ms", b.durationMs);
            bj.put("result", b.result);
            bj.put("trigger", b.triggerType);
            bj.put("branch", b.branch);

            JSONArray stagesArr = new JSONArray();
            for (MetricsStore.StageRecord s : stagesByBuild.getOrDefault(b.id, java.util.Collections.emptyList())) {
                JSONObject sj = new JSONObject();
                sj.put("name", s.stageName);
                sj.put("duration_ms", s.durationMs);
                sj.put("result", s.result);
                stagesArr.add(sj);
            }
            bj.put("stages", stagesArr);
            buildsArr.add(bj);
        }
        snapshot.put("builds", buildsArr);
        return snapshot.toString(2);
    }
}
