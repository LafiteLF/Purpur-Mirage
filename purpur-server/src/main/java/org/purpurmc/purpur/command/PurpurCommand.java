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
        this.usageMessage = "/mirage [reload | version | stats | optimize | monitor]";
        this.setPermission("bukkit.command.mirage");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) throws IllegalArgumentException {
        if (args.length == 1) {
            return Stream.of("reload", "version", "stats", "optimize", "monitor")
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

    private String formatTps(Object tps) {
        if (tps == null) return "20.00";
        double val = Double.parseDouble(tps.toString());
        NamedTextColor color = val >= 18 ? NamedTextColor.GREEN : val >= 15 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return String.format("%.2f", val);
    }
}
