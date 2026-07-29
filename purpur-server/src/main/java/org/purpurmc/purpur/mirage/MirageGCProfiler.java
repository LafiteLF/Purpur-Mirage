package org.purpurmc.purpur.mirage;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MirageGCProfiler — Garbage collection pause tracking and analysis.
 *
 * Monitors GC behavior to identify if garbage collection is causing TPS drops.
 * Tracks collection count, time, and estimates the impact on server performance.
 *
 * Inspired by spark's GC profiler and G1GC tuning best practices.
 */
public final class MirageGCProfiler {

    private static final Logger LOGGER = Logger.getLogger("Mirage");
    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    // Previous GC stats for delta calculation
    private static volatile long lastGcCount = 0;
    private static volatile long lastGcTime = 0;
    private static volatile long lastCheckTime = 0;

    // Accumulated stats
    private static final AtomicLong totalGcPauses = new AtomicLong(0);
    private static final AtomicLong totalGcPauseTimeMs = new AtomicLong(0);
    private static final AtomicLong maxGcPauseMs = new AtomicLong(0);
    private static final AtomicLong gcEpisodesSinceLastReport = new AtomicLong(0);

    // GC pause detection threshold (ms)
    private static volatile long pauseWarningThreshold = 50;

    // Track GC episodes within a tick window
    private static volatile long gcCountAtTickStart = 0;
    private static volatile long gcTimeAtTickStart = 0;

    // History of GC pauses for trend analysis
    private static final long[] PAUSE_HISTORY = new long[60]; // Last 60 GC episodes
    private static int pauseHistoryIndex = 0;

    private MirageGCProfiler() {}

    /**
     * Called at the start of each server tick to establish a baseline.
     */
    public static void tickStart(long tick) {
        if (!MirageConfig.enableGcProfiler) return;

        long currentGcCount = getTotalGcCount();
        long currentGcTime = getTotalGcTime();

        gcCountAtTickStart = currentGcCount;
        gcTimeAtTickStart = currentGcTime;
    }

    /**
     * Called at the end of each server tick to detect GC pauses during the tick.
     *
     * @param tick The current tick
     * @param tickTimeNanos The time taken for this tick in nanoseconds
     */
    public static void tickEnd(long tick, long tickTimeNanos) {
        if (!MirageConfig.enableGcProfiler) return;

        long currentGcCount = getTotalGcCount();
        long currentGcTime = getTotalGcTime();

        long gcCountDelta = currentGcCount - gcCountAtTickStart;
        long gcTimeDelta = currentGcTime - gcTimeAtTickStart;

        if (gcCountDelta > 0) {
            // GC occurred during this tick
            totalGcPauses.addAndGet(gcCountDelta);
            totalGcPauseTimeMs.addAndGet(gcTimeDelta);
            gcEpisodesSinceLastReport.addAndGet(gcCountDelta);

            // Track max pause
            if (gcTimeDelta > maxGcPauseMs.get()) {
                maxGcPauseMs.set(gcTimeDelta);
            }

            // Record in history
            synchronized (PAUSE_HISTORY) {
                for (int i = 0; i < gcCountDelta && i < 60; i++) {
                    PAUSE_HISTORY[pauseHistoryIndex % PAUSE_HISTORY.length] = gcTimeDelta / Math.max(1, gcCountDelta);
                    pauseHistoryIndex++;
                }
            }

            // Warn on long pauses
            if (gcTimeDelta >= pauseWarningThreshold) {
                LOGGER.log(Level.WARNING, "[Mirage] GC pause detected: {0} ms during tick {1} (collections: {2})",
                    new Object[]{gcTimeDelta, tick, gcCountDelta});
            }
        }
    }

    /**
     * Generate a GC report for display.
     */
    public static Map<String, Object> getReport() {
        Map<String, Object> report = new LinkedHashMap<>();

        long totalCollections = getTotalGcCount();
        long totalTime = getTotalGcTime();
        long currentCollections = totalGcPauses.get();
        long currentTime = totalGcPauseTimeMs.get();

        report.put("gc_total_collections", totalCollections);
        report.put("gc_total_time_ms", totalTime);
        report.put("gc_collections_tracked", currentCollections);
        report.put("gc_pause_time_tracked_ms", currentTime);
        report.put("gc_max_pause_ms", maxGcPauseMs.get());
        report.put("gc_avg_pause_ms", currentCollections > 0 ? String.format("%.2f", (double) currentTime / currentCollections) : "0.00");
        report.put("gc_since_last_report", gcEpisodesSinceLastReport.get());

        // GC algorithm info
        List<String> gcInfo = new ArrayList<>();
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            gcInfo.add(bean.getName() + " [count=" + bean.getCollectionCount() + ", time=" + bean.getCollectionTime() + "ms]");
        }
        report.put("gc_algorithms", gcInfo);

        // Memory pool info
        var heapUsage = MEMORY_BEAN.getHeapMemoryUsage();
        report.put("heap_used_mb", String.format("%.1f", heapUsage.getUsed() / 1024.0 / 1024.0));
        report.put("heap_committed_mb", String.format("%.1f", heapUsage.getCommitted() / 1024.0 / 1024.0));
        report.put("heap_max_mb", String.format("%.1f", heapUsage.getMax() / 1024.0 / 1024.0));

        return report;
    }

    /**
     * Get average GC pause time from recent history.
     */
    public static double getRecentAvgPauseMs() {
        synchronized (PAUSE_HISTORY) {
            int count = Math.min(pauseHistoryIndex, PAUSE_HISTORY.length);
            if (count == 0) return 0;
            long sum = 0;
            for (int i = 0; i < count; i++) {
                sum += PAUSE_HISTORY[i];
            }
            return (double) sum / count;
        }
    }

    /**
     * Get the percentage of server time spent in GC pauses.
     *
     * @param uptimeMs Server uptime in milliseconds
     * @return Percentage (0-100) of time spent in GC
     */
    public static double getGcTimePercent(long uptimeMs) {
        if (uptimeMs <= 0) return 0;
        return (double) totalGcPauseTimeMs.get() / uptimeMs * 100.0;
    }

    /**
     * Reset the report counters (called after /mirage gc report).
     */
    public static void resetReport() {
        gcEpisodesSinceLastReport.set(0);
    }

    /**
     * Reset all statistics.
     */
    public static void resetAll() {
        totalGcPauses.set(0);
        totalGcPauseTimeMs.set(0);
        maxGcPauseMs.set(0);
        gcEpisodesSinceLastReport.set(0);
        synchronized (PAUSE_HISTORY) {
            for (int i = 0; i < PAUSE_HISTORY.length; i++) PAUSE_HISTORY[i] = 0;
            pauseHistoryIndex = 0;
        }
    }

    // ========== Private helpers ==========

    private static long getTotalGcCount() {
        long total = 0;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long count = bean.getCollectionCount();
            if (count > 0) total += count;
        }
        return total;
    }

    private static long getTotalGcTime() {
        long total = 0;
        for (GarbageCollectorMXBean bean : GC_BEANS) {
            long time = bean.getCollectionTime();
            if (time > 0) total += time;
        }
        return total;
    }
}
