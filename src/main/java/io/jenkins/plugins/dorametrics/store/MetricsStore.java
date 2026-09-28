package io.jenkins.plugins.dorametrics.store;

import jenkins.model.Jenkins;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * SQLite embedded database for storing build metrics.
 * Located at JENKINS_HOME/pipeline-dora-metrics/metrics.db
 */
public class MetricsStore {

    private static final Logger LOGGER = Logger.getLogger(MetricsStore.class.getName());
    private static MetricsStore instance;

    private static final Set<String> ALLOWED_ORDER_BY = Set.of(
            "avg_dur DESC", "avg_dur ASC", "failures DESC", "failures ASC",
            "total DESC", "total ASC", "failure_rate DESC"
    );

    private final String dbUrl;

    private MetricsStore() {
        File jenkinsHome = Jenkins.get().getRootDir();
        File dbDir = new File(jenkinsHome, "pipeline-dora-metrics");
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }
        this.dbUrl = "jdbc:sqlite:" + new File(dbDir, "metrics.db").getAbsolutePath();

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "SQLite JDBC driver not found", e);
        }

        initializeSchema();
    }

    /** Constructor for testing. */
    MetricsStore(String dbUrl) {
        this.dbUrl = dbUrl;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "SQLite JDBC driver not found", e);
        }
        initializeSchema();
    }

    public static synchronized MetricsStore getInstance() {
        if (instance == null) {
            instance = new MetricsStore();
        }
        return instance;
    }

    /** Reset singleton. Used by tests to reinitialize with fresh Jenkins home. */
    public static synchronized void setInstance(MetricsStore store) {
        instance = store;
    }

    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=5000");
        }
        org.sqlite.Function.create(conn, "REGEXP", new RegexpFunction());
        return conn;
    }

    /**
     * {@code value REGEXP pattern} for SQL, with the same full-match Java regex semantics as
     * the job filter and the MTTR calculation, so a pattern selects the same jobs everywhere.
     * SQLite has the operator but no implementation of its own. One instance per connection,
     * and a connection is only used by one thread, so the compiled pattern can be kept.
     */
    private static final class RegexpFunction extends org.sqlite.Function {
        private String lastRegex;
        private Pattern lastPattern;

        @Override
        protected void xFunc() throws SQLException {
            // X REGEXP Y calls regexp(Y, X): the pattern comes first
            String regex = value_text(0);
            String value = value_text(1);
            if (regex == null || value == null) {
                result();
                return;
            }
            if (!regex.equals(lastRegex)) {
                lastPattern = Pattern.compile(regex);
                lastRegex = regex;
            }
            result(lastPattern.matcher(value).matches() ? 1 : 0);
        }
    }

    private void initializeSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS builds ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "job_name TEXT NOT NULL,"
                    + "build_number INTEGER NOT NULL,"
                    + "timestamp INTEGER NOT NULL,"
                    + "duration_ms INTEGER NOT NULL,"
                    + "result TEXT NOT NULL,"
                    + "trigger_type TEXT,"
                    + "branch TEXT,"
                    + "UNIQUE(job_name, build_number)"
                    + ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS stages ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "build_id INTEGER NOT NULL,"
                    + "stage_name TEXT NOT NULL,"
                    + "duration_ms INTEGER NOT NULL,"
                    + "result TEXT NOT NULL,"
                    + "FOREIGN KEY (build_id) REFERENCES builds(id)"
                    + ")");

            stmt.execute("CREATE TABLE IF NOT EXISTS commits ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "build_id INTEGER NOT NULL,"
                    + "commit_sha TEXT NOT NULL,"
                    + "author TEXT,"
                    + "timestamp INTEGER NOT NULL,"
                    + "FOREIGN KEY (build_id) REFERENCES builds(id)"
                    + ")");

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_builds_job ON builds(job_name)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_builds_timestamp ON builds(timestamp)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_builds_result ON builds(result)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_stages_build ON stages(build_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_commits_build ON commits(build_id)");

            LOGGER.info("Pipeline DORA Metrics: database initialized");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize metrics database", e);
        }
    }

    // === Write operations ===

    public long insertBuild(String jobName, int buildNumber, long timestamp,
                            long durationMs, String result, String triggerType, String branch) {
        String sql = "INSERT OR REPLACE INTO builds (job_name, build_number, timestamp, duration_ms, result, trigger_type, branch) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobName);
            ps.setInt(2, buildNumber);
            ps.setLong(3, timestamp);
            ps.setLong(4, durationMs);
            ps.setString(5, result);
            ps.setString(6, triggerType);
            ps.setString(7, branch);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to insert build: " + jobName + "#" + buildNumber, e);
        }
        return -1;
    }

    public void insertStage(long buildId, String stageName, long durationMs, String result) {
        String sql = "INSERT INTO stages (build_id, stage_name, duration_ms, result) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, buildId);
            ps.setString(2, stageName);
            ps.setLong(3, durationMs);
            ps.setString(4, result);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to insert stage: " + stageName, e);
        }
    }

    public void insertCommit(long buildId, String sha, String author, long timestamp) {
        String sql = "INSERT INTO commits (build_id, commit_sha, author, timestamp) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, buildId);
            ps.setString(2, sha);
            ps.setString(3, author);
            ps.setLong(4, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to insert commit: " + sha, e);
        }
    }

    // === Read operations ===

    public List<BuildRecord> getBuilds(String jobName, long fromTimestamp, long toTimestamp) {
        List<BuildRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM builds WHERE job_name = ? AND timestamp BETWEEN ? AND ? ORDER BY timestamp";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, jobName);
            ps.setLong(2, fromTimestamp);
            ps.setLong(3, toTimestamp);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(BuildRecord.fromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query builds for " + jobName, e);
        }
        return records;
    }

    public List<BuildRecord> getAllBuilds(long fromTimestamp, long toTimestamp) {
        return getAllBuilds(fromTimestamp, toTimestamp, Collections.emptySet());
    }

    /** All builds in the window, leaving out the given job names. */
    public List<BuildRecord> getAllBuilds(long fromTimestamp, long toTimestamp, Set<String> excludedJobs) {
        List<BuildRecord> records = new ArrayList<>();
        List<String> excluded = usableNames(excludedJobs);
        String sql = "SELECT * FROM builds WHERE timestamp BETWEEN ? AND ?"
                + notIn("job_name", excluded) + " ORDER BY timestamp";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fromTimestamp);
            ps.setLong(2, toTimestamp);
            bindExcluded(ps, 3, excluded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(BuildRecord.fromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query all builds", e);
        }
        return records;
    }

    /**
     * For every job whose failures had not been fixed yet at {@code beforeMs}, when its run of
     * failures began: the earliest FAILURE after that job's last SUCCESS before that moment.
     * Lets a recovery inside a window be measured from a failure that started before it.
     */
    public java.util.Map<String, Long> failureStreaksOpenAt(long beforeMs, Set<String> excludedJobs,
                                                             String branchPattern) {
        java.util.Map<String, Long> starts = new java.util.HashMap<>();
        List<String> excluded = usableNames(excludedJobs);
        String sql = "SELECT f.job_name, MIN(f.timestamp) FROM builds f"
                + " WHERE f.timestamp < ? AND f.result = 'FAILURE'"
                + branchCondition("f.branch", branchPattern)
                + " AND NOT EXISTS (SELECT 1 FROM builds s WHERE s.job_name = f.job_name"
                + " AND s.result = 'SUCCESS' AND s.timestamp > f.timestamp AND s.timestamp < ?"
                + branchCondition("s.branch", branchPattern) + ")"
                + notIn("f.job_name", excluded)
                + " GROUP BY f.job_name";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            ps.setLong(index++, beforeMs);
            if (branchPattern != null) {
                ps.setString(index++, branchPattern);
            }
            ps.setLong(index++, beforeMs);
            if (branchPattern != null) {
                ps.setString(index++, branchPattern);
            }
            bindExcluded(ps, index, excluded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    starts.put(rs.getString(1), rs.getLong(2));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query open failure streaks", e);
        }
        return starts;
    }

    /**
     * Builds that finished after {@code afterMs} and no later than {@code untilMs}, leaving out
     * the given job names. A build is stored when it finishes, so this is what an export that
     * ran at {@code afterMs} could not have seen yet, however long before that the build started.
     */
    public List<BuildRecord> getBuildsFinishedBetween(long afterMs, long untilMs, Set<String> excludedJobs) {
        List<BuildRecord> records = new ArrayList<>();
        List<String> excluded = usableNames(excludedJobs);
        String sql = "SELECT * FROM builds WHERE timestamp + duration_ms > ? AND timestamp + duration_ms <= ?"
                + notIn("job_name", excluded) + " ORDER BY timestamp";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, afterMs);
            ps.setLong(2, untilMs);
            bindExcluded(ps, 3, excluded);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(BuildRecord.fromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query finished builds", e);
        }
        return records;
    }

    public List<String> getAllJobNames() {
        List<String> names = new ArrayList<>();
        String sql = "SELECT DISTINCT job_name FROM builds ORDER BY job_name";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                names.add(rs.getString("job_name"));
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query job names", e);
        }
        return names;
    }

    public long getEarliestCommitTimestamp(long buildId) {
        String sql = "SELECT MIN(timestamp) FROM commits WHERE build_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, buildId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query earliest commit", e);
        }
        return 0;
    }

    public List<StageRecord> getStages(long buildId) {
        List<StageRecord> records = new ArrayList<>();
        String sql = "SELECT * FROM stages WHERE build_id = ? ORDER BY id";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, buildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(new StageRecord(
                            rs.getLong("id"),
                            rs.getLong("build_id"),
                            rs.getString("stage_name"),
                            rs.getLong("duration_ms"),
                            rs.getString("result")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query stages", e);
        }
        return records;
    }

    /**
     * The stages of many builds at once, keyed by build id, each list in recorded order.
     * One query per few hundred builds instead of one per build, which is what made exporting
     * a month of history slow.
     */
    public java.util.Map<Long, List<StageRecord>> getStagesByBuild(java.util.Collection<Long> buildIds) {
        java.util.Map<Long, List<StageRecord>> byBuild = new java.util.HashMap<>();
        List<Long> ids = new ArrayList<>(buildIds);
        try (Connection conn = getConnection()) {
            for (int start = 0; start < ids.size(); start += STAGE_BATCH) {
                List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + STAGE_BATCH));
                StringBuilder sql = new StringBuilder("SELECT * FROM stages WHERE build_id IN (");
                for (int i = 0; i < chunk.size(); i++) {
                    sql.append(i == 0 ? "?" : ",?");
                }
                sql.append(") ORDER BY id");
                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < chunk.size(); i++) {
                        ps.setLong(i + 1, chunk.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            StageRecord stage = new StageRecord(
                                    rs.getLong("id"),
                                    rs.getLong("build_id"),
                                    rs.getString("stage_name"),
                                    rs.getLong("duration_ms"),
                                    rs.getString("result"));
                            byBuild.computeIfAbsent(stage.buildId, k -> new ArrayList<>()).add(stage);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to query stages", e);
        }
        return byBuild;
    }

    private static final int STAGE_BATCH = 500;

    // === Optimized aggregate queries ===

    public long countSuccessfulBuilds(long fromMs, long toMs, String jobPattern) {
        return countSuccessfulBuilds(fromMs, toMs, jobPattern, Collections.emptySet());
    }

    public long countSuccessfulBuilds(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs) {
        return countSuccessfulBuilds(fromMs, toMs, jobPattern, excludedJobs, null);
    }

    /** As above, counting only builds on a branch matching {@code branchPattern}, or all when it is null. */
    public long countSuccessfulBuilds(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs,
                                      String branchPattern) {
        return executeCount(fromMs, toMs, jobPattern, "AND result = 'SUCCESS'", excludedJobs, branchPattern);
    }

    public long countTotalBuilds(long fromMs, long toMs, String jobPattern) {
        return countTotalBuilds(fromMs, toMs, jobPattern, Collections.emptySet());
    }

    public long countTotalBuilds(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs) {
        return countTotalBuilds(fromMs, toMs, jobPattern, excludedJobs, null);
    }

    /** As above, counting only builds on a branch matching {@code branchPattern}, or all when it is null. */
    public long countTotalBuilds(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs,
                                 String branchPattern) {
        return executeCount(fromMs, toMs, jobPattern, "", excludedJobs, branchPattern);
    }

    /**
     * Builds that ran as a deployment, successfully or not: SUCCESS, UNSTABLE or FAILURE.
     * An aborted build, one that was never built, or one with no result deployed nothing.
     */
    public long countDeployments(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs,
                                 String branchPattern) {
        return executeCount(fromMs, toMs, jobPattern, "AND result IN ('SUCCESS', 'UNSTABLE', 'FAILURE')",
                excludedJobs, branchPattern);
    }

    public long countFailedBuilds(long fromMs, long toMs, String jobPattern) {
        return countFailedBuilds(fromMs, toMs, jobPattern, Collections.emptySet());
    }

    public long countFailedBuilds(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs) {
        return countFailedBuilds(fromMs, toMs, jobPattern, excludedJobs, null);
    }

    /** As above, counting only builds on a branch matching {@code branchPattern}, or all when it is null. */
    public long countFailedBuilds(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs,
                                  String branchPattern) {
        return executeCount(fromMs, toMs, jobPattern, "AND result = 'FAILURE'", excludedJobs, branchPattern);
    }

    public double avgLeadTimeMs(long fromMs, long toMs, String jobPattern) {
        return avgLeadTimeMs(fromMs, toMs, jobPattern, Collections.emptySet());
    }

    public double avgLeadTimeMs(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs) {
        return avgLeadTimeMs(fromMs, toMs, jobPattern, excludedJobs, null);
    }

    /** As above, over builds on a branch matching {@code branchPattern} only, or all when it is null. */
    /**
     * Average lead time of the successful builds in the window, as the time from the earliest
     * commit that build deployed until it finished. A changelog only lists what changed since
     * the build before, so a commit first built by a build that failed or was aborted is in
     * that build's changelog, not in the deploying one's. Every build since the job's previous
     * success therefore counts toward the next success. Only builds on a production branch
     * take part when {@code branchPattern} is set.
     */
    public double avgLeadTimeMs(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs,
                                String branchPattern) {
        boolean filtered = jobPattern != null && !".*".equals(jobPattern);
        List<String> excluded = usableNames(excludedJobs);
        String sql = "WITH ordered AS ("
                + " SELECT id, job_name, timestamp, duration_ms, result,"
                // how many successes this job had before this build: the same for a success and
                // every build since the success before it
                + " COALESCE(SUM(CASE WHEN result = 'SUCCESS' THEN 1 ELSE 0 END) OVER ("
                + " PARTITION BY job_name ORDER BY timestamp, id"
                + " ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS deployment"
                + " FROM builds WHERE 1 = 1" + branchCondition("branch", branchPattern) + "),"
                + " first_commits AS ("
                + " SELECT o.job_name, o.deployment, MIN(c.timestamp) AS first_commit"
                + " FROM ordered o INNER JOIN commits c ON c.build_id = o.id"
                + " WHERE c.timestamp > 0 GROUP BY o.job_name, o.deployment)"
                + " SELECT AVG((d.timestamp + d.duration_ms) - f.first_commit) FROM ordered d"
                + " INNER JOIN first_commits f ON f.job_name = d.job_name AND f.deployment = d.deployment"
                + " WHERE d.result = 'SUCCESS' AND d.timestamp BETWEEN ? AND ?"
                + (filtered ? " AND d.job_name REGEXP ?" : "")
                + notIn("d.job_name", excluded);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int index = 1;
            if (branchPattern != null) {
                ps.setString(index++, branchPattern);
            }
            ps.setLong(index++, fromMs);
            ps.setLong(index++, toMs);
            if (filtered) {
                ps.setString(index++, jobPattern);
            }
            bindExcluded(ps, index, excluded);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.FINE, "avgLeadTimeMs query failed", e);
        }
        return 0;
    }

    public List<JobStats> getJobStats(long fromMs, long toMs, int limit, String orderBy) {
        return getJobStats(fromMs, toMs, limit, orderBy, Collections.emptySet());
    }

    public List<JobStats> getJobStats(long fromMs, long toMs, int limit, String orderBy, Set<String> excludedJobs) {
        if (!ALLOWED_ORDER_BY.contains(orderBy)) {
            LOGGER.warning("Rejected invalid orderBy: " + orderBy);
            return Collections.emptyList();
        }
        List<JobStats> results = new ArrayList<>();
        List<String> excluded = usableNames(excludedJobs);
        String sql = "SELECT job_name, COUNT(*) as total, "
                + "AVG(duration_ms) as avg_dur, "
                + "SUM(CASE WHEN result = 'FAILURE' THEN 1 ELSE 0 END) as failures, "
                + "SUM(CASE WHEN result = 'FAILURE' THEN 1.0 ELSE 0 END) / COUNT(*) as failure_rate "
                + "FROM builds WHERE timestamp BETWEEN ? AND ?"
                + notIn("job_name", excluded)
                + " GROUP BY job_name ORDER BY " + orderBy + " LIMIT ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            ps.setInt(bindExcluded(ps, 3, excluded), limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new JobStats(
                            rs.getString("job_name"),
                            rs.getInt("total"),
                            rs.getDouble("avg_dur"),
                            rs.getInt("failures")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "getJobStats query failed", e);
        }
        return results;
    }

    public List<StageStats> getStageStats(long fromMs, long toMs, int limit, String orderBy) {
        return getStageStats(fromMs, toMs, limit, orderBy, Collections.emptySet());
    }

    public List<StageStats> getStageStats(long fromMs, long toMs, int limit, String orderBy, Set<String> excludedJobs) {
        if (!ALLOWED_ORDER_BY.contains(orderBy)) {
            LOGGER.warning("Rejected invalid orderBy: " + orderBy);
            return Collections.emptyList();
        }
        List<StageStats> results = new ArrayList<>();
        List<String> excluded = usableNames(excludedJobs);
        String sql = "SELECT s.stage_name, COUNT(*) as total, "
                + "AVG(s.duration_ms) as avg_dur, "
                + "SUM(CASE WHEN s.result = 'FAILURE' THEN 1 ELSE 0 END) as failures, "
                + "SUM(CASE WHEN s.result = 'FAILURE' THEN 1.0 ELSE 0 END) / COUNT(*) as failure_rate "
                + "FROM stages s INNER JOIN builds b ON s.build_id = b.id "
                + "WHERE b.timestamp BETWEEN ? AND ?"
                + notIn("b.job_name", excluded)
                + " GROUP BY s.stage_name ORDER BY " + orderBy + " LIMIT ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            ps.setInt(bindExcluded(ps, 3, excluded), limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new StageStats(
                            rs.getString("stage_name"),
                            rs.getInt("total"),
                            rs.getDouble("avg_dur"),
                            rs.getInt("failures")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "getStageStats query failed", e);
        }
        return results;
    }

    private long executeCount(long fromMs, long toMs, String jobPattern, String extraWhere,
                              Set<String> excludedJobs, String branchPattern) {
        boolean filtered = jobPattern != null && !".*".equals(jobPattern);
        List<String> excluded = usableNames(excludedJobs);
        String sql = "SELECT COUNT(*) FROM builds WHERE timestamp BETWEEN ? AND ?"
                + (filtered ? " AND job_name REGEXP ?" : "")
                + branchCondition("branch", branchPattern)
                + " " + extraWhere
                + notIn("job_name", excluded);
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, fromMs);
            ps.setLong(2, toMs);
            int index = 3;
            if (filtered) {
                ps.setString(index++, jobPattern);
            }
            if (branchPattern != null) {
                ps.setString(index++, branchPattern);
            }
            bindExcluded(ps, index, excluded);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.FINE, "Count query failed", e);
        }
        return 0;
    }

    /**
     * SQL fragment keeping builds whose branch matches the pattern, with one bind variable for
     * it. A build with no branch is kept: nothing says it came from a branch that is not
     * production, and a deploy job without SCM has none. Empty when there is no pattern.
     */
    private static String branchCondition(String column, String branchPattern) {
        if (branchPattern == null) return "";
        return " AND (" + column + " IS NULL OR " + column + " = '' OR " + column + " REGEXP ?)";
    }

    /**
     * Whether a build's branch passes the same test as {@link #branchCondition}, for the
     * metrics that are worked out row by row.
     */
    public static boolean isOnBranch(BuildRecord build, String branchPattern) {
        return branchPattern == null || build.branch == null || build.branch.isEmpty()
                || build.branch.matches(branchPattern);
    }

    /**
     * SQL fragment excluding the given job names from a query, with one bind
     * variable per name so the names are never interpolated. Empty when there is
     * nothing to exclude. The bundled SQLite accepts 250000 bind variables per
     * statement, far more than any realistic number of disabled jobs.
     */
    private static String notIn(String column, List<String> excludedJobs) {
        if (excludedJobs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" AND ").append(column).append(" NOT IN (");
        for (int i = 0; i < excludedJobs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('?');
        }
        return sb.append(')').toString();
    }

    /** Binds the excluded job names starting at index and returns the next free index. */
    private static int bindExcluded(PreparedStatement ps, int index, List<String> excludedJobs) throws SQLException {
        for (String name : excludedJobs) {
            ps.setString(index++, name);
        }
        return index;
    }

    /**
     * The names from {@code excludedJobs} that can actually be matched against
     * {@code job_name}, in a fixed order so the placeholders {@link #notIn} writes and the
     * values {@link #bindExcluded} binds always line up.
     *
     * <p>A null entry is dropped rather than bound: {@code job_name NOT IN (NULL)} is NULL
     * for every row in SQL, which would silently reduce every metric to zero instead of
     * excluding one job. No recorded job name is null, but these overloads are public API
     * and a caller-supplied set can be.
     */
    private static List<String> usableNames(Set<String> excludedJobs) {
        if (excludedJobs == null || excludedJobs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> usable = new ArrayList<>(excludedJobs.size());
        for (String name : excludedJobs) {
            if (name != null && !name.isEmpty()) {
                usable.add(name);
            }
        }
        return usable;
    }

    /**
     * Convert simple regex patterns to SQLite GLOB patterns.
     * Handles common cases: .* -> *, .+ -> ?*, literal strings.
     */
    static String regexToGlob(String regex) {
        if (regex == null || ".*".equals(regex)) return "*";
        // Handle Pattern.quote() output: ^\Qliteral\E$
        if (regex.startsWith("^\\Q") && regex.endsWith("\\E$")) {
            return regex.substring(3, regex.length() - 3);
        }
        return regex
                .replace(".*", "*")
                .replace(".+", "?*")
                .replace("\\.", ".");
    }

    public void renameJob(String oldName, String newName) {
        String sql = "UPDATE builds SET job_name = ? WHERE job_name = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newName);
            ps.setString(2, oldName);
            int updated = ps.executeUpdate();
            LOGGER.fine("Renamed " + updated + " build records from " + oldName + " to " + newName);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to rename job: " + oldName + " -> " + newName, e);
        }
    }

    /** Marks records of a deleted job. '@' is not allowed in a Jenkins item name. */
    public static final String DELETED_MARKER = "@deleted-";

    /**
     * Detaches the records of a deleted job, and of everything below it when it
     * was a folder, from the job name. The history stays in the totals that
     * administrators and the scheduled export see, but a new job created under
     * the same name starts empty instead of inheriting it.
     */
    public void detachDeletedJob(String fullName, long deletedAtMs) {
        String sql = "UPDATE builds SET job_name = job_name || ? "
                + "WHERE job_name = ? OR substr(job_name, 1, ?) = ?";
        String prefix = fullName + "/";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, DELETED_MARKER + deletedAtMs);
            ps.setString(2, fullName);
            ps.setInt(3, prefix.length());
            ps.setString(4, prefix);
            int updated = ps.executeUpdate();
            LOGGER.fine("Detached " + updated + " build records of deleted job " + fullName);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to detach records of deleted job: " + fullName, e);
        }
    }

    // === Maintenance ===

    public void cleanup(long retainAfterTimestamp) {
        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM commits WHERE build_id IN (SELECT id FROM builds WHERE timestamp < ?)")) {
                ps.setLong(1, retainAfterTimestamp);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM stages WHERE build_id IN (SELECT id FROM builds WHERE timestamp < ?)")) {
                ps.setLong(1, retainAfterTimestamp);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM builds WHERE timestamp < ?")) {
                ps.setLong(1, retainAfterTimestamp);
                ps.executeUpdate();
            }
            LOGGER.info("Pipeline DORA Metrics: old data cleaned up");
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to cleanup old metrics", e);
        }
    }

    // === Record types ===

    public static class BuildRecord {
        public final long id;
        public final String jobName;
        public final int buildNumber;
        public final long timestamp;
        public final long durationMs;
        public final String result;
        public final String triggerType;
        public final String branch;

        public BuildRecord(long id, String jobName, int buildNumber, long timestamp,
                           long durationMs, String result, String triggerType, String branch) {
            this.id = id;
            this.jobName = jobName;
            this.buildNumber = buildNumber;
            this.timestamp = timestamp;
            this.durationMs = durationMs;
            this.result = result;
            this.triggerType = triggerType;
            this.branch = branch;
        }

        public static BuildRecord fromResultSet(ResultSet rs) throws SQLException {
            return new BuildRecord(
                    rs.getLong("id"), rs.getString("job_name"), rs.getInt("build_number"),
                    rs.getLong("timestamp"), rs.getLong("duration_ms"), rs.getString("result"),
                    rs.getString("trigger_type"), rs.getString("branch"));
        }

        public boolean isSuccess() { return "SUCCESS".equals(result); }
        public boolean isFailure() { return "FAILURE".equals(result); }
    }

    public static class StageRecord {
        public final long id;
        public final long buildId;
        public final String stageName;
        public final long durationMs;
        public final String result;

        public StageRecord(long id, long buildId, String stageName, long durationMs, String result) {
            this.id = id;
            this.buildId = buildId;
            this.stageName = stageName;
            this.durationMs = durationMs;
            this.result = result;
        }
    }

    public static class JobStats {
        public final String jobName;
        public final int buildCount;
        public final double avgDurationMs;
        public final int failureCount;

        public JobStats(String jobName, int buildCount, double avgDurationMs, int failureCount) {
            this.jobName = jobName;
            this.buildCount = buildCount;
            this.avgDurationMs = avgDurationMs;
            this.failureCount = failureCount;
        }
    }

    public static class StageStats {
        public final String stageName;
        public final int totalRuns;
        public final double avgDurationMs;
        public final int failureCount;

        public StageStats(String stageName, int totalRuns, double avgDurationMs, int failureCount) {
            this.stageName = stageName;
            this.totalRuns = totalRuns;
            this.avgDurationMs = avgDurationMs;
            this.failureCount = failureCount;
        }
    }
}
