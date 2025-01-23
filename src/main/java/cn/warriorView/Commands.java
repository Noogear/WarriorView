package cn.warriorView;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Commands implements CommandExecutor, TabCompleter {
    private final List<String> subcommands;
    private final Main plugin;

    public Commands(Main main) {
        this.plugin = main;
        subcommands = new ArrayList<>(List.of("reload"));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
        if (args.length == 0 || !subcommands.contains(args[0].toLowerCase())) {
            return false;
        }

        if (!sender.hasPermission("warriorview." + args[0])) {
            return false;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            plugin.reload(sender);
        }
        return true;
    }


    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, String[] args) {
        List<String> ret = new ArrayList<>();
        if (args.length == 1) {
            for (String subcmd : subcommands) {
                if (!sender.hasPermission("warriorview." + subcmd)) continue;
                ret.add(subcmd);
            }
            return StringUtil.copyPartialMatches(args[0].toLowerCase(), ret, new ArrayList<>());
        }

        return Collections.emptyList();
    }
}
