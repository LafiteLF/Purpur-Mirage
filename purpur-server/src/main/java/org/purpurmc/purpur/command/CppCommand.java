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
 * CppCommand — /c++ command for executing C++ code.
 *
 * Usage:
 * /c++ <code>           — Execute inline C++ code (transpiled to Java)
 * /c++ file <filename>  — Execute a C++ file from ./c++/ directory
 * /c++ stats            — Show code runner statistics
 *
 * Note: C++ code is transpiled to Java and compiled with the JDK's
 * built-in compiler. No external C++ compiler required.
 */
public class CppCommand extends Command {

    public CppCommand(String name) {
        super(name);
        this.description = "Execute C++ code (transpiled to Java)";
        this.usageMessage = "/c++ <code> | /c++ file <name> | /c++ stats";
        this.setPermission("mirage.command.cpp");
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
            sender.sendMessage(Component.text("Usage: /c++ <code>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("       /c++ file <filename>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("       /c++ stats", NamedTextColor.YELLOW));
            return false;
        }

        // Handle subcommands
        if (args[0].equalsIgnoreCase("stats")) {
            showStats(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("file")) {
            if (args.length < 2) {
                sender.sendMessage(Component.text("Usage: /c++ file <filename>", NamedTextColor.RED));
                return false;
            }
            String filename = args[1];
            sender.sendMessage(Component.text("Executing C++ file: " + filename, NamedTextColor.YELLOW));
            java.io.File file = new java.io.File("c++", filename);
            if (!file.exists()) {
                file = new java.io.File("c++", filename + ".cpp");
            }
            if (!file.exists()) {
                sender.sendMessage(Component.text("File not found: " + filename, NamedTextColor.RED));
                return false;
            }
            MirageCodeRunner.runCppFile(file);
            sender.sendMessage(Component.text("C++ file execution started. Check console for output.", NamedTextColor.GREEN));
            return true;
        }

        // Join all args as code
        String code = String.join(" ", args);

        sender.sendMessage(Component.text("Executing C++ (transpiling to Java)...", NamedTextColor.YELLOW));

        MirageCodeRunner.executeCpp(code, output -> {
            String[] lines = output.split("\n");
            for (String line : lines) {
                sender.sendMessage(Component.text(line, NamedTextColor.WHITE));
            }
        });

        return true;
    }

    private void showStats(CommandSender sender) {
        var stats = MirageCodeRunner.getStats();
        sender.sendMessage(Component.text("======= C++ Code Runner =======", NamedTextColor.GOLD, TextDecoration.BOLD));
        sender.sendMessage(Component.text("C++ Mode: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("cpp_mode")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Total Runs: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("cpp_runs")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Auto Runs: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("auto_runs")), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text("Failures: ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(stats.get("failures")), NamedTextColor.RED)));
        sender.sendMessage(Component.text("===============================", NamedTextColor.GOLD));
    }
}
