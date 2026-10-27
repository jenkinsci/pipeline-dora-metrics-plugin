package io.jenkins.plugins.dorametrics.store;

import jenkins.model.Jenkins;
import jenkins.util.SystemProperties;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * SQLite embedded database for storing build metrics.
 * Located at JENKINS_HOME/pipeline-dora-metrics/metrics.db, or in the directory named by the
 * {@link #DIRECTORY_PROPERTY} system property.
 */
public class MetricsStore {

    private static final Logger LOGGER = Logger.getLogger(MetricsStore.class.getName());
    private static volatile MetricsStore instance;

    private static final Set<String> ALLOWED_ORDER_BY = Set.of(
            "avg_dur DESC", "avg_dur ASC", "failures DESC", "failures ASC",
            "total DESC", "total ASC", "failure_rate DESC"
    );

    /** A directory for the database instead of JENKINS_HOME, for example on local disk. */
    public static final String DIRECTORY_PROPERTY = MetricsStore.class.getName() + ".directory";

    /** Filesystem types SQLite should not be used on: its locking and syncing are unreliable there. */
    private static final Set<String> NETWORK_FILE_SYSTEMS = Set.of(
            "nfs", "nfs4", "cifs", "smb", "smbfs", "smb2", "smb3", "afpfs", "webdav", "fuse.sshfs",
            "glusterfs", "fuse.glusterfs", "ceph", "fuse.ceph", "lustre");

    private final String dbUrl;
    private final File dbFile;
    private final String networkFileSystem;

    // Writes share one connection, so this process never competes with itself for the write
    // lock, and the -wal and -shm files are not created and removed again for every write.
    private final ReentrantLock writeLock = new ReentrantLock();
    private Connection writeConnection;
    /** How long a write waits for another write in this process, as busy_timeout does for other processes. */
    long writeLockTimeoutMs = 5000;

    private MetricsStore() {
        File dbDir = databaseDirectory();
        if (!dbDir.isDirectory() && !dbDir.mkdirs()) {
            LOGGER.severe("Pipeline DORA Metrics: cannot create " + dbDir
                    + ", so builds are not recorded until it exists and Jenkins can write to it");
        }
        this.dbFile = new File(dbDir, "metrics.db");
        this.dbUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        this.networkFileSystem = networkFileSystemType(dbDir.toPath());
        if (networkFileSystem != null) {
            LOGGER.warning("Pipeline DORA Metrics: the database at " + dbFile + " is on a network filesystem ("
                    + networkFileSystem + "). SQLite is not reliable there; move it to local disk with -D"
                    + DIRECTORY_PROPERTY + "=<directory>");
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "SQLite JDBC driver not found", e);
        }

        initializeSchema();
    }

    /** Constructor for testing. */
    MetricsStore(String dbUrl) {
        this(dbUrl, null);
    }

    /** Constructor for testing, as if the database were on a filesystem of the given type. */
    MetricsStore(String dbUrl, String networkFileSystem) {
        this.dbUrl = dbUrl;
        this.dbFile = null;
        this.networkFileSystem = networkFileSystem;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "SQLite JDBC driver not found", e);
        }
        initializeSchema();
    }

    private static File databaseDirectory() {
        String configured = SystemProperties.getString(DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            File dir = new File(configured.trim()).getAbsoluteFile();
            LOGGER.info("Pipeline DORA Metrics: keeping the database in " + dir);
            return dir;
        }
        return new File(Jenkins.get().getRootDir(), "pipeline-dora-metrics");
    }

    /** The type of the filesystem {@code dir} is on when it is a network one, otherwise null. */
    static String networkFileSystemType(Path dir) {
        try {
            String type = Files.getFileStore(dir).type();
            return isNetworkFileSystem(type) ? type : null;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not tell which filesystem " + dir + " is on", e);
            return null;
        }
    }

    static boolean isNetworkFileSystem(String type) {
        return type != null && NETWORK_FILE_SYSTEMS.contains(type.toLowerCase(Locale.ROOT));
    }

    /** The network filesystem the database is on, or null when it is on local storage or unknown. */
    public String getNetworkFileSystem() {
        return networkFileSystem;
    }

    /** The database file, or null for a store opened by URL in tests. */
    public File getDatabaseFile() {
        return dbFile;
    }

    /** The store if one is open, without opening one: for callers that must never wait on the database. */
    public static MetricsStore existingInstance() {
        return instance;
    }

    public static synchronized MetricsStore getInstance() {
        if (instance == null) {
            instance = new MetricsStore();
        }
        return instance;
    }

    /** Reset singleton. Used by tests to reinitialize with fresh Jenkins home. */
    public static synchronized void setInstance(MetricsStore store) {
        if (instance != null && instance != store) {
            instance.close();
        }
        instance = store;
    }

    /** Closes the shared write connection when Jenkins stops; the next write opens it again. */
    public static void closeOnShutdown() {
        MetricsStore store;
        synchronized (MetricsStore.class) {
            store = instance;
        }
        if (store != null && !store.closeIfFree(2, TimeUnit.SECONDS)) {
            LOGGER.warning("Pipeline DORA Metrics: a write is still running, leaving the database to close with Jenkins");
        }
    }

    /** Closes the shared write connection unless a write holds it for longer than the timeout. */
    boolean closeIfFree(long timeout, TimeUnit unit) {
        try {
            if (!writeLock.tryLock(timeout, unit)) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            closeQuietly(writeConnection);
            writeConnection = null;
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    /** Holds the write lock until {@code release} opens, for tests that need a write in progress. */
    void holdWriteLockUntil(CountDownLatch held, CountDownLatch release)
            throws InterruptedException {
        writeLock.lock();
        try {
            held.countDown();
            release.await();
        } finally {
            writeLock.unlock();
        }
    }

    /** Closes the shared write connection. A later write opens a new one. */
    public void close() {
        writeLock.lock();
        try {
            closeQuietly(writeConnection);
            writeConnection = null;
        } finally {
            writeLock.unlock();
        }
    }

    /** Whether the shared write connection is open, for tests. */
    boolean writeConnectionOpen() {
        writeLock.lock();
        try {
            return writeConnection != null && !writeConnection.isClosed();
        } catch (SQLException e) {
            return false;
        } finally {
            writeLock.unlock();
        }
    }

    @FunctionalInterface
    private interface Write<T> {
        T run(Connection conn) throws SQLException;
    }

    /**
     * Runs {@code write} on the shared write connection, one write at a time. A transaction the
     * write left open is rolled back, and a connection that failed for any reason other than
     * another process holding the lock is closed, so the next write starts on a fresh one.
     */
    private <T> T write(Write<T> write) throws SQLException {
        try {
            if (!writeLock.tryLock(writeLockTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new org.sqlite.SQLiteException("Another write in Jenkins kept the database busy for longer than "
                        + writeLockTimeoutMs + "ms", org.sqlite.SQLiteErrorCode.SQLITE_BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting to write", e);
        }
        try {
            if (writeConnection == null || writeConnection.isClosed()) {
                writeConnection = getConnection();
            }
            Connection conn = writeConnection;
            try {
                return write.run(conn);
            } catch (SQLException e) {
                if (!isBusy(e)) {
                    closeQuietly(conn);
                    writeConnection = null;
                }
                throw e;
            } finally {
                if (writeConnection != null) {
                    try {
                        // setAutoCommit(true) commits, so only after a rollback that worked
                        if (!writeConnection.getAutoCommit()) {
                            if (rollbackQuietly(writeConnection)) {
                                writeConnection.setAutoCommit(true);
                            } else {
                                closeQuietly(writeConnection);
                                writeConnection = null;
                            }
                        }
                    } catch (SQLException e) {
                        closeQuietly(writeConnection);
                        writeConnection = null;
                    }
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(dbUrl);
        try {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA busy_timeout=5000");
                stmt.execute("PRAGMA journal_mode=WAL");
                // the write connection stays open, so cap the WAL file once it has been checkpointed
                stmt.execute("PRAGMA journal_size_limit=8388608");
            }
            org.sqlite.Function.create(conn, "REGEXP", new RegexpFunction());
            return conn;
        } catch (SQLException e) {
            closeQuietly(conn);
            throw e;
        }
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
        try {
            write(conn -> {
                createSchema(conn);
                return null;
            });
            LOGGER.info("Pipeline DORA Metrics: database initialized");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize metrics database", e);
        }
    }

    private static void createSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
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
        }
    }

    // === Write operations ===

    /** A stage to write alongside its build. */
    public static final class StageRow {
        final String name;
        final long durationMs;
        final String result;

        public StageRow(String name, long durationMs, String result) {
            this.name = name;
            this.durationMs = durationMs;
            this.result = result;
        }
    }

    /** A commit to write alongside its build. */
    public static final class CommitRow {
        final String sha;
        final String author;
        final long timestamp;

        public CommitRow(String sha, String author, long timestamp) {
            this.sha = sha;
            this.author = author;
            this.timestamp = timestamp;
        }
    }

    /**
     * Writes one build and the stages and commits belonging to it, in a single transaction.
     *
     * <p>The build row is upserted rather than replaced. INSERT OR REPLACE deletes the
     * conflicting row and inserts a new one, which hands out a new id, and since the foreign
     * keys on stages and commits are not enforced their old rows are left pointing at an id
     * that no longer exists. Nothing deletes those afterwards, including retention, which
     * looks its children up through their parent. Keeping the id and replacing the children
     * explicitly is what stops them accumulating.
     *
     * <p>The id comes from RETURNING because getGeneratedKeys reports 0 after an upsert that
     * updates rather than inserts.
     *
     * @return the build's id, or -1 if nothing was written
     */
    public long recordBuild(String jobName, int buildNumber, long timestamp,
                            long durationMs, String result, String triggerType, String branch,
                            List<StageRow> stages, List<CommitRow> commits) {
        String upsert = "INSERT INTO builds "
                + "(job_name, build_number, timestamp, duration_ms, result, trigger_type, branch) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(job_name, build_number) DO UPDATE SET "
                + "timestamp = excluded.timestamp, duration_ms = excluded.duration_ms, "
                + "result = excluded.result, trigger_type = excluded.trigger_type, "
                + "branch = excluded.branch "
                + "RETURNING id";

        try {
            return write(conn -> {
                conn.setAutoCommit(false);
                long buildId;

                try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                    ps.setString(1, jobName);
                    ps.setInt(2, buildNumber);
                    ps.setLong(3, timestamp);
                    ps.setLong(4, durationMs);
                    ps.setString(5, result);
                    ps.setString(6, triggerType);
                    ps.setString(7, branch);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            LOGGER.log(Level.WARNING, "No id returned for " + jobName + "#" + buildNumber);
                            return -1L;
                        }
                        buildId = rs.getLong(1);
                    }
                }

                // Replace rather than add to: this build may have been recorded before.
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM stages WHERE build_id = ?")) {
                    ps.setLong(1, buildId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM commits WHERE build_id = ?")) {
                    ps.setLong(1, buildId);
                    ps.executeUpdate();
                }

                if (stages != null && !stages.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO stages (build_id, stage_name, duration_ms, result) VALUES (?, ?, ?, ?)")) {
                        for (StageRow stage : stages) {
                            ps.setLong(1, buildId);
                            ps.setString(2, stage.name);
                            ps.setLong(3, stage.durationMs);
                            ps.setString(4, stage.result);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                if (commits != null && !commits.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO commits (build_id, commit_sha, author, timestamp) VALUES (?, ?, ?, ?)")) {
                        for (CommitRow commit : commits) {
                            ps.setLong(1, buildId);
                            ps.setString(2, commit.sha);
                            ps.setString(3, commit.author);
                            ps.setLong(4, commit.timestamp);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                conn.commit();
                return buildId;
            });
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to record build: " + jobName + "#" + buildNumber, e);
            return -1;
        }
    }

    /**
     * Upserts the build row and clears the stages and commits recorded against it, returning
     * its id so they can be written back.
     *
     * <p>Prefer {@link #recordBuild} where the stages and commits are already known: it writes
     * all three in one transaction, so the build is never briefly left without them.
     *
     * @return the build's id, or -1 if nothing was written
     */
    public long insertBuild(String jobName, int buildNumber, long timestamp,
                            long durationMs, String result, String triggerType, String branch) {
        return recordBuild(jobName, buildNumber, timestamp, durationMs, result, triggerType, branch,
                Collections.emptyList(), Collections.emptyList());
    }

    private static boolean rollbackQuietly(Connection conn) {
        if (conn == null) {
            return false;
        }
        try {
            conn.rollback();
            return true;
        } catch (SQLException e) {
            LOGGER.log(Level.FINE, "Could not roll back", e);
            return false;
        }
    }

    private static void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException e) {
            LOGGER.log(Level.FINE, "Could not close the connection", e);
        }
    }

    public void insertStage(long buildId, String stageName, long durationMs, String result) {
        String sql = "INSERT INTO stages (build_id, stage_name, duration_ms, result) VALUES (?, ?, ?, ?)";
        try {
            write(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, buildId);
                    ps.setString(2, stageName);
                    ps.setLong(3, durationMs);
                    ps.setString(4, result);
                    return ps.executeUpdate();
                }
            });
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to insert stage: " + stageName, e);
        }
    }

    public void insertCommit(long buildId, String sha, String author, long timestamp) {
        String sql = "INSERT INTO commits (build_id, commit_sha, author, timestamp) VALUES (?, ?, ?, ?)";
        try {
            write(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1, buildId);
                    ps.setString(2, sha);
                    ps.setString(3, author);
                    ps.setLong(4, timestamp);
                    return ps.executeUpdate();
                }
            });
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
        List<long[]> leadTimes = leadTimes(fromMs, toMs, jobPattern, excludedJobs, branchPattern);
        return leadTimes.stream().mapToLong(row -> row[1]).average().orElse(0);
    }

    /**
     * The lead time of each successful build in the window, as {@code [build start, lead time]},
     * worked out as {@link #avgLeadTimeMs(long, long, String, Set, String)} describes.
     */
    public List<long[]> leadTimes(long fromMs, long toMs, String jobPattern, Set<String> excludedJobs,
                                  String branchPattern) {
        List<long[]> rows = new ArrayList<>();
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
                + " SELECT d.timestamp, (d.timestamp + d.duration_ms) - f.first_commit FROM ordered d"
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
                while (rs.next()) {
                    rows.add(new long[] {rs.getLong(1), rs.getLong(2)});
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.FINE, "lead time query failed", e);
        }
        return rows;
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

    /**
     * Moves a job's records to its new name. Rows already stored under the new name
     * belong to a job that is gone, since the name was free until now, so they are
     * detached first, in the same transaction, instead of being merged into this
     * job's history or making the rename fail on the unique key.
     */
    public void renameJob(String oldName, String newName) {
        long now = System.currentTimeMillis();
        try {
            int moved = writeRetryingWhenBusy(conn -> {
                conn.setAutoCommit(false);
                try (PreparedStatement detach = conn.prepareStatement(
                             "UPDATE builds SET job_name = job_name || ? WHERE job_name = ?");
                     PreparedStatement rename = conn.prepareStatement(
                             "UPDATE builds SET job_name = ? WHERE job_name = ?")) {
                    detach.setString(1, DELETED_MARKER + now);
                    detach.setString(2, newName);
                    detach.executeUpdate();
                    rename.setString(1, newName);
                    rename.setString(2, oldName);
                    int updated = rename.executeUpdate();
                    conn.commit();
                    return updated;
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            });
            LOGGER.fine("Renamed " + moved + " build records from " + oldName + " to " + newName);
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
        try {
            int updated = writeRetryingWhenBusy(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, DELETED_MARKER + deletedAtMs);
                    ps.setString(2, fullName);
                    ps.setInt(3, prefix.length());
                    ps.setString(4, prefix);
                    return ps.executeUpdate();
                }
            });
            LOGGER.fine("Detached " + updated + " build records of deleted job " + fullName);
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to detach records of deleted job: " + fullName, e);
        }
    }

    private static final int BUSY_ATTEMPTS = 3;

    /**
     * Runs a write, trying again when another writer kept the database locked for
     * longer than busy_timeout. Used for the writes that keep a job's history tied
     * to the right name, where giving up leaves the history under a stale name.
     */
    private int writeRetryingWhenBusy(Write<Integer> write) throws SQLException {
        for (int attempt = 1; ; attempt++) {
            try {
                return write(write);
            } catch (SQLException e) {
                if (attempt >= BUSY_ATTEMPTS || !isBusy(e)) {
                    throw e;
                }
                LOGGER.fine("Database busy, trying again (" + attempt + "/" + BUSY_ATTEMPTS + ")");
                try {
                    Thread.sleep(250L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static boolean isBusy(SQLException e) {
        return e instanceof org.sqlite.SQLiteException
                && (((org.sqlite.SQLiteException) e).getResultCode().code & 0xFF)
                        == org.sqlite.SQLiteErrorCode.SQLITE_BUSY.code;
    }

    // === Maintenance ===

    public void cleanup(long retainAfterTimestamp) {
        try {
            write(conn -> {
                deleteOlderThan(conn, retainAfterTimestamp);
                return null;
            });
            LOGGER.info("Pipeline DORA Metrics: old data cleaned up");
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to cleanup old metrics", e);
        }
    }

    private static void deleteOlderThan(Connection conn, long retainAfterTimestamp) throws SQLException {
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

        // Rows whose build is gone entirely. Retention looks children up through their
        // parent, so anything orphaned by an older version of this plugin was never
        // reachable and would sit there for good.
        try (Statement stmt = conn.createStatement()) {
            int stages = stmt.executeUpdate(
                    "DELETE FROM stages WHERE build_id NOT IN (SELECT id FROM builds)");
            int commits = stmt.executeUpdate(
                    "DELETE FROM commits WHERE build_id NOT IN (SELECT id FROM builds)");
            if (stages > 0 || commits > 0) {
                LOGGER.info("Pipeline DORA Metrics: removed " + stages + " orphaned stage rows and "
                        + commits + " orphaned commit rows");
            }
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
