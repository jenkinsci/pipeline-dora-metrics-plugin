package io.jenkins.plugins.dorametrics.collectors;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.ClassLoaderSanityThreadFactory;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import io.jenkins.plugins.dorametrics.DoraGlobalConfiguration;
import io.jenkins.plugins.dorametrics.store.MetricsStore;
import jenkins.util.SystemProperties;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes finished builds to the store on one background thread, so a build waits on the database
 * for a few seconds at most. Jenkins counts a build as running until every completion listener
 * returns, so a write stuck on slow storage would keep the build running, and the next build of
 * a job that does not allow concurrent builds waiting behind it.
 *
 * <p>A build waits for its own write for up to {@link #WAIT_PROPERTY} seconds, so on healthy
 * storage the data is there when the build finishes, as before. Once a wait runs out, builds do
 * not wait at all for the next minute, so slow storage costs one build the wait rather than every
 * build; nor do they while the write in progress is older than the wait. Builds are written in
 * order; when {@link #CAPACITY_PROPERTY} builds are already waiting, the next ones are not
 * recorded. Import history adds them later.
 */
public final class RecordingQueue {

    static final String WAIT_PROPERTY = RecordingQueue.class.getName() + ".waitSeconds";
    static final String CAPACITY_PROPERTY = RecordingQueue.class.getName() + ".capacity";
    private static final int DRAIN_SECONDS = 10;
    private static final long NO_WAIT_AFTER_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(1);

    private static final Logger LOGGER = Logger.getLogger(RecordingQueue.class.getName());

    /** What records a build. Tests swap it for one that blocks. */
    static Consumer<Run<?, ?>> recorder = BuildRecorder::record;

    private static Writer writer;
    private static boolean stopped;

    private RecordingQueue() {
    }

    /** Lets a Jenkins started again in the same JVM, as in tests, record again. */
    @Initializer(after = InitMilestone.PLUGINS_STARTED)
    public static synchronized void start() {
        stopped = false;
    }

    /** Hands {@code run} to the writer and waits a little while for it, never longer. */
    static void record(Run<?, ?> run) {
        DoraGlobalConfiguration config = DoraGlobalConfiguration.get();
        if (config != null && !config.shouldTrackJob(run.getParent().getFullName())) {
            return;
        }
        Writer current;
        synchronized (RecordingQueue.class) {
            if (stopped) {
                LOGGER.fine("Pipeline DORA Metrics: Jenkins is stopping, not recording " + run.getFullDisplayName());
                return;
            }
            if (writer == null) {
                writer = new Writer(Math.max(1, SystemProperties.getInteger(CAPACITY_PROPERTY, 1000)));
            }
            current = writer;
        }
        current.submit(run, TimeUnit.SECONDS.toMillis(Math.max(0, SystemProperties.getInteger(WAIT_PROPERTY, 5))));
    }

    /** Writes what is already queued when Jenkins stops, for a few seconds at most, then closes the store. */
    @Terminator
    public static void shutdown() throws InterruptedException {
        Writer current;
        synchronized (RecordingQueue.class) {
            stopped = true;
            current = writer;
            writer = null;
        }
        if (current != null) {
            current.stop(DRAIN_SECONDS);
        }
        MetricsStore.closeOnShutdown();
    }

    private static final class Writer {
        private final ThreadPoolExecutor pool;
        /** When the write in progress started, or 0 when none is. */
        private volatile long busySince;
        /** Builds do not wait before this time, set when a wait runs out. */
        private volatile long noWaitUntil;
        /** Builds not recorded because the queue was full, reported once the queue is empty again. */
        private final AtomicInteger skipped = new AtomicInteger();

        Writer(int capacity) {
            pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(capacity),
                    new NamingThreadFactory(new ClassLoaderSanityThreadFactory(new DaemonThreadFactory()),
                            "Pipeline DORA Metrics recorder"));
        }

        void submit(Run<?, ?> run, long waitMs) {
            // the job and number rather than the run, so a long queue does not keep builds in memory
            Job<?, ?> job = run.getParent();
            int number = run.getNumber();
            String name = run.getFullDisplayName();
            Future<?> written;
            try {
                written = pool.submit(() -> write(job, number, name));
            } catch (RejectedExecutionException e) {
                if (pool.isShutdown()) {
                    LOGGER.fine("Pipeline DORA Metrics: Jenkins is stopping, not recording " + name);
                } else if (skipped.getAndIncrement() == 0) {
                    LOGGER.warning("Pipeline DORA Metrics: " + pool.getQueue().size() + " builds are waiting to be "
                            + "written, so " + name + " and any more until the writer catches up are not recorded. "
                            + "Import history on the dashboard adds them later.");
                }
                return;
            }

            long now = System.currentTimeMillis();
            long since = busySince;
            if (waitMs <= 0 || now < noWaitUntil || (since != 0 && now - since > waitMs)) {
                return;
            }
            try {
                written.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                noWaitUntil = System.currentTimeMillis() + NO_WAIT_AFTER_TIMEOUT_MS;
                LOGGER.warning("Pipeline DORA Metrics: writing " + name + " took longer than "
                        + TimeUnit.MILLISECONDS.toSeconds(waitMs) + "s, so for the next minute builds finish without "
                        + "waiting for their metrics, which are written in the background. "
                        + "Slow storage for the database is the usual cause.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                LOGGER.log(Level.WARNING, "Failed to collect metrics for " + name, e.getCause());
            }
        }

        private void write(Job<?, ?> job, int number, String name) {
            busySince = System.currentTimeMillis();
            try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
                Run<?, ?> run = job.getBuildByNumber(number);
                if (run != null) {
                    recorder.accept(run);
                } else {
                    LOGGER.fine("Pipeline DORA Metrics: " + name + " is gone, nothing to record");
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to collect metrics for " + name, e);
            } finally {
                busySince = 0;
                if (pool.getQueue().isEmpty()) {
                    int missed = skipped.getAndSet(0);
                    if (missed > 0) {
                        LOGGER.info("Pipeline DORA Metrics: the writer has caught up. " + missed + " builds were not "
                                + "recorded meanwhile; Import history on the dashboard adds them.");
                    }
                }
            }
        }

        void stop(int seconds) throws InterruptedException {
            pool.shutdown();
            if (!pool.awaitTermination(seconds, TimeUnit.SECONDS)) {
                int left = pool.shutdownNow().size();
                LOGGER.warning("Pipeline DORA Metrics: " + left + " builds were not written before Jenkins stopped. "
                        + "Import history on the dashboard adds them later.");
            }
        }
    }
}
