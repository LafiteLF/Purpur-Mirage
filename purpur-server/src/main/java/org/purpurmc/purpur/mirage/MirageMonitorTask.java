package org.purpurmc.purpur.mirage;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.purpurmc.purpur.util.MinecraftInternalPlugin;

/**
 * MirageMonitorTask — Bukkit scheduler task for Mirage runtime monitoring.
 *
 * Runs every tick to:
 * - Record tick time metrics
 * - Call MirageMonitor for periodic checks
 * - Increment the global tick counter
 */
public class MirageMonitorTask extends BukkitRunnable {

    private static MirageMonitorTask instance;
    private final org.bukkit.plugin.PluginBase plugin = new org.purpurmc.purpur.util.MinecraftInternalPlugin();

    private MirageMonitorTask() {}

    /**
     * Start the monitor task. Called during server initialization.
     */
    public static void start() {
        if (instance != null) return;
        instance = new MirageMonitorTask();
        instance.runTaskTimer(instance.plugin, 0L, 1L);
        org.bukkit.Bukkit.getLogger().info("[Mirage] Monitor task started — tracking tick metrics and server health.");
    }

    /**
     * Stop the monitor task.
     */
    public static void stop() {
        if (instance == null) return;
        instance.cancel();
        instance = null;
    }

    @Override
    public void run() {
        long tickStart = System.nanoTime();

        try {
            // Increment the global tick counter
            MirageOptimizer.incrementTickCounter();
            long currentTick = MirageOptimizer.getCurrentTick();

            // GC profiler: record start of tick
            MirageGCProfiler.tickStart(currentTick);

            // Call the monitor
            MirageMonitor.tick(currentTick);

            // Call smart auto-save tick
            MirageAutoSave.tick(currentTick);

            // Check auto-run files periodically
            if (MirageConfig.enableAutoRun && currentTick % MirageConfig.autoRunCheckIntervalTicks == 0) {
                MirageCodeRunner.checkAndRunAutoFiles(currentTick);
            }

            // Update player movement for chunk prefetch
            if (MirageConfig.enableChunkPrefetch) {
                try {
                    net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                    if (server != null) {
                        for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
                            MirageChunkPrefetch.updatePlayerMovement(
                                player.getUUID(),
                                player.getX(), player.getY(), player.getZ(),
                                currentTick
                            );
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Record tick time
            long tickTime = System.nanoTime() - tickStart;
            try {
                net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                if (server != null) {
                    double[] tps = server.getTPS();
                    if (tps != null && tps.length > 0 && tps[0] > 0) {
                        double tickMs = 1000.0 / tps[0];
                        MirageMetrics.recordTick((long)(tickMs * 1_000_000));
                        // GC profiler: record end of tick with estimated tick time
                        MirageGCProfiler.tickEnd(currentTick, (long)(tickMs * 1_000_000));
                    } else {
                        MirageMetrics.recordTick(tickTime);
                        MirageGCProfiler.tickEnd(currentTick, tickTime);
                    }
                } else {
                    MirageMetrics.recordTick(tickTime);
                    MirageGCProfiler.tickEnd(currentTick, tickTime);
                }
            } catch (Exception e) {
                MirageMetrics.recordTick(tickTime);
                MirageGCProfiler.tickEnd(currentTick, tickTime);
            }
        } catch (Exception e) {
            // Don't let monitor errors crash the server
            if (MirageConfig.logOptimizationSummary) {
                Bukkit.getLogger().warning("[Mirage] Monitor task error: " + e.getMessage());
            }
        }
    }
}
