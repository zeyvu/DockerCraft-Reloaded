package dev.dockercraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Replaces all the RCON-mode polling: the event carries the player, entity and action. */
public final class InteractionListener implements Listener {

    private final DockerCraftPlugin plugin;
    private final DockerService docker;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public InteractionListener(DockerCraftPlugin plugin, DockerService docker) {
        this.plugin = plugin;
        this.docker = docker;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction entity)) {
            return;
        }
        if (!entity.getScoreboardTags().contains(CardRenderer.TAG_BUTTON)) {
            return;
        }
        event.setCancelled(true);
        // The event fires once per hand: only handle the main hand.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getLong("click-cooldown-ms", 400);
        Long previous = lastClick.put(player.getUniqueId(), now);
        if (previous != null && now - previous < cooldown) {
            return;
        }

        String action = tagValue(entity, CardRenderer.TAG_ACTION);
        String shortId = tagValue(entity, CardRenderer.TAG_ID);
        if (action == null || shortId == null) {
            return;
        }

        boolean readOnly = action.equals("info") || action.equals("logs");
        if (readOnly && !player.hasPermission("dockercraft.view")) {
            player.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
            return;
        }
        if (!readOnly && !player.hasPermission("dockercraft.control")) {
            player.sendMessage(Component.text("You do not have permission to control containers.",
                    NamedTextColor.RED));
            return;
        }

        // Docker is blocking: run it off the main thread.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<ContainerInfo> found = docker.find(shortId);
                if (found.isEmpty()) {
                    plugin.sync(() -> player.sendMessage(
                            Component.text("Container not found (was it removed?).",
                                    NamedTextColor.RED)));
                    plugin.requestRefresh();
                    return;
                }
                ContainerInfo info = found.get();
                switch (action) {
                    case "info" -> {
                        List<Component> msg = infoMessage(info);
                        plugin.sync(() -> msg.forEach(m -> player.sendMessage(m)));
                    }
                    case "logs" -> {
                        List<String> lines = docker.logs(info.id(),
                                plugin.getConfig().getInt("logs.lines", 20));
                        List<Component> msg = logsMessage(info, lines);
                        plugin.sync(() -> msg.forEach(m -> player.sendMessage(m)));
                    }
                    case "start" -> control(player, info, "START", () -> docker.start(info.id()));
                    case "stop" -> control(player, info, "STOP", () -> docker.stop(info.id()));
                    case "restart" -> control(player, info, "RESTART", () -> docker.restart(info.id()));
                    default -> { }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error in action " + action + ": " + e.getMessage());
                plugin.sync(() -> player.sendMessage(
                        Component.text("DockerCraft: error running the action.", NamedTextColor.RED)));
            }
        });
    }

    private void control(Player player, ContainerInfo info, String label, Runnable operation) {
        try {
            operation.run();
            plugin.getLogger().info(player.getName() + " -> " + label + " " + info.name());
            plugin.sync(() -> player.sendMessage(Component.text(
                    "DockerCraft: " + label + " executed -> " + info.name(), NamedTextColor.GREEN)));
        } catch (Exception e) {
            plugin.getLogger().warning(label + " failed on " + info.name() + ": " + e.getMessage());
            plugin.sync(() -> player.sendMessage(Component.text(
                    "DockerCraft: error on " + label + " -> " + info.name(), NamedTextColor.RED)));
        }
        plugin.requestRefresh(); // show the new state without waiting for the next cycle
    }

    private static String tagValue(Entity entity, String prefix) {
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith(prefix)) {
                return tag.substring(prefix.length());
            }
        }
        return null;
    }

    // ------------------------------------------------------------ chat messages
    private static Component bar(NamedTextColor color) {
        return Component.text("══════════════════════════════", color);
    }

    private static List<Component> infoMessage(ContainerInfo info) {
        List<Component> out = new java.util.ArrayList<>();
        out.add(bar(NamedTextColor.DARK_AQUA));
        out.add(Component.text(" DOCKERCRAFT | ", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text(info.name(), NamedTextColor.WHITE, TextDecoration.BOLD)));
        out.add(Component.text("Status: ", NamedTextColor.GRAY)
                .append(Component.text(info.state().toUpperCase(), CardRenderer.stateColor(info.state()),
                        TextDecoration.BOLD)));
        out.add(Component.text("ID: ", NamedTextColor.GRAY)
                .append(Component.text(info.shortId(), NamedTextColor.WHITE)));
        out.add(Component.text("Image: ", NamedTextColor.GRAY)
                .append(Component.text(info.image(), NamedTextColor.WHITE)));
        addList(out, "Ports:", info.ports());
        addList(out, "Volumes:", info.volumes());
        out.add(bar(NamedTextColor.DARK_AQUA));
        return out;
    }

    private static void addList(List<Component> out, String title, List<String> items) {
        out.add(Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD));
        if (items.isEmpty()) {
            out.add(Component.text("  • None", NamedTextColor.GRAY));
        }
        for (String item : items) {
            out.add(Component.text("  • ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(item, NamedTextColor.WHITE)));
        }
    }

    private static List<Component> logsMessage(ContainerInfo info, List<String> lines) {
        List<Component> out = new java.util.ArrayList<>();
        out.add(bar(NamedTextColor.DARK_PURPLE));
        out.add(Component.text(" DOCKERCRAFT | LOGS | ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text(info.name(), NamedTextColor.WHITE, TextDecoration.BOLD)));
        out.add(Component.text(" Last " + lines.size() + " lines", NamedTextColor.GRAY));
        out.add(bar(NamedTextColor.DARK_PURPLE));
        if (lines.isEmpty()) {
            out.add(Component.text("No logs available.", NamedTextColor.GRAY));
        }
        for (String line : lines) {
            out.add(Component.text("│ ", NamedTextColor.DARK_PURPLE)
                    .append(Component.text(CardRenderer.truncate(line, 250), NamedTextColor.WHITE)));
        }
        out.add(bar(NamedTextColor.DARK_PURPLE));
        return out;
    }
}
