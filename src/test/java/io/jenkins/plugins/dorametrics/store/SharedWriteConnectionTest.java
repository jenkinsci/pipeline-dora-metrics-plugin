package io.jenkins.plugins.dorametrics.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writes go through one long-lived connection rather than a new one each time. */
class SharedWriteConnectionTest {

    @TempDir
    Path dir;

    private MetricsStore store;

    @BeforeEach
    void open() {
        store = new MetricsStore("jdbc:sqlite:" + dir.resolve("metrics.db"));
    }

    @AfterEach
    void close() {
        store.close();
    }

    @Test
    void theWriteConnectionStaysOpenWithItsWal() {
        assertTrue(store.insertBuild("app", 1, 1000, 10, "SUCCESS", "SCM", "main") > 0);
        assertTrue(store.writeConnectionOpen());
        // with a connection per write the last close checkpointed and deleted the WAL every time
        assertTrue(Files.exists(dir.resolve("metrics.db-wal")));
    }

    @Test
    void manyThreadsWritingAtOnceAllLand() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Long>> ids = new ArrayList<>();
            for (int job = 0; job < 16; job++) {
                String name = "job-" + job;
                for (int build = 1; build <= 25; build++) {
                    int number = build;
                    ids.add(pool.submit(() -> store.insertBuild(name, number, number * 1000L, 10, "SUCCESS", "SCM", "main")));
                }
            }
            for (Future<Long> id : ids) {
                assertTrue(id.get() > 0);
            }
        } finally {
            pool.shutdown();
        }
        assertEquals(400, store.getAllBuilds(0, Long.MAX_VALUE).size());
    }

    @Test
    void aClosedConnectionIsOpenedAgainForTheNextWrite() {
        store.insertBuild("app", 1, 1000, 10, "SUCCESS", "SCM", "main");
        store.close();
        assertFalse(store.writeConnectionOpen());
        assertTrue(store.insertBuild("app", 2, 2000, 10, "SUCCESS", "SCM", "main") > 0);
        assertTrue(store.writeConnectionOpen());
        assertEquals(2, store.getAllBuilds(0, Long.MAX_VALUE).size());
    }

    /** Another write holding the lock, released when the test ends. */
    private CountDownLatch holdWriteLock(CountDownLatch release) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                store.holdWriteLockUntil(held, release);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.setDaemon(true);
        holder.start();
        held.await();
        return held;
    }

    @Test
    void aWriteWaitsForAnotherOnlySoLong() throws Exception {
        store.writeLockTimeoutMs = 300;
        CountDownLatch release = new CountDownLatch(1);
        try {
            holdWriteLock(release);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
                assertEquals(-1, store.insertBuild("app", 1, 1000, 10, "SUCCESS", "SCM", "main"));
                // retried as busy, then given up with a warning instead of waiting for good
                store.renameJob("app", "web");
            });
        } finally {
            release.countDown();
        }
        assertTrue(store.insertBuild("app", 2, 2000, 10, "SUCCESS", "SCM", "main") > 0);
    }

    @Test
    void closingOnShutdownDoesNotWaitForAStuckWrite() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try {
            MetricsStore.setInstance(store);
            holdWriteLock(release);
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), MetricsStore::closeOnShutdown);
        } finally {
            release.countDown();
            MetricsStore.setInstance(null);
        }
    }

    @Test
    void replacingTheStoreClosesTheOldOne() {
        MetricsStore other = new MetricsStore("jdbc:sqlite:" + dir.resolve("other.db"));
        try {
            MetricsStore.setInstance(store);
            MetricsStore.setInstance(other);
            assertFalse(store.writeConnectionOpen());
        } finally {
            MetricsStore.setInstance(null);
        }
        assertFalse(other.writeConnectionOpen());
    }
}
