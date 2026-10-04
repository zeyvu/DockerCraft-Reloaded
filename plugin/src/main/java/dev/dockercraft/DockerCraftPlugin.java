package dev.dockercraft;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class DockerCraftPlugin extends JavaPlugin {

    private DockerService docker;
    private DockerCli cli;
    private CardRenderer renderer;

    /** Last state drawn successfully (or already scheduled to be drawn). */
    private final AtomicReference<List<ContainerInfo>> rendered = new AtomicReference<>();
    private volatile long lastDockerWarning = 0;
    private volatile boolean worldWarned = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        renderer = new CardRenderer(this);

        try {
            docker = new DockerService(getConfig().getString("docker.host", "unix:///var/run/docker.sock"));
        } catch (Exception e) {
            getLogger().severe("Could not create the Docker client: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new InteractionListener(this, docker), this);

        cli = new DockerCli(this, docker);
        DockerCraftCommand command = new DockerCraftCommand(this, cli);
        var pluginCommand = getCommand("dockercraft");
        if (pluginCommand != null) {
            // The aliases (/dc, /docker) declared in plugin.yml share this same executor.
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        startSyncTask();
        getLogger().info("DockerCraft: Reloaded enabled.");
    }

    private void startSyncTask() {
        long period = Math.max(1, getConfig().getLong("sync-interval-seconds", 5)) * 20L;
        // Docker is queried on an async thread; the world is only touched on the main thread.
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::syncTick, 40L, period);
    }

    private void syncTick() {
        try {
            List<ContainerInfo> snapshot = docker.snapshot();
            if (snapshot.equals(rendered.get())) {
                return;
            }
            rendered.set(snapshot);
            getLogger().info("Docker change detected (" + snapshot.size() + " containers); redrawing panel...");
            getServer().getScheduler().runTask(this, () -> {
                try {
                    renderer.render(snapshot);
                } catch (Throwable t) {
                    getLogger().log(Level.SEVERE, "Error redrawing the panel (will retry on the next cycle)", t);
                    rendered.compareAndSet(snapshot, null); // force a retry
                }
            });
        } catch (Throwable e) {
            long now = System.currentTimeMillis();
            if (now - lastDockerWarning > 60_000) { // do not flood the console
                lastDockerWarning = now;
                getLogger().warning("Error reading Docker: " + e);
            }
        }
    }

    /** Requests an immediate check (e.g. after a START/STOP). Safe from any thread. */
    public void requestRefresh() {
        if (!isEnabled()) {
            return;
        }
        getServer().getScheduler().runTaskAsynchronously(this, this::syncTick);
    }

    /** Forces a redraw even if nothing changed. */
    public void forceRebuild() {
        rendered.set(null);
        requestRefresh();
    }

    /** Runs something on the main thread (safe from any thread). */
    public void sync(Runnable task) {
        if (isEnabled()) {
            getServer().getScheduler().runTask(this, task);
        }
    }

    /**
     * World where the panel is drawn. Accepts the name ("world"), the key
     * ("minecraft:overworld") or empty to use the first normal world.
     * If the configured one does not exist, warns (once) and uses the default world.
     */
    public World targetWorld() {
        String name = getConfig().getString("world.name", "");
        if (name != null && !name.isBlank()) {
            World found = Bukkit.getWorld(name);
            if (found == null && name.contains(":")) {
                NamespacedKey key = NamespacedKey.fromString(name);
                if (key != null) {
                    found = Bukkit.getWorld(key);
                }
            }
            if (found == null) {
                for (World w : Bukkit.getWorlds()) {
                    if (w.getName().equalsIgnoreCase(name) || w.getKey().toString().equalsIgnoreCase(name)) {
                        found = w;
                        break;
                    }
                }
            }
            if (found != null) {
                return found;
            }
            if (!worldWarned) {
                worldWarned = true;
                StringBuilder sb = new StringBuilder();
                for (World w : Bukkit.getWorlds()) {
                    sb.append(w.getName()).append(" (").append(w.getKey()).append(") ");
                }
                getLogger().warning("World '" + name + "' does not exist. Available worlds: " + sb
                        + "- using the default normal world.");
            }
        }
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == World.Environment.NORMAL) {
                return w;
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }

    public DockerService docker() {
        return docker;
    }

    public List<ContainerInfo> lastRendered() {
        return rendered.get();
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        if (renderer != null) {
            if (getConfig().getBoolean("clear-on-disable", false)) {
                renderer.clearAll();
            }
            renderer.releaseChunks();
        }
        if (docker != null) {
            docker.close();
        }
    }
}
