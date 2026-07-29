package org.purpurmc.purpur.mirage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MirageChunkPrefetch — Predictive chunk loading based on player movement.
 *
 * Analyzes player movement direction and speed to pre-load chunks
 * before the player reaches them, reducing visible chunk loading lag.
 *
 * Inspired by C2ME's chunk prefetch system and Noisium's generation optimization.
 */
public final class MirageChunkPrefetch {

    private static final Logger LOGGER = Logger.getLogger("Mirage");

    // Player movement tracking: UUID -> MovementData
    private static final Map<java.util.UUID, MovementData> playerMovement = new ConcurrentHashMap<>();

    // Statistics
    private static final AtomicLong totalPrefetched = new AtomicLong(0);
    private static final AtomicLong totalHits = new AtomicLong(0); // Prefetched chunk was actually needed
    private static final AtomicLong totalMisses = new AtomicLong(0); // Prefetched chunk was not needed
    private static final AtomicLong totalEvicted = new AtomicLong(0);

    // Track prefetched chunks to detect hits/misses
    private static final Map<String, Long> prefetchedChunks = new ConcurrentHashMap<>();
    private static final long PREFETCH_EXPIRY_TICKS = 200; // 10 seconds

    private MirageChunkPrefetch() {}

    /**
     * Per-player movement tracking data.
     */
    private static final class MovementData {
        double lastX, lastY, lastZ;
        double velX, velZ;
        long lastUpdateTick;
        boolean initialized;

        void update(double x, double y, double z, long tick) {
            if (initialized) {
                double dt = tick - lastUpdateTick;
                if (dt > 0 && dt < 20) {
                    // Exponential moving average for velocity
                    double alpha = 0.5;
                    velX = velX * (1 - alpha) + (x - lastX) / dt * alpha;
                    velZ = velZ * (1 - alpha) + (z - lastZ) / dt * alpha;
                }
            }
            lastX = x;
            lastY = y;
            lastZ = z;
            lastUpdateTick = tick;
            initialized = true;
        }

        /** Get the predicted position N ticks ahead */
        double[] predictPosition(int ticksAhead) {
            return new double[]{
                lastX + velX * ticksAhead,
                lastY,
                lastZ + velZ * ticksAhead
            };
        }

        /** Get movement speed (blocks per tick) */
        double getSpeed() {
            return Math.sqrt(velX * velX + velZ * velZ);
        }

        /** Get movement direction in radians */
        double getDirection() {
            return Math.atan2(velZ, velX);
        }
    }

    /**
     * Update player movement data. Called from the monitor task.
     *
     * @param playerId The player's UUID
     * @param x        Player X position
     * @param y        Player Y position
     * @param z        Player Z position
     * @param tick     Current tick
     */
    public static void updatePlayerMovement(java.util.UUID playerId, double x, double y, double z, long tick) {
        if (!MirageConfig.enableChunkPrefetch) return;

        MovementData data = playerMovement.computeIfAbsent(playerId, k -> new MovementData());
        data.update(x, y, z, tick);
    }

    /**
     * Get the list of chunks that should be prefetched for a player.
     *
     * @param playerId The player's UUID
     * @param currentChunkX Player's current chunk X
     * @param currentChunkZ Player's current chunk Z
     * @param viewDistance Current view distance
     * @param tick Current tick
     * @return Array of [chunkX, chunkZ] pairs to prefetch, or null if no prefetch needed
     */
    public static int[][] getPrefetchChunks(java.util.UUID playerId, int currentChunkX, int currentChunkZ,
                                             int viewDistance, long tick) {
        if (!MirageConfig.enableChunkPrefetch) return null;

        MovementData data = playerMovement.get(playerId);
        if (data == null || !data.initialized) return null;

        double speed = data.getSpeed();
        if (speed < 0.1) return null; // Player is stationary or very slow

        // Predict where the player will be in N ticks
        int lookaheadTicks = MirageConfig.prefetchLookaheadTicks;
        double[] predicted = data.predictPosition(lookaheadTicks);

        int predictedChunkX = (int) Math.floor(predicted[0] / 16.0);
        int predictedChunkZ = (int) Math.floor(predicted[2] / 16.0);

        // Only prefetch if the player is moving toward a new chunk area
        int dx = predictedChunkX - currentChunkX;
        int dz = predictedChunkZ - currentChunkZ;
        if (Math.abs(dx) < 2 && Math.abs(dz) < 2) return null;

        // Generate a cone of chunks to prefetch in the movement direction
        java.util.List<int[]> chunks = new java.util.ArrayList<>();
        int prefetchRadius = Math.min(MirageConfig.prefetchRadius, viewDistance);

        // Prefetch chunks ahead in the movement direction
        for (int r = viewDistance - 1; r <= prefetchRadius + viewDistance - 1; r++) {
            for (int cx = -prefetchRadius; cx <= prefetchRadius; cx++) {
                for (int cz = -prefetchRadius; cz <= prefetchRadius; cz++) {
                    // Only prefetch chunks in the general direction of movement
                    int targetX = predictedChunkX + cx;
                    int targetZ = predictedChunkZ + cz;

                    // Check if this chunk is in the movement cone
                    double angle = Math.atan2(targetZ - currentChunkZ, targetX - currentChunkX);
                    double dirDiff = Math.abs(angle - data.getDirection());
                    if (dirDiff > Math.PI) dirDiff = 2 * Math.PI - dirDiff;
                    if (dirDiff > Math.PI / 3) continue; // 60 degree cone

                    // Skip chunks that are already within view distance
                    int distSq = (targetX - currentChunkX) * (targetX - currentChunkX) +
                                 (targetZ - currentChunkZ) * (targetZ - currentChunkZ);
                    if (distSq < viewDistance * viewDistance) continue;

                    chunks.add(new int[]{targetX, targetZ});
                }
            }
        }

        // Limit total prefetch per cycle
        int maxPrefetch = MirageConfig.maxPrefetchPerCycle;
        if (chunks.size() > maxPrefetch) {
            // Sort by distance to predicted position and take closest
            chunks.sort((a, b) -> {
                int da = (a[0] - predictedChunkX) * (a[0] - predictedChunkX) + (a[1] - predictedChunkZ) * (a[1] - predictedChunkZ);
                int db = (b[0] - predictedChunkX) * (b[0] - predictedChunkX) + (b[1] - predictedChunkZ) * (b[1] - predictedChunkZ);
                return Integer.compare(da, db);
            });
            chunks = chunks.subList(0, maxPrefetch);
        }

        // Track prefetched chunks
        long expiryTick = tick + PREFETCH_EXPIRY_TICKS;
        for (int[] chunk : chunks) {
            String key = chunk[0] + ":" + chunk[1];
            prefetchedChunks.put(key, expiryTick);
            totalPrefetched.incrementAndGet();
        }

        return chunks.isEmpty() ? null : chunks.toArray(new int[0][]);
    }

    /**
     * Called when a chunk is loaded by the server (not by prefetch).
     * Used to track hit/miss rates.
     *
     * @param chunkX Chunk X
     * @param chunkZ Chunk Z
     * @param tick Current tick
     */
    public static void onChunkLoaded(int chunkX, int chunkZ, long tick) {
        String key = chunkX + ":" + chunkZ;
        Long expiry = prefetchedChunks.remove(key);
        if (expiry != null) {
            if (tick <= expiry) {
                totalHits.incrementAndGet();
            } else {
                totalMisses.incrementAndGet();
                totalEvicted.incrementAndGet();
            }
        }

        // Clean up expired entries periodically
        if (prefetchedChunks.size() > 1000) {
            prefetchedChunks.entrySet().removeIf(entry -> entry.getValue() < tick);
        }
    }

    /**
     * Remove a player from tracking.
     */
    public static void removePlayer(java.util.UUID playerId) {
        playerMovement.remove(playerId);
    }

    /**
     * Get statistics for display.
     */
    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("prefetch_total", totalPrefetched.get());
        stats.put("prefetch_hits", totalHits.get());
        stats.put("prefetch_misses", totalMisses.get());
        stats.put("prefetch_evicted", totalEvicted.get());
        stats.put("prefetch_tracked_players", playerMovement.size());
        stats.put("prefetch_pending_chunks", prefetchedChunks.size());

        double hitRate = totalPrefetched.get() > 0 ?
            (double) totalHits.get() / totalPrefetched.get() * 100.0 : 0;
        stats.put("prefetch_hit_rate", String.format("%.1f%%", hitRate));

        return stats;
    }

    /**
     * Reset all statistics.
     */
    public static void resetStats() {
        totalPrefetched.set(0);
        totalHits.set(0);
        totalMisses.set(0);
        totalEvicted.set(0);
        prefetchedChunks.clear();
    }
}
