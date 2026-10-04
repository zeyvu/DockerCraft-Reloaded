package dev.dockercraft;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Version;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A small docker CLI: {@code docker ps}, {@code docker images}, {@code docker pull X},
 * {@code docker run ...}, {@code docker logs X}, ...
 *
 * It never spawns a shell and never calls the docker binary: every command is translated to a
 * call to the Docker Engine API through {@link DockerService}, so there is no shell injection.
 * Works from in-game chat and from the server console.
 */
public final class DockerCli {

    /** Everything the interpreter understands (after normalisation), used for tab-completion and help. */
    public static final List<String> COMMANDS = List.of(
            "ps", "images", "pull", "run", "start", "stop", "restart", "kill", "pause", "unpause",
            "rm", "rmi", "logs", "exec", "inspect", "network", "volume", "version", "info", "help");

    private static final Set<String> VIEW = Set.of("ps", "images", "logs", "inspect", "network", "volume",
            "version", "info", "help");
    private static final Set<String> CONTROL = Set.of("start", "stop", "restart", "kill", "pause", "unpause");
    private static final Set<String> PULL = Set.of("pull", "rmi");
    private static final Set<String> RUN = Set.of("run", "exec", "rm");

    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[A-Za-z]:.*"); // a named volume needs 2+ chars, so "C:" is always a drive

    private final DockerCraftPlugin plugin;
    private final DockerService docker;

    public DockerCli(DockerCraftPlugin plugin, DockerService docker) {
        this.plugin = plugin;
        this.docker = docker;
    }

    // ------------------------------------------------------------ entry point

    /**
     * Parses and runs a docker command. Returns immediately: Docker is blocking, so the real work
     * happens on an async thread and the answer is sent back to the sender on the main thread.
     *
     * @param tokens the command WITHOUT the leading "docker" (e.g. ["run", "-d", "nginx"])
     */
    public void execute(CommandSender sender, List<String> tokens) {
        if (!plugin.getConfig().getBoolean("commands.enabled", true)) {
            reply(sender, List.of(error("Docker commands are disabled (commands.enabled: false).")));
            return;
        }

        List<String> args = normalize(tokens);
        if (args.isEmpty() || args.get(0).equals("help")) {
            reply(sender, helpLines());
            return;
        }
        String cmd = args.get(0);
        if (!COMMANDS.contains(cmd)) {
            reply(sender, List.of(error("docker: '" + cmd + "' is not a supported command. Try: docker help")));
            return;
        }

        String needed = requiredPermission(cmd);
        if (!sender.hasPermission(needed)) {
            reply(sender, List.of(error("You do not have permission for 'docker " + cmd + "' (" + needed + ").")));
            return;
        }

        if (!VIEW.contains(cmd)) {
            plugin.getLogger().info(sender.getName() + " ran: docker " + String.join(" ", tokens));
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Component> out;
            try {
                out = run(cmd, args.subList(1, args.size()));
            } catch (UsageException e) {
                out = List.of(error(e.getMessage()));
            } catch (Exception e) {
                plugin.getLogger().warning("docker " + cmd + " failed: " + e);
                out = List.of(error("docker " + cmd + ": " + cleanMessage(e)));
            }
            List<Component> finalOut = out;
            plugin.sync(() -> reply(sender, finalOut));
            if (!VIEW.contains(cmd)) {
                plugin.requestRefresh(); // redraw the panel without waiting for the next cycle
            }
        });
    }

    /** Permission node needed for a (normalised) command. */
    public static String requiredPermission(String cmd) {
        if (PULL.contains(cmd)) {
            return "dockercraft.docker.pull";
        }
        if (RUN.contains(cmd)) {
            return "dockercraft.docker.run";
        }
        if (CONTROL.contains(cmd)) {
            return "dockercraft.control";
        }
        return "dockercraft.view";
    }

    // ------------------------------------------------------------ parsing helpers

    /** Splits a command line honouring "double" and 'single' quotes. */
    public static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else if (ch == '\\' && quote == '"' && i + 1 < line.length()
                        && (line.charAt(i + 1) == '"' || line.charAt(i + 1) == '\\')) {
                    cur.append(line.charAt(++i));
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
                has = true;
            } else if (Character.isWhitespace(ch)) {
                if (has || cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    has = false;
                }
            } else {
                cur.append(ch);
            }
        }
        if (has || cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * Maps the many spellings of the docker CLI to one canonical form:
     * "container ls" -> ps, "images ls" / "image ls" -> images, "image rm" -> rmi, "container rm" -> rm, ...
     */
    static List<String> normalize(List<String> tokens) {
        List<String> t = new ArrayList<>(tokens);
        if (!t.isEmpty() && t.get(0).equalsIgnoreCase("docker")) {
            t.remove(0); // "/dockercraft docker ps" and "docker docker ps" both work
        }
        if (t.isEmpty()) {
            return t;
        }
        t.set(0, t.get(0).toLowerCase(Locale.ROOT));
        String first = t.get(0);
        String second = t.size() > 1 ? t.get(1).toLowerCase(Locale.ROOT) : "";
        switch (first) {
            case "container" -> {
                t.remove(0);
                if (!t.isEmpty()) {
                    String sub = t.get(0).toLowerCase(Locale.ROOT);
                    t.set(0, switch (sub) {
                        case "ls", "list" -> "ps";
                        case "remove" -> "rm";
                        default -> sub;
                    });
                }
            }
            case "image" -> {
                t.remove(0);
                if (!t.isEmpty()) {
                    String sub = t.get(0).toLowerCase(Locale.ROOT);
                    t.set(0, switch (sub) {
                        case "ls", "list" -> "images";
                        case "rm", "remove" -> "rmi";
                        default -> sub;
                    });
                }
            }
            case "images" -> {
                if (second.equals("ls") || second.equals("list")) {
                    t.remove(1); // "docker images ls" == "docker images"
                }
            }
            case "system" -> {
                if (second.equals("info")) {
                    t.remove(0);
                }
            }
            case "ls", "list" -> t.set(0, "ps");
            default -> { }
        }
        return t;
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------ dispatch

    private List<Component> run(String cmd, List<String> a) throws Exception {
        return switch (cmd) {
            case "ps" -> ps(a);
            case "images" -> images();
            case "pull" -> pull(a);
            case "run" -> runContainer(a);
            case "start", "stop", "restart", "kill", "pause", "unpause" -> control(cmd, a);
            case "rm" -> removeContainers(a);
            case "rmi" -> removeImages(a);
            case "logs" -> logs(a);
            case "exec" -> exec(a);
            case "inspect" -> inspect(a);
            case "network" -> network(a);
            case "volume" -> volume(a);
            case "version" -> version();
            case "info" -> info();
            default -> List.of(error("Unknown command: " + cmd));
        };
    }

    // ------------------------------------------------------------ read-only commands

    private List<Component> ps(List<String> a) {
        boolean all = false;
        for (String s : a) {
            if (s.equals("--all") || (s.startsWith("-") && !s.startsWith("--") && s.contains("a"))) {
                all = true; // -a, -aq, ...  (other flags such as -q are accepted and ignored)
            }
        }
        List<Container> containers = docker.listContainers(all);
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER PS" + (all ? " -a" : ""), NamedTextColor.DARK_AQUA, containers.size() + " containers"));
        if (containers.isEmpty()) {
            out.add(muted(all ? "No containers." : "No running containers. Use 'docker ps -a' to see all of them."));
        }
        for (Container c : containers) {
            String name = c.getNames() != null && c.getNames().length > 0
                    ? c.getNames()[0].replaceFirst("^/", "") : "?";
            String state = c.getState() != null ? c.getState() : "unknown";
            Component row = Component.text(shortId(c.getId()) + " ", NamedTextColor.GRAY)
                    .append(Component.text(name, NamedTextColor.WHITE, TextDecoration.BOLD))
                    .append(Component.text("  " + (c.getImage() != null ? c.getImage() : "?"), NamedTextColor.AQUA))
                    .append(Component.text("  " + (c.getStatus() != null ? c.getStatus() : state),
                            CardRenderer.stateColor(state)));
            out.add(row);
            List<String> ports = portsOf(c);
            if (!ports.isEmpty()) {
                out.add(Component.text("   ports: " + String.join(", ", ports), NamedTextColor.DARK_GRAY));
            }
        }
        return out;
    }

    private static List<String> portsOf(Container c) {
        List<String> ports = new ArrayList<>();
        if (c.getPorts() == null) {
            return ports;
        }
        for (var p : c.getPorts()) {
            if (p.getPublicPort() != null) {
                ports.add(p.getPublicPort() + "->" + p.getPrivatePort() + "/" + (p.getType() != null ? p.getType() : "tcp"));
            }
        }
        return ports.stream().distinct().toList();
    }

    private List<Component> images() {
        List<Image> images = docker.listImages();
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER IMAGES", NamedTextColor.DARK_GREEN, images.size() + " images"));
        if (images.isEmpty()) {
            out.add(muted("No images. Try: docker pull nginx"));
        }
        for (Image img : images) {
            String[] tags = img.getRepoTags();
            List<String> names = (tags == null || tags.length == 0) ? List.of("<none>") : List.of(tags);
            for (String tag : names) {
                out.add(Component.text(shortImageId(img.getId()) + " ", NamedTextColor.GRAY)
                        .append(Component.text(tag, NamedTextColor.WHITE, TextDecoration.BOLD))
                        .append(Component.text("  " + humanSize(img.getSize()), NamedTextColor.GOLD))
                        .append(Component.text("  " + age(img.getCreated()), NamedTextColor.DARK_GRAY)));
            }
        }
        return out;
    }

    private List<Component> logs(List<String> a) throws Exception {
        int lines = plugin.getConfig().getInt("logs.lines", 20);
        String ref = null;
        for (int i = 0; i < a.size(); i++) {
            String s = a.get(i);
            if (s.equals("--tail") || s.equals("-n")) {
                lines = parseInt(valueAfter(a, i++, s), s);
            } else if (s.startsWith("--tail=")) {
                lines = parseInt(s.substring(7), "--tail");
            } else if (s.startsWith("-")) {
                // -f / --follow / --timestamps ... are accepted and ignored (chat cannot stream)
                continue;
            } else {
                ref = s;
            }
        }
        if (ref == null) {
            throw new UsageException("Usage: docker logs [--tail N] <container>");
        }
        lines = Math.max(1, Math.min(lines, 200));
        ContainerInfo c = container(ref);
        List<String> log = docker.logs(c.id(), lines);
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER LOGS | " + c.name(), NamedTextColor.DARK_PURPLE, "last " + log.size() + " lines"));
        if (log.isEmpty()) {
            out.add(muted("No logs available."));
        }
        for (String line : log) {
            out.add(Component.text("│ ", NamedTextColor.DARK_PURPLE)
                    .append(Component.text(CardRenderer.truncate(line, 250), NamedTextColor.WHITE)));
        }
        return out;
    }

    private List<Component> inspect(List<String> a) {
        if (a.isEmpty()) {
            throw new UsageException("Usage: docker inspect <container>");
        }
        ContainerInfo c = container(a.get(0));
        InspectContainerResponse r = docker.inspect(c.id());
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER INSPECT | " + c.name(), NamedTextColor.DARK_AQUA, ""));
        out.add(kv("ID", r.getId() != null ? r.getId() : c.id()));
        out.add(kv("Image", r.getConfig() != null && r.getConfig().getImage() != null
                ? r.getConfig().getImage() : c.image()));
        out.add(kv("Created", String.valueOf(r.getCreated())));
        if (r.getState() != null) {
            out.add(kv("State", String.valueOf(r.getState().getStatus())));
            out.add(kv("Started", String.valueOf(r.getState().getStartedAt())));
        }
        if (r.getHostConfig() != null && r.getHostConfig().getRestartPolicy() != null) {
            out.add(kv("Restart", String.valueOf(r.getHostConfig().getRestartPolicy())));
        }
        if (r.getNetworkSettings() != null && r.getNetworkSettings().getNetworks() != null) {
            r.getNetworkSettings().getNetworks().forEach((name, net) ->
                    out.add(kv("Network " + name, net.getIpAddress() == null || net.getIpAddress().isEmpty()
                            ? "-" : net.getIpAddress())));
        }
        out.add(kv("Ports", c.ports().isEmpty() ? "none" : String.join(", ", c.ports())));
        out.add(kv("Volumes", c.volumes().isEmpty() ? "none" : String.join(", ", c.volumes())));
        return out;
    }

    private List<Component> network(List<String> a) {
        if (a.isEmpty() || !(a.get(0).equals("ls") || a.get(0).equals("list"))) {
            throw new UsageException("Usage: docker network ls");
        }
        List<Network> networks = docker.listNetworks();
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER NETWORKS", NamedTextColor.DARK_AQUA, networks.size() + " networks"));
        for (Network n : networks) {
            out.add(Component.text(shortId(n.getId()) + " ", NamedTextColor.GRAY)
                    .append(Component.text(String.valueOf(n.getName()), NamedTextColor.WHITE, TextDecoration.BOLD))
                    .append(Component.text("  " + n.getDriver() + "/" + n.getScope(), NamedTextColor.GOLD)));
        }
        return out;
    }

    private List<Component> volume(List<String> a) {
        if (a.isEmpty() || !(a.get(0).equals("ls") || a.get(0).equals("list"))) {
            throw new UsageException("Usage: docker volume ls");
        }
        var volumes = docker.listVolumes();
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER VOLUMES", NamedTextColor.DARK_AQUA, volumes.size() + " volumes"));
        for (var v : volumes) {
            out.add(Component.text(String.valueOf(v.getName()), NamedTextColor.WHITE, TextDecoration.BOLD)
                    .append(Component.text("  " + v.getDriver(), NamedTextColor.GOLD)));
        }
        return out;
    }

    private List<Component> version() {
        Version v = docker.version();
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER VERSION", NamedTextColor.DARK_AQUA, ""));
        out.add(kv("Engine", String.valueOf(v.getVersion())));
        out.add(kv("API", String.valueOf(v.getApiVersion())));
        out.add(kv("OS/Arch", v.getOperatingSystem() + "/" + v.getArch()));
        return out;
    }

    private List<Component> info() {
        Info i = docker.info();
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER INFO", NamedTextColor.DARK_AQUA, ""));
        out.add(kv("Server version", String.valueOf(i.getServerVersion())));
        out.add(kv("Containers", i.getContainers() + " (" + i.getContainersRunning() + " running)"));
        out.add(kv("Images", String.valueOf(i.getImages())));
        out.add(kv("Operating system", String.valueOf(i.getOperatingSystem())));
        return out;
    }

    // ------------------------------------------------------------ state-changing commands

    private List<Component> pull(List<String> a) throws Exception {
        List<String> images = a.stream().filter(s -> !s.startsWith("-")).toList();
        if (images.isEmpty()) {
            throw new UsageException("Usage: docker pull <image[:tag]>");
        }
        List<Component> out = new ArrayList<>();
        for (String image : images) {
            checkImageName(image);
            docker.pull(image);
            out.add(ok("Pulled " + image + (image.contains(":") || image.contains("@") ? "" : ":latest")));
        }
        return out;
    }

    private List<Component> runContainer(List<String> a) throws Exception {
        RunSpec spec = parseRun(a);
        checkImageName(spec.image);
        if (plugin.getConfig().getBoolean("commands.safe-mode", true)) {
            String violation = safeModeViolation(spec);
            if (violation != null) {
                throw new UsageException("Blocked by safe-mode: " + violation
                        + ". (Set commands.safe-mode: false in config.yml to allow it.)");
            }
        }
        String id = docker.run(spec);
        List<Component> out = new ArrayList<>();
        out.add(ok("Container started: " + shortId(id) + (spec.name != null ? " (" + spec.name + ")" : "")));
        out.add(muted("Runs detached (-d). It will show up on the panel in a few seconds."));
        return out;
    }

    private List<Component> control(String cmd, List<String> a) {
        int timeout = 10;
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            String s = a.get(i);
            if ((s.equals("-t") || s.equals("--time")) && !cmd.equals("kill")) {
                timeout = parseInt(valueAfter(a, i++, s), s);
            } else if (s.startsWith("-")) {
                continue;
            } else {
                refs.add(s);
            }
        }
        if (refs.isEmpty()) {
            throw new UsageException("Usage: docker " + cmd + " <container...>");
        }
        List<Component> out = new ArrayList<>();
        for (String ref : refs) {
            try {
                ContainerInfo c = container(ref);
                switch (cmd) {
                    case "start" -> docker.start(c.id());
                    case "stop" -> docker.stop(c.id(), timeout);
                    case "restart" -> docker.restart(c.id());
                    case "kill" -> docker.kill(c.id());
                    case "pause" -> docker.pause(c.id());
                    case "unpause" -> docker.unpause(c.id());
                    default -> throw new UsageException("Unknown command: " + cmd);
                }
                out.add(ok(cmd + " -> " + c.name()));
            } catch (UsageException e) {
                out.add(error(e.getMessage()));
            } catch (Exception e) {
                out.add(error(cmd + " " + ref + ": " + cleanMessage(e)));
            }
        }
        return out;
    }

    private List<Component> removeContainers(List<String> a) {
        boolean force = false;
        boolean volumes = false;
        List<String> refs = new ArrayList<>();
        for (String s : a) {
            if (s.startsWith("-") && !s.startsWith("--") && s.length() > 1) {
                force |= s.contains("f");
                volumes |= s.contains("v");
            } else if (s.equals("--force")) {
                force = true;
            } else if (s.equals("--volumes")) {
                volumes = true;
            } else if (!s.startsWith("-")) {
                refs.add(s);
            }
        }
        if (refs.isEmpty()) {
            throw new UsageException("Usage: docker rm [-f] [-v] <container...>");
        }
        List<Component> out = new ArrayList<>();
        for (String ref : refs) {
            try {
                ContainerInfo c = container(ref);
                docker.remove(c.id(), force, volumes);
                out.add(ok("Removed " + c.name()));
            } catch (UsageException e) {
                out.add(error(e.getMessage()));
            } catch (Exception e) {
                out.add(error("rm " + ref + ": " + cleanMessage(e)));
            }
        }
        return out;
    }

    private List<Component> removeImages(List<String> a) {
        boolean force = a.contains("-f") || a.contains("--force");
        List<String> refs = a.stream().filter(s -> !s.startsWith("-")).toList();
        if (refs.isEmpty()) {
            throw new UsageException("Usage: docker rmi [-f] <image...>");
        }
        List<Component> out = new ArrayList<>();
        for (String ref : refs) {
            try {
                docker.removeImage(ref, force);
                out.add(ok("Removed image " + ref));
            } catch (Exception e) {
                out.add(error("rmi " + ref + ": " + cleanMessage(e)));
            }
        }
        return out;
    }

    private List<Component> exec(List<String> a) throws Exception {
        int i = 0;
        while (i < a.size() && a.get(i).startsWith("-")) {
            i++; // -i, -t, -it ... (no TTY in chat)
        }
        if (i >= a.size() - 1) {
            throw new UsageException("Usage: docker exec <container> <command...>");
        }
        ContainerInfo c = container(a.get(i));
        List<String> command = a.subList(i + 1, a.size());
        List<String> result = docker.exec(c.id(), command);
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKER EXEC | " + c.name(), NamedTextColor.DARK_PURPLE, String.join(" ", command)));
        if (result.isEmpty()) {
            out.add(muted("(no output)"));
        }
        for (String line : result) {
            out.add(Component.text("│ ", NamedTextColor.DARK_PURPLE)
                    .append(Component.text(CardRenderer.truncate(line, 250), NamedTextColor.WHITE)));
        }
        return out;
    }

    // ------------------------------------------------------------ docker run parsing

    /** Options that take a value, in their long form. Short aliases are mapped in {@link #longName}. */
    private static final Set<String> VALUE_OPTIONS = Set.of("--name", "--publish", "--volume", "--env", "--restart",
            "--network", "--workdir", "--user", "--memory", "--entrypoint", "--label", "--hostname", "--cap-add",
            "--device", "--pid");

    private static String longName(String opt) {
        return switch (opt) {
            case "-p" -> "--publish";
            case "-v" -> "--volume";
            case "-e" -> "--env";
            case "-w" -> "--workdir";
            case "-u" -> "--user";
            case "-m" -> "--memory";
            case "-l" -> "--label";
            case "-h" -> "--hostname";
            case "--net" -> "--network";
            default -> opt;
        };
    }

    static RunSpec parseRun(List<String> a) {
        RunSpec spec = new RunSpec();
        int i = 0;
        for (; i < a.size(); i++) {
            String tok = a.get(i);
            if (!tok.startsWith("-") || tok.equals("-")) {
                break; // first non-option = image
            }
            String name = tok;
            String value = null;
            int eq = tok.indexOf('=');
            if (tok.startsWith("--") && eq > 0) {
                name = tok.substring(0, eq);
                value = tok.substring(eq + 1);
            } else if (!tok.startsWith("--") && tok.length() > 2 && VALUE_OPTIONS.contains(longName(tok.substring(0, 2)))) {
                name = tok.substring(0, 2); // -p8080:80, -eFOO=bar
                value = tok.substring(2);
            }
            name = longName(name);

            if (VALUE_OPTIONS.contains(name)) {
                if (value == null) {
                    value = valueAfter(a, i++, name);
                }
                applyValue(spec, name, value);
                continue;
            }
            switch (name) {
                case "--rm" -> spec.autoRemove = true;
                case "--privileged" -> spec.privileged = true;
                case "-P", "--publish-all" -> spec.publishAll = true;
                case "--read-only" -> spec.readOnly = true;
                case "-d", "--detach", "-i", "--interactive", "-t", "--tty", "-it", "-dit", "-di", "-dt", "-id", "-td",
                     "--init" -> { /* always detached, no TTY in chat */ }
                default -> throw new UsageException("docker run: unsupported option " + tok
                        + ". Supported: -d --name -p -v -e --rm --restart --network -w -u -m --entrypoint -l -h"
                        + " --privileged --cap-add --device --pid -P --read-only");
            }
        }
        if (i >= a.size()) {
            throw new UsageException("Usage: docker run [options] <image> [command...]");
        }
        spec.image = a.get(i);
        spec.cmd.addAll(a.subList(i + 1, a.size()));
        return spec;
    }

    private static void applyValue(RunSpec s, String option, String v) {
        switch (option) {
            case "--name" -> s.name = v;
            case "--publish" -> s.ports.add(v);
            case "--volume" -> s.binds.add(v);
            case "--env" -> s.env.add(v);
            case "--restart" -> s.restart = v;
            case "--network" -> s.network = v;
            case "--workdir" -> s.workdir = v;
            case "--user" -> s.user = v;
            case "--entrypoint" -> s.entrypoint = v;
            case "--hostname" -> s.hostname = v;
            case "--cap-add" -> s.capAdd.add(v);
            case "--device" -> s.devices.add(v);
            case "--pid" -> s.pid = v;
            case "--memory" -> s.memoryBytes = parseMemory(v);
            case "--label" -> {
                int eq = v.indexOf('=');
                s.labels.put(eq > 0 ? v.substring(0, eq) : v, eq > 0 ? v.substring(eq + 1) : "");
            }
            default -> { }
        }
    }

    /** Returns why the spec is dangerous, or null if it is acceptable under safe-mode. */
    static String safeModeViolation(RunSpec s) {
        if (s.privileged) {
            return "--privileged gives the container full control of the host";
        }
        if (!s.capAdd.isEmpty()) {
            return "--cap-add";
        }
        if (!s.devices.isEmpty()) {
            return "--device";
        }
        if ("host".equalsIgnoreCase(s.pid)) {
            return "--pid host";
        }
        if ("host".equalsIgnoreCase(s.network)) {
            return "--network host";
        }
        for (String bind : s.binds) {
            if (isHostPathBind(bind)) {
                return "bind-mounting a host path (" + bind + "). Use a named volume instead";
            }
        }
        return null;
    }

    private static boolean isHostPathBind(String bind) {
        if (WINDOWS_DRIVE.matcher(bind).matches()) {
            return true;
        }
        if (!bind.contains(":")) {
            return false; // anonymous volume ("-v /data"): lives inside Docker
        }
        String source = bind.substring(0, bind.indexOf(':'));
        return source.startsWith("/") || source.startsWith(".") || source.startsWith("~");
    }

    private static Long parseMemory(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        long mult = 1;
        if (s.endsWith("k") || s.endsWith("kb")) {
            mult = 1024L;
        } else if (s.endsWith("m") || s.endsWith("mb")) {
            mult = 1024L * 1024;
        } else if (s.endsWith("g") || s.endsWith("gb")) {
            mult = 1024L * 1024 * 1024;
        }
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw new UsageException("Invalid memory value: " + v);
        }
        return Long.parseLong(digits) * mult;
    }

    private static void checkImageName(String image) {
        if (image == null || image.isBlank() || image.startsWith("-") || image.length() > 255
                || !image.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]*")) {
            throw new UsageException("Invalid image name: " + image);
        }
    }

    private static String valueAfter(List<String> a, int index, String option) {
        if (index + 1 >= a.size()) {
            throw new UsageException("Option " + option + " needs a value");
        }
        return a.get(index + 1);
    }

    private static int parseInt(String s, String option) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new UsageException("Option " + option + " needs a number, got '" + s + "'");
        }
    }

    private ContainerInfo container(String ref) {
        Optional<ContainerInfo> found = docker.resolve(ref);
        if (found.isEmpty()) {
            throw new UsageException("No such container: " + ref);
        }
        return found.get();
    }

    // ------------------------------------------------------------ output helpers

    private static void reply(CommandSender sender, List<Component> lines) {
        lines.forEach(sender::sendMessage);
    }

    private static Component title(String text, NamedTextColor color, String right) {
        Component c = Component.text("══ ", color)
                .append(Component.text(text, NamedTextColor.WHITE, TextDecoration.BOLD));
        if (!right.isEmpty()) {
            c = c.append(Component.text("  " + right, NamedTextColor.GRAY));
        }
        return c.append(Component.text(" ══", color));
    }

    private static Component kv(String key, String value) {
        return Component.text(key + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component ok(String text) {
        return Component.text("DockerCraft: ", NamedTextColor.GREEN, TextDecoration.BOLD)
                .append(Component.text(text, NamedTextColor.GREEN));
    }

    private static Component error(String text) {
        return Component.text("DockerCraft: ", NamedTextColor.RED, TextDecoration.BOLD)
                .append(Component.text(text, NamedTextColor.RED));
    }

    private static Component muted(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static List<Component> helpLines() {
        List<Component> out = new ArrayList<>();
        out.add(title("DOCKERCRAFT: RELOADED", NamedTextColor.AQUA, "docker commands"));
        String[][] rows = {
                {"docker ps [-a]", "list containers"},
                {"docker images", "list images"},
                {"docker pull <image[:tag]>", "download an image"},
                {"docker run [opts] <image> [cmd]", "-d --name -p -v -e --rm --restart --network -m ..."},
                {"docker start|stop|restart|kill <name>", "control containers"},
                {"docker rm [-f] <name>  /  docker rmi <image>", "remove container / image"},
                {"docker logs [--tail N] <name>", "last log lines"},
                {"docker exec <name> <cmd>", "run a command inside a container"},
                {"docker inspect <name>", "details of a container"},
                {"docker network ls | volume ls | version | info", "misc"},
        };
        for (String[] r : rows) {
            out.add(Component.text(" " + r[0], NamedTextColor.WHITE)
                    .append(Component.text("  " + r[1], NamedTextColor.GRAY)));
        }
        out.add(Component.text(" Also: container ls, image ls, images ls, container rm, image rm...", NamedTextColor.DARK_GRAY));
        out.add(Component.text(" Use as /docker <cmd>, /dc <cmd> or /dockercraft docker <cmd>. Works from the console too.",
                NamedTextColor.DARK_GRAY));
        return out;
    }

    private static String shortId(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() > 12 ? id.substring(0, 12) : id;
    }

    private static String shortImageId(String id) {
        if (id == null) {
            return "?";
        }
        String clean = id.startsWith("sha256:") ? id.substring(7) : id;
        return shortId(clean);
    }

    private static String humanSize(Long bytes) {
        if (bytes == null) {
            return "?";
        }
        double b = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (b >= 1000 && u < units.length - 1) {
            b /= 1000;
            u++;
        }
        return u == 0 ? (long) b + " B" : String.format(Locale.ROOT, "%.1f %s", b, units[u]);
    }

    private static String age(Long createdEpochSeconds) {
        if (createdEpochSeconds == null) {
            return "";
        }
        Duration d = Duration.between(Instant.ofEpochSecond(createdEpochSeconds), Instant.now());
        long days = d.toDays();
        if (days >= 365) {
            return (days / 365) + " years ago";
        }
        if (days >= 30) {
            return (days / 30) + " months ago";
        }
        if (days >= 1) {
            return days + " days ago";
        }
        if (d.toHours() >= 1) {
            return d.toHours() + " hours ago";
        }
        return Math.max(0, d.toMinutes()) + " minutes ago";
    }

    /** Docker-java exceptions carry a JSON blob; keep it short and readable. */
    private static String cleanMessage(Throwable e) {
        String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        m = m.replaceAll("^Status \\d+: ", "").replaceAll("\\{\"message\":\"(.*?)\"\\}", "$1");
        return CardRenderer.truncate(m.replace('\n', ' '), 200);
    }
}
