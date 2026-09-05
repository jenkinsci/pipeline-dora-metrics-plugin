package io.jenkins.plugins.dorametrics.store;

import io.jenkins.plugins.dorametrics.DisabledPipelines;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.export.ExportStorageConfig;
import io.jenkins.plugins.dorametrics.store.MetricsStore.BuildRecord;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.util.Date;
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
     * Export a daily snapshot of metrics to the configured storage backend.
     */
    public static void exportDailySnapshot() {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        if (config == null || !config.isExportEnabled()) return;

        try {
            MetricsStore store = MetricsStore.getInstance();
            long now = System.currentTimeMillis();
            long dayAgo = now - 86400_000;

            List<BuildRecord> builds = store.getAllBuilds(dayAgo, now, DisabledPipelines.names(config, store));
            if (builds.isEmpty()) {
                LOGGER.fine("No builds in last 24h, skipping export");
                return;
            }

            String jsonData = buildSnapshot(builds, store, now);
            String fileName = String.format("dora-metrics/%tF/snapshot.json", new Date(now));

            ExportStorageConfig storageConfig = config.getExportStorage();
            if (storageConfig == null) {
                LOGGER.warning("Export enabled but no storage configured");
                return;
            }

            storageConfig.upload(jsonData, fileName);

            LOGGER.info("Exported daily snapshot: " + builds.size() + " builds to " + storageConfig.getStorageType());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to export metrics snapshot", e);
        }
    }

    /**
     * Export a full dump for testing/debugging.
     */
    public static String exportFullDump(int days) {
        MetricsStore store = MetricsStore.getInstance();
        long now = System.currentTimeMillis();
        long fromMs = now - ((long) days * 86400_000);
        List<BuildRecord> builds = store.getAllBuilds(fromMs, now,
                DisabledPipelines.names(DoraGlobalConfiguration.get(), store));
        return buildSnapshot(builds, store, now);
    }

    static String buildSnapshot(List<BuildRecord> builds, MetricsStore store, long timestamp) {
        JSONObject snapshot = new JSONObject();
        snapshot.put("exported_at", timestamp);
        snapshot.put("total_builds", builds.size());

        JSONArray buildsArr = new JSONArray();
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
            for (MetricsStore.StageRecord s : store.getStages(b.id)) {
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
