package dev.dockercraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <pre>
 * /dockercraft status|rebuild|reload|help        panel administration
 * /dockercraft docker ps|run|pull|...            docker CLI (also: /dockercraft ps, /dc ps)
 * /docker ps|images|pull|run|...                 alias, so it reads like the real docker CLI
 * </pre>
 * Works from in-game chat and from the server console.
 */
public final class DockerCraftCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ADMIN = List.of("status", "rebuild", "reload", "help");
    private static final Set<String> NEEDS_CONTAINER = Set.of(
            "start", "stop", "restart", "kill", "pause", "unpause", "rm", "logs", "exec", "inspect");

    private final DockerCraftPlugin plugin;
    private final DockerCli cli;

    public DockerCraftCommand(DockerCraftPlugin plugin, DockerCli cli) {
        this.plugin = plugin;
        this.cli = cli;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Bukkit already split on spaces; rejoin and tokenize again so "quoted args" work.
        List<String> tokens = DockerCli.tokenize(String.join(" ", args));

        // /docker <...> : everything is a docker command
        if (label.equalsIgnoreCase("docker")) {
            cli.execute(sender, tokens);
            return true;
        }

        // /dockercraft (no args) = status
        if (tokens.isEmpty()) {
            return admin(sender, "status");
        }
        String first = tokens.get(0).toLowerCase(Locale.ROOT);
        if (first.equals("help") && tokens.size() == 1) {
            cli.execute(sender, List.of("help"));
            return true;
        }
        if (ADMIN.contains(first)) {
            return admin(sender, first);
        }
        // /dockercraft docker ps  |  /dockercraft ps  |  /dc run -d nginx
        cli.execute(sender, tokens);
        return true;
    }

    private boolean admin(CommandSender sender, String sub) {
        if (!sender.hasPermission("dockercraft.admin")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return true;
        }
        switch (sub) {
            case "status" -> {
                List<ContainerInfo> last = plugin.lastRendered();
                int count = last == null ? 0 : last.size();
                sender.sendMessage(Component.text("DockerCraft: Reloaded: " + count + " containers on the panel.",
                        NamedTextColor.AQUA));
            }
            case "rebuild" -> {
                plugin.forceRebuild();
                sender.sendMessage(Component.text("DockerCraft: redrawing the panel...", NamedTextColor.GREEN));
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.forceRebuild();
                sender.sendMessage(Component.text(
                        "DockerCraft: configuration reloaded. (Changing docker.host or "
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
        boolean dockerAlias = alias.equalsIgnoreCase("docker");
        List<String> argList = new ArrayList<>(List.of(args));

        // For /dockercraft and /dc the first word can be an admin subcommand, "docker", or a docker command.
        if (!dockerAlias) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(ADMIN);
                options.add("docker");
                options.addAll(DockerCli.COMMANDS);
                return filter(options, args[0]);
            }
            if (args[0].equalsIgnoreCase("docker")) {
                argList.remove(0);
            }
        }
        if (argList.isEmpty()) {
            return List.of();
        }
        if (argList.size() == 1) {
            return filter(DockerCli.COMMANDS, argList.get(0));
        }

        String cmd = argList.get(0).toLowerCase(Locale.ROOT);
        String current = argList.get(argList.size() - 1);
        if (NEEDS_CONTAINER.contains(cmd) && !current.startsWith("-")) {
            List<ContainerInfo> last = plugin.lastRendered();
            if (last != null) {
                return filter(last.stream().map(ContainerInfo::name).toList(), current);
            }
        }
        if (cmd.equals("ps") && argList.size() == 2) {
            return filter(List.of("-a"), current);
        }
        if ((cmd.equals("network") || cmd.equals("volume")) && argList.size() == 2) {
            return filter(List.of("ls"), current);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).toList();
    }
}
