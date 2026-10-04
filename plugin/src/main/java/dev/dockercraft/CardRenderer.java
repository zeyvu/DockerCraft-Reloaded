package dev.dockercraft;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws each container's panel EXACTLY like the Python script:
 * wall, glass buttons with signs, Interaction entities and one armor stand
 * per line of information.
 *
 * All methods must be called from the main server thread.
 */
public final class CardRenderer {

    public static final String TAG_ENTITY = "dockercraft_entity";
    public static final String TAG_CARD = "dockercraft_card";
    public static final String TAG_BUTTON = "dockercraft_button";
    public static final String TAG_ACTION = "dockercraft_action_";
    public static final String TAG_ID = "dockercraft_id_";

    // Tags used by the project when it was still called McDocker (cleaned up on rebuild).
    private static final String LEGACY_TAG_ENTITY = "mcdocker_entity";
    private static final String LEGACY_TAG_CARD = "mcdocker_card";
    private static final String LEGACY_TAG_BUTTON = "mcdocker_button";

    private record Control(String action, String label, int offset, Material block, NamedTextColor color) { }

    // Same order, blocks and sign colors as CONTROLS in the Python script
    // (script offset 0..4 minus 2 => -2..2).
    private static final List<Control> CONTROLS = List.of(
            new Control("info", "INFO", -2, Material.BLUE_STAINED_GLASS, NamedTextColor.WHITE),
            new Control("start", "START", -1, Material.GREEN_STAINED_GLASS, NamedTextColor.WHITE),
            new Control("stop", "STOP", 0, Material.RED_STAINED_GLASS, NamedTextColor.WHITE),
            new Control("restart", "RESTART", 1, Material.YELLOW_STAINED_GLASS, NamedTextColor.BLACK),
            new Control("logs", "LOGS", 2, Material.PURPLE_STAINED_GLASS, NamedTextColor.WHITE));

    private record Line(String text, NamedTextColor color, boolean bold) { }

    private final DockerCraftPlugin plugin;
    private final File stateFile;

    public CardRenderer(DockerCraftPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "state.yml");
    }

    // ------------------------------------------------------------ configuration
    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private int baseX() { return cfg().getInt("world.base-x", 0); }
    private int baseY() { return cfg().getInt("world.base-y", -60); }
    private int baseZ() { return cfg().getInt("world.base-z", 0); }
    private int spacing() { return cfg().getInt("world.card-spacing", 7); }
    private int wallWidth() { return cfg().getInt("world.wall-width", 7); }
    private int wallHeight() { return cfg().getInt("world.wall-height", 8); }
    private double lineSpacing() { return cfg().getDouble("world.line-spacing", 0.30); }
    /** 0 = do not truncate (like the original script). */
    private int maxLineLength() { return cfg().getInt("world.max-line-length", 0); }

    private int centerX(int index) { return baseX() + index * spacing(); }
    private int wallStartX(int index) { return centerX(index) - wallWidth() / 2; }
    private int wallEndX(int index) { return wallStartX(index) + wallWidth() - 1; }

    // ------------------------------------------------------------ persistent state
    private int readBuiltSlots() {
        return YamlConfiguration.loadConfiguration(stateFile).getInt("built-slots", 0);
    }

    private void writeBuiltSlots(int slots) {
        YamlConfiguration state = new YamlConfiguration();
        state.set("built-slots", slots);
        try {
            plugin.getDataFolder().mkdirs();
            state.save(stateFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save state.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ public API
    public void render(List<ContainerInfo> containers) {
        World world = plugin.targetWorld();
        if (world == null) {
            throw new IllegalStateException("There is no loaded world to draw the panel in.");
        }
        int slots = Math.max(readBuiltSlots(), containers.size());
        prepareChunks(world, slots);
        clearSlots(world, slots);
        for (int i = 0; i < containers.size(); i++) {
            buildWall(world, i);
            buildButtons(world, i, containers.get(i));
            buildText(world, i, containers.get(i));
        }
        writeBuiltSlots(containers.size());
        plugin.getLogger().info("Panel redrawn: " + containers.size() + " containers in world '"
                + world.getName() + "' (positions cleared: " + slots + ").");
    }

    /** Removes the whole panel (blocks and entities). */
    public void clearAll() {
        World world = plugin.targetWorld();
        if (world == null) {
            return;
        }
        int slots = readBuiltSlots();
        prepareChunks(world, slots);
        clearSlots(world, slots);
        writeBuiltSlots(0);
    }

    public void releaseChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
    }

    // ------------------------------------------------------------ cleanup
    private void prepareChunks(World world, int slots) {
        if (slots <= 0) {
            return;
        }
        int minX = wallStartX(0);
        int maxX = wallEndX(slots - 1);
        boolean keep = cfg().getBoolean("world.keep-chunks-loaded", true);
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = baseZ() >> 4; cz <= (baseZ() + 2) >> 4; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz); // loads the chunk if needed
                if (keep) {
                    chunk.addPluginChunkTicket(plugin);
                }
            }
        }
    }

    private void clearSlots(World world, int slots) {
        for (Entity entity : world.getEntities()) {
            var tags = entity.getScoreboardTags();
            // TAG_ENTITY = this plugin's entities; the others = leftovers from the Python script.
            // The LEGACY_* tags belong to panels drawn before the rename to DockerCraft: Reloaded.
            if (tags.contains(TAG_ENTITY) || tags.contains(TAG_BUTTON) || tags.contains(TAG_CARD)
                    || tags.contains(LEGACY_TAG_ENTITY) || tags.contains(LEGACY_TAG_BUTTON)
                    || tags.contains(LEGACY_TAG_CARD)) {
                entity.remove();
            }
        }
        int y0 = baseY();
        int y1 = baseY() + wallHeight() - 1;
        for (int i = 0; i < slots; i++) {
            for (int x = wallStartX(i); x <= wallEndX(i); x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = baseZ(); z <= baseZ() + 2; z++) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ building
    private void buildWall(World world, int index) {
        Material material = Material.matchMaterial(cfg().getString("world.wall-block", "BLACK_CONCRETE"));
        if (material == null || !material.isBlock()) {
            material = Material.BLACK_CONCRETE;
        }
        for (int x = wallStartX(index); x <= wallEndX(index); x++) {
            for (int y = baseY(); y < baseY() + wallHeight(); y++) {
                world.getBlockAt(x, y, baseZ()).setType(material, false);
            }
        }
    }

    private void buildButtons(World world, int index, ContainerInfo info) {
        int y = baseY();
        int z = baseZ() + 1; // button row (the script's center_z)
        for (Control control : CONTROLS) {
            int x = centerX(index) + control.offset();

            world.getBlockAt(x, y, z).setType(control.block(), false);
            placeSign(world, x, y, z + 1, control);

            // Same as the script: x centered on the block (x + 0.5; the script uses an integer
            // that Minecraft centers), y + 0.25, z + 0.75, width 1, height 0.55.
            Location loc = new Location(world, x + 0.5, y + 0.25, z + 0.75);
            String shortId = info.shortId();
            world.spawn(loc, Interaction.class, entity -> {
                entity.setInteractionWidth(1.0f);
                entity.setInteractionHeight(0.55f);
                entity.setResponsive(true);
                entity.setPersistent(false);
                entity.addScoreboardTag(TAG_ENTITY);
                entity.addScoreboardTag(TAG_BUTTON);
                entity.addScoreboardTag(TAG_ACTION + control.action());
                entity.addScoreboardTag(TAG_ID + shortId);
            });
        }
    }

    private void placeSign(World world, int x, int y, int z, Control control) {
        Block block = world.getBlockAt(x, y, z);
        WallSign data = (WallSign) Material.OAK_WALL_SIGN.createBlockData();
        data.setFacing(BlockFace.SOUTH);
        block.setBlockData(data, false);
        if (block.getState() instanceof org.bukkit.block.Sign sign) {
            SignSide side = sign.getSide(Side.FRONT);
            // Text on the first line, bold, using the script's color.
            side.line(0, Component.text(control.label(), control.color(), TextDecoration.BOLD));
            sign.setWaxed(true);
            sign.update(true, false);
        }
    }

    /** One armor stand per line, same as the script's create_container_card(). */
    private void buildText(World world, int index, ContainerInfo info) {
        List<Line> lines = cardLines(info);
        // The script runs "summon ... x y z" with INTEGER x and z, and Minecraft centers those
        // coordinates on the block (+0.5). So its text is centered on the wall at
        // z = BASE_Z + 1.5, away from the wall face (which is at z = BASE_Z + 1.0).
        double x = centerX(index) + 0.5;
        double z = baseZ() + 1.5;
        double textBaseY = baseY() + 2;     // BUTTON_Y + 2
        double step = lineSpacing();        // LINE_SPACING = 0.30
        int max = maxLineLength();

        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            int reverse = lines.size() - 1 - i; // the first line is the highest
            double y = textBaseY + reverse * step;

            String text = max > 0 ? truncate(line.text(), max) : line.text();
            Component name = Component.text(text)
                    .color(line.color())
                    .decoration(TextDecoration.BOLD, line.bold());

            world.spawn(new Location(world, x, y, z), ArmorStand.class, stand -> {
                stand.customName(name);
                stand.setCustomNameVisible(true);
                stand.setVisible(false);
                stand.setInvulnerable(true);
                stand.setGravity(false);
                stand.setMarker(true);
                stand.setPersistent(false);
                stand.addScoreboardTag(TAG_ENTITY);
                stand.addScoreboardTag(TAG_CARD);
                stand.addScoreboardTag(TAG_ID + info.id());
            });
        }
    }

    // ------------------------------------------------------------ texts
    static NamedTextColor stateColor(String state) {
        return switch (state.toLowerCase(Locale.ROOT)) {
            case "running" -> NamedTextColor.GREEN;
            case "exited" -> NamedTextColor.RED;
            case "paused", "restarting" -> NamedTextColor.YELLOW;
            case "created" -> NamedTextColor.AQUA;
            case "dead" -> NamedTextColor.DARK_RED;
            default -> NamedTextColor.GRAY;
        };
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Same lines, order, texts and colors as the script's create_container_card(). */
    private static List<Line> cardLines(ContainerInfo info) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(info.name().toUpperCase(Locale.ROOT), NamedTextColor.AQUA, true));
        lines.add(new Line("━━━━━━━━━━━━━━━━━━━━", NamedTextColor.DARK_GRAY, false));
        lines.add(new Line("● " + info.state().toUpperCase(Locale.ROOT),
                stateColor(info.state()), true));
        lines.add(new Line("Image: " + info.image(), NamedTextColor.WHITE, false));
        lines.add(new Line("ID: " + info.shortId(), NamedTextColor.GRAY, false));
        lines.add(new Line("──────────────", NamedTextColor.DARK_GRAY, false));
        lines.add(new Line("Ports:", NamedTextColor.GOLD, true));
        if (info.ports().isEmpty()) {
            lines.add(new Line("  None", NamedTextColor.GRAY, false));
        }
        for (String port : info.ports()) {
            lines.add(new Line("  " + port, NamedTextColor.WHITE, false));
        }
        lines.add(new Line("Volumes:", NamedTextColor.GOLD, true));
        if (info.volumes().isEmpty()) {
            lines.add(new Line("  None", NamedTextColor.GRAY, false));
        }
        for (String volume : info.volumes()) {
            lines.add(new Line("  " + volume, NamedTextColor.WHITE, false));
        }
        return lines;
    }
}
