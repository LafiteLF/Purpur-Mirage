package org.purpurmc.purpur.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.purpurmc.purpur.PurpurConfig;
import org.purpurmc.purpur.mirage.MirageMetrics;
import org.purpurmc.purpur.mirage.MirageMonitor;
import org.purpurmc.purpur.mirage.MirageEntityLimiter;
import org.purpurmc.purpur.mirage.MirageGCProfiler;
import org.purpurmc.purpur.mirage.MirageChunkPrefetch;
import org.purpurmc.purpur.mirage.MirageAutoSave;
import org.purpurmc.purpur.mirage.MirageWarmup;
import org.purpurmc.purpur.mirage.MirageCodeRunner;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PurpurCommand extends Command {
    public PurpurCommand(String name) {
        super(name);
        this.description = "Mirage related commands";
        this.usageMessage = "/mirage [reload | version | stats | optimize | monitor | gc | prefetch | autosave | warmup]";
        this.setPermission("bukkit.command.mirage");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) throws IllegalArgumentException {
        if (args.length == 1) {
            return Stream.of("reload", "version", "stats", "optimize", "monitor", "gc", "prefetch", "autosave", "warmup", "code")
                    .filter(arg -> arg.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("monitor")) {
            return Stream.of("on", "off", "status")
                    .filter(arg -> arg.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) return true;

        if (args.length < 1) {
            sendHelp(sender);
            return false;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "version" -> handleVersion(sender, commandLabel);
            case "stats" -> handleStats(sender);
            case "optimize" -> handleOptimize(sender);
            case "monitor" -> handleMonitor(sender, args);
            case "gc" -> handleGcReport(sender);
            case "prefetch" -> handlePrefetchStats(sender);
            case "autosave" -> handleAutoSaveStatus(sender);
            case "warmup" -> handleWarmupStatus(sender);
            case "code" -> handleCodeRunnerStats(sender);
            default -> {
                sendHelp(sender);
                return false;
            }
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== Mirage Commands ===", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("/mirage reload", NamedTextColor.AQUA).append(Component.text(" - Reload mirage.yml configuration", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage version", NamedTextColor.AQUA).append(Component.text(" - Show server version info", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage stats", NamedTextColor.AQUA).append(Component.text(" - Show optimization statistics", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage optimize", NamedTextColor.AQUA).append(Component.text(" - Run immediate optimization (clear caches + GC)", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage monitor [on|off|status]", NamedTextColor.AQUA).append(Component.text(" - Control runtime monitor", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage gc", NamedTextColor.AQUA).append(Component.text(" - Show GC profiler report", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage prefetch", NamedTextColor.AQUA).append(Component.text(" - Show chunk prefetch statistics", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage autosave", NamedTextColor.AQUA).append(Component.text(" - Show smart auto-save status", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage warmup", NamedTextColor.AQUA).append(Component.text(" - Show JVM warmup status", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/mirage code", NamedTextColor.AQUA).append(Component.text(" - Show code runner statistics", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/python <code>", NamedTextColor.AQUA).append(Component.text(" - Execute Python code", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/c++ <code>", NamedTextColor.AQUA).append(Component.text(" - Execute C++ code (transpiled to Java)", NamedTextColor.GRAY)));
    }

    private void handleReload(CommandSender sender) {
        Command.broadcastCommandMessage(sender, Component.text("Please note that this command is not supported and may cause issues", NamedTextColor.RED));
        Command.broadcastCommandMessage(sender, Component.text("If you encounter any issues please use the /stop command to restart your server.", NamedTextColor.RED));

        MinecraftServer console = MinecraftServer.getServer();
        PurpurConfig.init((File) console.options.valueOf("mirage-settings"));
        for (ServerLevel level : console.getAllLevels()) {
            level.purpurConfig.init();
            level.resetBreedingCooldowns();
        }
        console.server.reloadCount++;

        Command.broadcastCommandMessage(sender, Component.text("Mirage config reload complete.", NamedTextColor.GREEN));
    }

    private void handleVersion(CommandSender sender, String commandLabel) {
        Command verCmd = org.bukkit.Bukkit.getServer().getCommandMap().getCommand("version");
        if (verCmd != null) {
            verCmd.execute(sender, commandLabel, new String[0]);
        }
    }

    private void handleStats(CommandSender sender) {
        Map<String, Object> stats = MirageMetrics.getAllStats();
        int health = MirageMetrics.getHealthRating();
        NamedTextColor healthColor = health >= 80 ? NamedTextColor.GREEN : health >= 50 ? NamedTextColor.YELLOW : NamedTextColor.RED;

        sender.sendMessage(Component.text("======= Mirage Stats =======", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Health Rating: ", NamedTextColor.GRAY)
            .append(Component.text(health + "/100", healthColor, TextDecoration.BOLD)));

        sender.sendMessage(Component.text("TPS: ", NamedTextColor.GRAY)
            .append(Component.text(formatTps(stats.get("tps_1m")), NamedTextColor.GREEN))
            .append(Component.text(" (1m)  ", NamedTextColor.DARK_GRAY))
            .append(Component.text(formatTps(stats.get("tps_5m")), NamedTextColor.GREEN))
            .append(Component.text(" (5m)  ", NamedTextColor.DARK_GRAY))
            .append(Component.text(formatTps(stats.get("tps_15m")), NamedTextColor.GREEN))
            .append(Component.text(" (15m)", NamedTextColor.DARK_GRAY)));

        sender.sendMessage(Component.text("Avg Tick: ", NamedTextColor.GRAY)
            .append(Component.text(stats.get("avg_tick_ms") + " ms", NamedTextColor.AQUA)));

        MirageMetrics.MemoryStats mem = MirageMetrics.getMemoryStats();
        NamedTextColor memColor = mem.usedPercent() > 85 ? NamedTextColor.RED : mem.usedPercent() > 70 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
        sender.sendMessage(Component.text("Memory: ", NamedTextColor.GRAY)
            .append(Component.text(String.format("%.0f/%.0f MB (%.1f%%)", mem.usedMB(), mem.maxMB(), mem.usedPercent()), memColor)));

        sender.sendMessage(Component.text("Entities: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("entities_total")), NamedTextColor.AQUA))
            .append(Component.text("  Chunks: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("chunks_loaded")), NamedTextColor.AQUA)));

        sender.sendMessage(Component.text("AI Skipped: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("entity_ai_skipped")), NamedTextColor.AQUA))
            .append(Component.text("  Chunks Skipped: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("chunks_skipped")), NamedTextColor.AQUA)));

        sender.sendMessage(Component.text("Packets Reduced: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("packets_reduced")), NamedTextColor.AQUA))
            .append(Component.text("  Entities Culled: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("entities_culled")), NamedTextColor.AQUA)));

        sender.sendMessage(Component.text("Memory Saved: ", NamedTextColor.GRAY)
            .append(Component.text(stats.get("memory_saved_mb") + " MB", NamedTextColor.GREEN))
            .append(Component.text("  View Distance: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("adaptive_view_distance")), NamedTextColor.AQUA)));

        // Entity limiter summary
        if (MirageEntityLimiter.getSummary() != null && !MirageEntityLimiter.getSummary().isEmpty()) {
            sender.sendMessage(Component.text("--- Entity Limits ---", NamedTextColor.DARK_AQUA));
            sender.sendMessage(Component.text(MirageEntityLimiter.getSummary(), NamedTextColor.GRAY));
        }

        // GC profiler summary
        if (org.purpurmc.purpur.mirage.MirageConfig.enableGcProfiler) {
            sender.sendMessage(Component.text("--- GC Profiler ---", NamedTextColor.DARK_AQUA));
            sender.sendMessage(Component.text("Max Pause: ", NamedTextColor.GRAY)
                .append(Component.text(MirageGCProfiler.getRecentAvgPauseMs() + " ms (avg recent)", NamedTextColor.AQUA)));
        }

        // Chunk prefetch summary
        if (org.purpurmc.purpur.mirage.MirageConfig.enableChunkPrefetch) {
            Map<String, Object> prefetchStats = MirageChunkPrefetch.getStats();
            sender.sendMessage(Component.text("--- Chunk Prefetch ---", NamedTextColor.DARK_AQUA));
            sender.sendMessage(Component.text("Hit Rate: ", NamedTextColor.GRAY)
                .append(Component.text(String.valueOf(prefetchStats.get("prefetch_hit_rate")), NamedTextColor.AQUA))
                .append(Component.text("  Pending: ", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(prefetchStats.get("prefetch_pending_chunks")), NamedTextColor.AQUA)));
        }

        // Auto-save summary
        if (org.purpurmc.purpur.mirage.MirageConfig.enableSmartAutoSave) {
            sender.sendMessage(Component.text("--- AutoSave ---", NamedTextColor.DARK_AQUA));
            sender.sendMessage(Component.text("Queue: ", NamedTextColor.GRAY)
                .append(Component.text(MirageAutoSave.getQueueSize() + "  Total: ", NamedTextColor.AQUA))
                .append(Component.text(String.valueOf(MirageAutoSave.getTotalSavesProcessed()), NamedTextColor.AQUA)));
        }

        // Warmup status
        sender.sendMessage(Component.text("Warmup: ", NamedTextColor.GRAY)
            .append(Component.text(MirageWarmup.getInfoString(), NamedTextColor.AQUA)));

        sender.sendMessage(Component.text("Memory Warnings: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(MirageMonitor.getMemoryWarningCount()), NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("============================", NamedTextColor.GOLD));
    }

    private void handleOptimize(CommandSender sender) {
        Command.broadcastCommandMessage(sender, Component.text("Running Mirage optimization...", NamedTextColor.YELLOW));

        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        MirageMonitor.runOptimization();
        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long freedMB = Math.max(0, memBefore - memAfter) / (1024 * 1024);

        Command.broadcastCommandMessage(sender, Component.text("Optimization complete! Freed " + freedMB + " MB of memory.", NamedTextColor.GREEN));
        Command.broadcastCommandMessage(sender, Component.text("Counters reset. Use /mirage stats to see fresh metrics.", NamedTextColor.GRAY));

        MirageMetrics.resetCounters();
    }

    private void handleMonitor(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Monitor status: ", NamedTextColor.GRAY)
                .append(Component.text(MirageMonitor.isEnabled() ? "ENABLED" : "DISABLED",
                    MirageMonitor.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "on" -> {
                MirageMonitor.setEnabled(true);
                Command.broadcastCommandMessage(sender, Component.text("Mirage monitor enabled.", NamedTextColor.GREEN));
            }
            case "off" -> {
                MirageMonitor.setEnabled(false);
                Command.broadcastCommandMessage(sender, Component.text("Mirage monitor disabled.", NamedTextColor.YELLOW));
            }
            case "status" -> {
                sender.sendMessage(Component.text("Monitor: ", NamedTextColor.GRAY)
                    .append(Component.text(MirageMonitor.isEnabled() ? "ENABLED" : "DISABLED",
                        MirageMonitor.isEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED)));
                sender.sendMessage(Component.text("Memory Warnings: " + MirageMonitor.getMemoryWarningCount(), NamedTextColor.GRAY));
            }
            default -> sender.sendMessage(Component.text("Usage: /mirage monitor [on|off|status]", NamedTextColor.RED));
        }
    }

    private void handleGcReport(CommandSender sender) {
        Map<String, Object> report = MirageGCProfiler.getReport();

        sender.sendMessage(Component.text("======= Mirage GC Report =======", NamedTextColor.GOLD, TextDecoration.BOLD));

        // GC algorithms
        Object algorithms = report.get("gc_algorithms");
        if (algorithms instanceof List<?> list) {
            for (Object info : list) {
                sender.sendMessage(Component.text("  " + info, NamedTextColor.GRAY));
            }
        }

        long maxPause = (long) report.get("gc_max_pause_ms");
        NamedTextColor maxColor = maxPause > 100 ? NamedTextColor.RED : maxPause > 50 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;

        sender.sendMessage(Component.text("Total Collections: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(report.get("gc_total_collections")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Total GC Time: ", NamedTextColor.GRAY)
            .append(Component.text(report.get("gc_total_time_ms") + " ms", NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Max Pause: ", NamedTextColor.GRAY)
            .append(Component.text(maxPause + " ms", maxColor)));
        sender.sendMessage(Component.text("Avg Pause: ", NamedTextColor.GRAY)
            .append(Component.text(report.get("gc_avg_pause_ms") + " ms", NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Since Last Report: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(report.get("gc_since_last_report")), NamedTextColor.AQUA)));

        double recentAvg = MirageGCProfiler.getRecentAvgPauseMs();
        NamedTextColor recentColor = recentAvg > 50 ? NamedTextColor.RED : recentAvg > 20 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
        sender.sendMessage(Component.text("Recent Avg Pause: ", NamedTextColor.GRAY)
            .append(Component.text(String.format("%.2f ms", recentAvg), recentColor)));

        sender.sendMessage(Component.text("Heap: ", NamedTextColor.GRAY)
            .append(Component.text(report.get("heap_used_mb") + " / " + report.get("heap_committed_mb") + " MB (max " + report.get("heap_max_mb") + " MB)", NamedTextColor.AQUA)));

        sender.sendMessage(Component.text("================================", NamedTextColor.GOLD));

        MirageGCProfiler.resetReport();
    }

    private void handlePrefetchStats(CommandSender sender) {
        Map<String, Object> stats = MirageChunkPrefetch.getStats();

        sender.sendMessage(Component.text("======= Mirage Chunk Prefetch =======", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Total Prefetched: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("prefetch_total")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Hits: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("prefetch_hits")), NamedTextColor.GREEN))
            .append(Component.text("  Misses: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("prefetch_misses")), NamedTextColor.YELLOW))
            .append(Component.text("  Evicted: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("prefetch_evicted")), NamedTextColor.RED)));
        sender.sendMessage(Component.text("Hit Rate: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("prefetch_hit_rate")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Tracked Players: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("prefetch_tracked_players")), NamedTextColor.AQUA))
            .append(Component.text("  Pending Chunks: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("prefetch_pending_chunks")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("======================================", NamedTextColor.GOLD));
    }

    private void handleAutoSaveStatus(CommandSender sender) {
        sender.sendMessage(Component.text("======= Mirage AutoSave =======", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Status: ", NamedTextColor.GRAY)
            .append(Component.text(org.purpurmc.purpur.mirage.MirageConfig.enableSmartAutoSave ? "ENABLED" : "DISABLED",
                org.purpurmc.purpur.mirage.MirageConfig.enableSmartAutoSave ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("Queue: ", NamedTextColor.GRAY)
            .append(Component.text(MirageAutoSave.getQueueSize() + " chunks", NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Total Saved: ", NamedTextColor.GRAY)
            .append(Component.text(MirageAutoSave.getTotalSavesProcessed() + " chunks", NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Avg Save Time: ", NamedTextColor.GRAY)
            .append(Component.text(String.format("%.3f ms", MirageAutoSave.getAvgSaveTimeMs()), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("================================", NamedTextColor.GOLD));
    }

    private void handleWarmupStatus(CommandSender sender) {
        sender.sendMessage(Component.text("======= Mirage Warmup =======", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Status: ", NamedTextColor.GRAY)
            .append(Component.text(MirageWarmup.isWarmedUp() ? "COMPLETE" : "NOT STARTED",
                MirageWarmup.isWarmedUp() ? NamedTextColor.GREEN : NamedTextColor.YELLOW)));
        if (MirageWarmup.isWarmedUp()) {
            sender.sendMessage(Component.text("Warmup Time: ", NamedTextColor.GRAY)
                .append(Component.text(MirageWarmup.getWarmupTimeMs() + " ms", NamedTextColor.AQUA)));
        }
        sender.sendMessage(Component.text("==============================", NamedTextColor.GOLD));
    }

    private void handleCodeRunnerStats(CommandSender sender) {
        Map<String, Object> stats = MirageCodeRunner.getStats();
        sender.sendMessage(Component.text("======= Mirage Code Runner =======", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Python Mode: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("python_mode")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("C++ Mode: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("cpp_mode")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Python Runs: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("python_runs")), NamedTextColor.AQUA))
            .append(Component.text("  C++ Runs: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("cpp_runs")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Auto Runs: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("auto_runs")), NamedTextColor.AQUA))
            .append(Component.text("  Failures: ", NamedTextColor.GRAY))
            .append(Component.text(String.valueOf(stats.get("failures")), NamedTextColor.RED)));
        sender.sendMessage(Component.text("Auto-Run: ", NamedTextColor.GRAY)
            .append(Component.text(Boolean.TRUE.equals(stats.get("auto_run_enabled")) ? "ENABLED" : "DISABLED",
                Boolean.TRUE.equals(stats.get("auto_run_enabled")) ? NamedTextColor.GREEN : NamedTextColor.RED)));
        sender.sendMessage(Component.text("Directories: ", NamedTextColor.GRAY)
            .append(Component.text("./python/main.py  ./c++/main.cpp", NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("==================================", NamedTextColor.GOLD));
    }

    private String formatTps(Object tps) {
        if (tps == null) return "20.00";
        double val = Double.parseDouble(tps.toString());
        NamedTextColor color = val >= 18 ? NamedTextColor.GREEN : val >= 15 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return String.format("%.2f", val);
    }
}
