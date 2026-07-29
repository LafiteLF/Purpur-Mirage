package org.purpurmc.purpur.mirage;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MirageMetrics — Real-time performance metrics collector.
 *
 * Tracks TPS, memory usage, entity/chunk counts, and cache statistics
 * to provide a comprehensive view of server health and optimization effectiveness.
 */
public final class MirageMetrics {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    // TPS tracking
    private static final long[] TICK_TIMES = new long[1200]; // 60s at 20 tps
    private static int tickIndex = 0;
    private static volatile double tps1m = 20.0;
    private static volatile double tps5m = 20.0;
    private static volatile double tps15m = 20.0;

    // Tick time tracking
    private static final AtomicLong totalTickTime = new AtomicLong(0);
    private static final AtomicLong totalTickCount = new AtomicLong(0);
    private static volatile double avgTickTimeMs = 0;

    // Entity tracking
    private static final AtomicInteger totalEntities = new AtomicInteger(0);
    private static final AtomicInteger totalChunks = new AtomicInteger(0);
    private static final AtomicInteger totalLoadedChunks = new AtomicInteger(0);

    // Optimization impact tracking
    private static final AtomicLong entityAiSkipped = new AtomicLong(0);
    private static final AtomicLong chunksSkipped = new AtomicLong(0);
    private static final AtomicLong packetsReduced = new AtomicLong(0);
    private static final AtomicLong memorySavedBytes = new AtomicLong(0);
    private static final AtomicLong entitiesCulled = new AtomicLong(0);

    private MirageMetrics() {}

    /**
     * Record a tick's execution time.
     */
    public static void recordTick(long tickTimeNanos) {
        int idx = tickIndex;
        TICK_TIMES[idx % TICK_TIMES.length] = tickTimeNanos;
        tickIndex++;

        totalTickTime.addAndGet(tickTimeNanos);
        long count = totalTickCount.incrementAndGet();
        avgTickTimeMs = (double) totalTickTime.get() / count / 1_000_000.0;

        // Calculate TPS every 20 ticks
        if (tickIndex % 20 == 0) {
            calculateTPS();
        }
    }

    private static void calculateTPS() {
        tps1m = calculateTPS(1200);  // 60s
        tps5m = calculateTPS(6000);  // 5min
        tps15m = calculateTPS(18000); // 15min
    }

    private static double calculateTPS(int windowTicks) {
        if (tickIndex < 2) return 20.0;
        int samples = Math.min(tickIndex, Math.min(windowTicks, TICK_TIMES.length));
        if (samples < 2) return 20.0;

        int startIdx = (tickIndex - samples + TICK_TIMES.length) % TICK_TIMES.length;
        long totalNanos = 0;
        for (int i = 0; i < samples; i++) {
            int idx = (startIdx + i) % TICK_TIMES.length;
            totalNanos += TICK_TIMES[idx];
        }
        double avgTickMs = (double) totalNanos / samples / 1_000_000.0;
        if (avgTickMs <= 0) return 20.0;
        return Math.min(20.0, 1000.0 / avgTickMs / 50.0 * 20.0);
    }

    // ========== Entity tracking ==========

    public static void setEntityCounts(int total, int perWorld) {
        totalEntities.set(total);
    }

    public static void setChunkCounts(int loaded, int total) {
        totalLoadedChunks.set(loaded);
        totalChunks.set(total);
    }

    public static void incrementEntityAiSkipped() { entityAiSkipped.incrementAndGet(); }
    public static void incrementChunksSkipped() { chunksSkipped.incrementAndGet(); }
    public static void incrementPacketsReduced() { packetsReduced.incrementAndGet(); }
    public static void addMemorySaved(long bytes) { memorySavedBytes.addAndGet(bytes); }
    public static void addEntitiesCulled(int count) { entitiesCulled.addAndGet(count); }

    // ========== Getters ==========

    public static double getTps1m() { return tps1m; }
    public static double getTps5m() { return tps5m; }
    public static double getTps15m() { return tps15m; }
    public static double getAvgTickTimeMs() { return avgTickTimeMs; }
    public static int getTotalEntities() { return totalEntities.get(); }
    public static int getTotalLoadedChunks() { return totalLoadedChunks.get(); }
    public static long getEntityAiSkipped() { return entityAiSkipped.get(); }
    public static long getChunksSkipped() { return chunksSkipped.get(); }
    public static long getPacketsReduced() { return packetsReduced.get(); }
    public static long getMemorySavedBytes() { return memorySavedBytes.get(); }
    public static long getEntitiesCulled() { return entitiesCulled.get(); }

    /**
     * Get memory usage statistics.
     */
    public static MemoryStats getMemoryStats() {
        MemoryUsage heap = MEMORY_BEAN.getHeapMemoryUsage();
        double usedMB = heap.getUsed() / 1024.0 / 1024.0;
        double maxMB = heap.getMax() / 1024.0 / 1024.0;
        double committedMB = heap.getCommitted() / 1024.0 / 1024.0;
        double usedPercent = maxMB > 0 ? (usedMB / maxMB * 100.0) : 0;
        return new MemoryStats(usedMB, maxMB, committedMB, usedPercent);
    }

    public record MemoryStats(double usedMB, double maxMB, double committedMB, double usedPercent) {}

    /**
     * Get all statistics as a formatted map for display.
     */
    public static Map<String, Object> getAllStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        MemoryStats mem = getMemoryStats();

        stats.put("tps_1m", String.format("%.2f", tps1m));
        stats.put("tps_5m", String.format("%.2f", tps5m));
        stats.put("tps_15m", String.format("%.2f", tps15m));
        stats.put("avg_tick_ms", String.format("%.2f", avgTickTimeMs));
        stats.put("memory_used_mb", String.format("%.1f", mem.usedMB()));
        stats.put("memory_max_mb", String.format("%.1f", mem.maxMB()));
        stats.put("memory_percent", String.format("%.1f%%", mem.usedPercent()));
        stats.put("entities_total", totalEntities.get());
        stats.put("chunks_loaded", totalLoadedChunks.get());
        stats.put("entity_ai_skipped", entityAiSkipped.get());
        stats.put("chunks_skipped", chunksSkipped.get());
        stats.put("packets_reduced", packetsReduced.get());
        stats.put("entities_culled", entitiesCulled.get());
        stats.put("memory_saved_mb", String.format("%.1f", memorySavedBytes.get() / 1024.0 / 1024.0));

        // Add cache stats from MirageOptimizer
        Map<String, Object> cacheStats = MirageOptimizer.getCacheStatistics();
        stats.put("cache_nbt_dedup_size", cacheStats.get("nbt_dedup_cache_size"));
        stats.put("cache_nbt_dedup_hits", cacheStats.get("nbt_dedup_hits"));
        stats.put("cache_chunk_packet_size", cacheStats.get("chunk_packet_cache_size"));
        stats.put("cache_collision_size", cacheStats.get("collision_cache_size"));
        stats.put("cache_collision_hits", cacheStats.get("collision_cache_hits"));
        stats.put("adaptive_view_distance", cacheStats.get("current_adaptive_view_distance"));
        stats.put("current_tick", cacheStats.get("current_tick"));

        return stats;
    }

    /**
     * Get a health rating (0-100) based on current metrics.
     */
    public static int getHealthRating() {
        int score = 100;
        double tps = tps1m;
        if (tps < 20) score -= (20 - tps) * 3;

        MemoryStats mem = getMemoryStats();
        if (mem.usedPercent() > 85) score -= 20;
        else if (mem.usedPercent() > 70) score -= 10;

        if (avgTickTimeMs > 50) score -= (int)((avgTickTimeMs - 50) * 2);

        return Math.max(0, Math.min(100, score));
    }

    /**
     * Reset all counters. Called on /mirage optimize.
     */
    public static void resetCounters() {
        entityAiSkipped.set(0);
        chunksSkipped.set(0);
        packetsReduced.set(0);
        memorySavedBytes.set(0);
        entitiesCulled.set(0);
    }
}
