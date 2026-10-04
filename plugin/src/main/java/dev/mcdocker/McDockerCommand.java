package dev.mcdocker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

/** /mcdocker <status|rebuild|reload> */
public final class McDockerCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("status", "rebuild", "reload");

    private final McDockerPlugin plugin;

    public McDockerCommand(McDockerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "status" -> {
                List<ContainerInfo> last = plugin.lastRendered();
                int count = last == null ? 0 : last.size();
                sender.sendMessage(Component.text("McDocker: " + count + " containers on the panel.",
                        NamedTextColor.AQUA));
            }
            case "rebuild" -> {
                plugin.forceRebuild();
                sender.sendMessage(Component.text("McDocker: redrawing the panel...", NamedTextColor.GREEN));
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.forceRebuild();
                sender.sendMessage(Component.text(
                        "McDocker: configuration reloaded. (Changing docker.host or "
                                + "sync-interval-seconds requires a server restart.)",
                        NamedTextColor.GREEN));
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
