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

            // Call the monitor
            MirageMonitor.tick(currentTick);

            // Record tick time (we measure the overhead of the monitor itself)
            long tickTime = System.nanoTime() - tickStart;
            // We can't get the actual server tick time from here, but we can
            // estimate it using MinecraftServer's tick times
            try {
                net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
                if (server != null) {
                    // Use the server's tick time if available
                    double[] tps = server.getTPS();
                    // Estimate tick time from TPS: tick_ms = 1000 / (tps * 50)
                    if (tps != null && tps.length > 0 && tps[0] > 0) {
                        double tickMs = 1000.0 / tps[0];
                        MirageMetrics.recordTick((long)(tickMs * 1_000_000));
                    } else {
                        MirageMetrics.recordTick(tickTime);
                    }
                } else {
                    MirageMetrics.recordTick(tickTime);
                }
            } catch (Exception e) {
                MirageMetrics.recordTick(tickTime);
            }
        } catch (Exception e) {
            // Don't let monitor errors crash the server
            if (MirageConfig.logOptimizationSummary) {
                Bukkit.getLogger().warning("[Mirage] Monitor task error: " + e.getMessage());
            }
        }
    }
}
