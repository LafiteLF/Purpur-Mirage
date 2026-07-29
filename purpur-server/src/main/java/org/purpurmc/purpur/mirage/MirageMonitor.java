package org.purpurmc.purpur.mirage;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MirageMonitor — Runtime monitoring and auto-optimization task.
 *
 * Runs periodically (every N ticks) to:
 * - Check memory pressure and auto-clear caches when needed
 * - Log warnings when TPS drops below threshold
 * - Track entity counts and trigger entity limiter
 * - Auto-adjust view distance based on server load
 * - Log periodic health summaries
 */
public final class MirageMonitor {

    private static final Logger LOGGER = Logger.getLogger("Mirage");
    private static volatile boolean enabled = true;
    private static volatile long lastCheckTick = 0;
    private static volatile long lastWarningTick = 0;
    private static volatile long lastSummaryTick = 0;

    // Memory pressure tracking
    private static volatile int memoryWarningCount = 0;
    private static volatile long lastGcSuggestionTick = 0;

    // Track if we're in a low-TPS state for logging
    private static volatile boolean inLowTpsState = false;
    private static volatile boolean inHighMemoryState = false;

    private MirageMonitor() {}

    /**
     * Called every server tick from the patch.
     * Performs periodic checks at the configured interval.
     *
     * @param currentTick The current server tick
     */
    public static void tick(long currentTick) {
        if (!enabled || !MirageConfig.enableMonitor) return;

        int interval = MirageConfig.monitorIntervalTicks;

        if (currentTick - lastCheckTick >= interval) {
            lastCheckTick = currentTick;
            performChecks(currentTick);
        }

        // Log periodic summary (every 5 minutes = 6000 ticks)
        if (MirageConfig.logPeriodicSummary && currentTick - lastSummaryTick >= 6000) {
            lastSummaryTick = currentTick;
            logHealthSummary();
        }
    }

    /**
     * Perform all monitoring checks.
     */
    private static void performChecks(long currentTick) {
        // 1. Memory pressure check
        checkMemoryPressure(currentTick);

        // 2. TPS warning check
        checkTpsWarning(currentTick);

        // 3. Entity count tracking
        updateEntityCounts();

        // 4. Adaptive view distance
        updateAdaptiveViewDistance(currentTick);
    }

    /**
     * Check memory pressure and take action if needed.
     */
    private static void checkMemoryPressure(long currentTick) {
        MirageMetrics.MemoryStats mem = MirageMetrics.getMemoryStats();

        if (mem.usedPercent() > MirageConfig.memoryCriticalThreshold) {
            // Critical memory state
            if (!inHighMemoryState) {
                inHighMemoryState = true;
                memoryWarningCount++;
                LOGGER.log(Level.WARNING, "[Mirage] Memory critical: {0}% used ({1} MB / {2} MB) - clearing caches",
                    new Object[]{String.format("%.1f", mem.usedPercent()),
                                 String.format("%.0f", mem.usedMB()),
                                 String.format("%.0f", mem.maxMB())});
            }

            // Auto-clear caches
            if (MirageConfig.autoClearCacheOnPressure) {
                MirageOptimizer.clearAllCaches();
                long freedBytes = estimateFreedMemory();
                MirageMetrics.addMemorySaved(freedBytes);
                LOGGER.log(Level.INFO, "[Mirage] Auto-cleared optimization caches (estimated {0} KB freed)",
                    String.format("%.0f", freedBytes / 1024.0));
            }

            // Suggest GC if we haven't recently
            if (MirageConfig.autoSuggestGc && currentTick - lastGcSuggestionTick > 6000) {
                lastGcSuggestionTick = currentTick;
                long beforeGc = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                System.gc();
                long afterGc = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                long freed = beforeGc - afterGc;
                if (freed > 0) {
                    LOGGER.log(Level.INFO, "[Mirage] GC freed {0} MB",
                        String.format("%.1f", freed / 1024.0 / 1024.0));
                    MirageMetrics.addMemorySaved(freed);
                }
            }
        } else if (mem.usedPercent() > MirageConfig.memoryWarningThreshold) {
            // Warning state
            if (!inHighMemoryState) {
                inHighMemoryState = true;
                LOGGER.log(Level.WARNING, "[Mirage] Memory high: {0}% used ({1} MB / {2} MB)",
                    new Object[]{String.format("%.1f", mem.usedPercent()),
                                 String.format("%.0f", mem.usedMB()),
                                 String.format("%.0f", mem.maxMB())});
            }
        } else {
            // Memory is healthy
            if (inHighMemoryState) {
                inHighMemoryState = false;
                LOGGER.log(Level.INFO, "[Mirage] Memory back to normal: {0}% used",
                    String.format("%.1f", mem.usedPercent()));
            }
        }
    }

    /**
     * Check TPS and log warnings when low.
     */
    private static void checkTpsWarning(long currentTick) {
        double tps = MirageMetrics.getTps1m();

        if (tps < MirageConfig.tpsWarningThreshold) {
            if (!inLowTpsState || currentTick - lastWarningTick >= 200) {
                inLowTpsState = true;
                lastWarningTick = currentTick;
                LOGGER.log(Level.WARNING, "[Mirage] Low TPS detected: {0} (avg tick: {1}ms) - consider reducing view distance or entity limits",
                    new Object[]{String.format("%.2f", tps),
                                 String.format("%.2f", MirageMetrics.getAvgTickTimeMs())});
            }
        } else {
            if (inLowTpsState) {
                inLowTpsState = false;
                LOGGER.log(Level.INFO, "[Mirage] TPS recovered: {0}",
                    String.format("%.2f", tps));
            }
        }
    }

    /**
     * Update entity counts from server.
     */
    private static void updateEntityCounts() {
        try {
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server == null) return;

            int totalEntities = 0;
            int loadedChunks = 0;

            for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                int worldEntities = level.getEntityLookup().count();
                totalEntities += worldEntities;
                loadedChunks += level.getChunkSource().getLoadedChunksCount();

                // Update entity limiter counts per world
                int[] counts = new int[8];
                level.getEntityLookup().getAll().forEach(entity -> {
                    int cat = categorizeEntity(entity);
                    if (cat >= 0 && cat < 8) counts[cat]++;
                });
                String worldName = level.getWorld().getName();
                MirageEntityLimiter.updateWorldCounts(worldName, counts);

                // Check for entity limit violations
                if (MirageConfig.enableEntityLimiter && MirageConfig.autoCullExcess) {
                    var toCull = MirageEntityLimiter.calculateCullExcess(worldName);
                    if (!toCull.isEmpty()) {
                        for (var entry : toCull.entrySet()) {
                            LOGGER.log(Level.WARNING, "[Mirage] Entity limit exceeded in {0}: {1} has {2} excess entities",
                                new Object[]{worldName, MirageEntityLimiter.getCategoryName(entry.getKey()), entry.getValue()});
                        }
                    }
                }
            }

            MirageMetrics.setEntityCounts(totalEntities, 0);
            MirageMetrics.setChunkCounts(loadedChunks, 0);
        } catch (Exception e) {
            // Ignore errors during monitoring - don't crash the server
        }
    }

    /**
     * Update adaptive view distance based on current metrics.
     */
    private static void updateAdaptiveViewDistance(long currentTick) {
        if (!MirageConfig.dynamicViewDistance) return;

        try {
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            if (server == null) return;

            int playerCount = server.getPlayerCount();
            double tps = MirageMetrics.getTps1m();

            int newViewDistance = MirageOptimizer.calculateAdaptiveViewDistance(playerCount, tps, currentTick);
            int currentViewDistance = server.getPlayerList().getViewDistance();

            if (newViewDistance != currentViewDistance && newViewDistance > 0) {
                server.getPlayerList().setViewDistance(newViewDistance);
                LOGGER.log(Level.INFO, "[Mirage] Adaptive view distance: {0} (players={1}, tps={2})",
                    new Object[]{newViewDistance, playerCount, String.format("%.2f", tps)});
            }
        } catch (Exception e) {
            // Ignore errors during monitoring
        }
    }

    /**
     * Categorize an entity into a limit category.
     * Uses instanceof checks with broad Minecraft entity types.
     */
    private static int categorizeEntity(net.minecraft.world.entity.Entity entity) {
        try {
            if (entity instanceof net.minecraft.world.entity.Mob mob) {
                if (mob.classification == net.minecraft.world.entity.MobCategory.MONSTER) return MirageEntityLimiter.CATEGORY_MONSTER;
                return MirageEntityLimiter.CATEGORY_ANIMAL;
            }
            if (entity instanceof net.minecraft.world.entity.ambient.AmbientCreature) return MirageEntityLimiter.CATEGORY_AMBIENT;
            if (entity instanceof net.minecraft.world.entity.animal.WaterAnimal) return MirageEntityLimiter.CATEGORY_WATER;
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity) return MirageEntityLimiter.CATEGORY_ITEM;
            if (entity instanceof net.minecraft.world.entity.ExperienceOrb) return MirageEntityLimiter.CATEGORY_XP_ORB;
            if (entity instanceof net.minecraft.world.entity.projectile.Projectile) return MirageEntityLimiter.CATEGORY_PROJECTILE;
            return MirageEntityLimiter.CATEGORY_OTHER;
        } catch (Exception e) {
            return MirageEntityLimiter.CATEGORY_OTHER;
        }
    }

    /**
     * Estimate memory freed by cache clearing.
     */
    private static long estimateFreedMemory() {
        // Rough estimates based on cache sizes
        long estimate = 0;
        estimate += MirageOptimizer.getCacheStatistics().size() * 256; // rough estimate per cache entry
        return estimate;
    }

    /**
     * Log a health summary.
     */
    public static void logHealthSummary() {
        MirageMetrics.MemoryStats mem = MirageMetrics.getMemoryStats();
        int health = MirageMetrics.getHealthRating();

        LOGGER.log(Level.INFO, "[Mirage] === Health Summary ===");
        LOGGER.log(Level.INFO, "[Mirage] Health Rating: {0}/100", health);
        LOGGER.log(Level.INFO, "[Mirage] TPS: 1m={0}, 5m={1}, 15m={2}",
            new Object[]{String.format("%.2f", MirageMetrics.getTps1m()),
                         String.format("%.2f", MirageMetrics.getTps5m()),
                         String.format("%.2f", MirageMetrics.getTps15m())});
        LOGGER.log(Level.INFO, "[Mirage] Memory: {0}% ({1} MB / {2} MB)",
            new Object[]{String.format("%.1f", mem.usedPercent()),
                         String.format("%.0f", mem.usedMB()),
                         String.format("%.0f", mem.maxMB())});
        LOGGER.log(Level.INFO, "[Mirage] Entities: {0}, Chunks: {1}",
            new Object[]{MirageMetrics.getTotalEntities(), MirageMetrics.getTotalLoadedChunks()});
        LOGGER.log(Level.INFO, "[Mirage] AI skipped: {0}, Chunks skipped: {1}, Packets reduced: {2}",
            new Object[]{MirageMetrics.getEntityAiSkipped(),
                         MirageMetrics.getChunksSkipped(),
                         MirageMetrics.getPacketsReduced()});
        LOGGER.log(Level.INFO, "[Mirage] Memory saved: {0} MB, Entities culled: {1}",
            new Object[]{String.format("%.1f", MirageMetrics.getMemorySavedBytes() / 1024.0 / 1024.0),
                         MirageMetrics.getEntitiesCulled()});
        LOGGER.log(Level.INFO, "[Mirage] =========================");
    }

    /**
     * Run immediate optimization (called from /mirage optimize).
     */
    public static void runOptimization() {
        LOGGER.log(Level.INFO, "[Mirage] Running immediate optimization...");

        // Clear all caches
        MirageOptimizer.clearAllCaches();
        MirageMetrics.addMemorySaved(estimateFreedMemory());

        // Suggest GC
        long beforeGc = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.gc();
        long afterGc = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long freed = beforeGc - afterGc;
        if (freed > 0) {
            MirageMetrics.addMemorySaved(freed);
        }

        LOGGER.log(Level.INFO, "[Mirage] Optimization complete. GC freed {0} MB",
            String.format("%.1f", Math.max(0, freed) / 1024.0 / 1024.0));
    }

    /**
     * Enable or disable monitoring.
     */
    public static void setEnabled(boolean enabled) {
        MirageMonitor.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static int getMemoryWarningCount() {
        return memoryWarningCount;
    }
}
