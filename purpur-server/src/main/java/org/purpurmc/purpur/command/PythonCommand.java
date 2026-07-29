package org.purpurmc.purpur.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.purpurmc.purpur.mirage.MirageCodeRunner;
import org.purpurmc.purpur.mirage.MirageConfig;

import java.util.Collections;
import java.util.List;

/**
 * PythonCommand — /python command for executing Python code.
 *
 * Usage:
 * /python <code>           — Execute inline Python code
 * /python file <filename>  — Execute a Python file from ./python/ directory
 * /python stats            — Show code runner statistics
 */
public class PythonCommand extends Command {

    public PythonCommand(String name) {
        super(name);
        this.description = "Execute Python code";
        this.usageMessage = "/python <code> | /python file <name> | /python stats";
        this.setPermission("mirage.command.python");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) throws IllegalArgumentException {
        if (args.length == 1) {
            return java.util.stream.Stream.of("file", "stats")
                    .filter(arg -> arg.startsWith(args[0].toLowerCase()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) return true;

        if (!MirageConfig.enableCodeRunner) {
            sender.sendMessage(Component.text("Code runner is disabled in mirage.yml.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /python <code>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("       /python file <filename>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("       /python stats", NamedTextColor.YELLOW));
            return false;
        }

        // Handle subcommands
        if (args[0].equalsIgnoreCase("stats")) {
            showStats(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("file")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: /python file <filename>", NamedTextColor.RED));
                return false;
            }
            String filename = args[1];
            sender.sendMessage(Component.text("Executing Python file: " + filename, NamedTextColor.YELLOW));
            // Read file and execute
            java.io.File file = new java.io.File("python", filename);
            if (!file.exists()) {
                file = new java.io.File("python", filename + ".py");
            }
            if (!file.exists()) {
                sender.sendMessage(Component.text("File not found: " + filename, NamedTextColor.RED));
                return false;
            }
            MirageCodeRunner.runPythonFile(file);
            sender.sendMessage(Component.text("Python file execution started. Check console for output.", NamedTextColor.GREEN));
            return true;
        }

        // Join all args as code
        String code = String.join(" ", args);

        sender.sendMessage(Component.text("Executing Python...", NamedTextColor.YELLOW));

        MirageCodeRunner.executePython(code, output -> {
            // Send output back to sender
            String[] lines = output.split("\n");
            for (String line : lines) {
                sender.sendMessage(Component.text(line, NamedTextColor.WHITE));
            }
        });

        return true;
    }

    private void showStats(CommandSender sender) {
        var stats = MirageCodeRunner.getStats();
        sender.sendMessage(Component.text("======= Python Code Runner =======", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("Python Mode: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("python_mode")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Total Runs: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("python_runs")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Auto Runs: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("auto_runs")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Failures: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("failures")), NamedTextColor.RED)));
        sender.sendMessage(Component.text("===================================", NamedTextColor.GOLD));
    }
}
