package dev.dockercraft;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Device;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Thin wrapper around docker-java. ALL methods are blocking:
 * always call them from an async thread, never from the main server thread.
 */
public final class DockerService implements AutoCloseable {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    private final DockerClient client;

    public DockerService(String host) {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(host)
                .build();
        ZerodepDockerHttpClient http = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(8)
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(120)) // image pulls can be quiet for a while
                .build();
        this.client = DockerClientImpl.getInstance(config, http);
    }

    /** Lists all containers (including stopped ones), sorted by name. */
    public List<ContainerInfo> snapshot() {
        List<Container> containers = client.listContainersCmd().withShowAll(true).exec();
        List<ContainerInfo> result = new ArrayList<>();
        for (Container c : containers) {
            result.add(toInfo(c));
        }
        result.sort(Comparator.comparing(ContainerInfo::name));
        return List.copyOf(result);
    }

    private static ContainerInfo toInfo(Container c) {
        String id = c.getId();
        String[] names = c.getNames();
        String name = (names != null && names.length > 0)
                ? names[0].replaceFirst("^/", "")
                : id.substring(0, Math.min(12, id.length()));
        String state = c.getState() != null ? c.getState() : "unknown";
        String image = c.getImage() != null ? c.getImage() : "unknown";
        return new ContainerInfo(id, name, state, image, formatPorts(c), formatVolumes(c));
    }

    private static List<String> formatPorts(Container c) {
        TreeSet<String> set = new TreeSet<>();
        ContainerPort[] ports = c.getPorts();
        if (ports == null) {
            return List.of();
        }
        for (ContainerPort p : ports) {
            String type = p.getType() != null ? p.getType() : "tcp";
            String left = p.getPrivatePort() + "/" + type;
            Integer pub = p.getPublicPort();
            String ip = p.getIp();
            if (pub == null) {
                set.add(left + " -> not published");
            } else if (ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0") && !ip.equals("::")) {
                set.add(left + " -> " + ip + ":" + pub);
            } else {
                set.add(left + " -> " + pub);
            }
        }
        return List.copyOf(set);
    }

    private static List<String> formatVolumes(Container c) {
        TreeSet<String> set = new TreeSet<>();
        List<ContainerMount> mounts = c.getMounts();
        if (mounts == null) {
            return List.of();
        }
        for (ContainerMount m : mounts) {
            String source = m.getSource() != null ? m.getSource()
                    : (m.getName() != null ? m.getName() : "unknown");
            String dest = m.getDestination() != null ? m.getDestination() : "unknown";
            String mode = m.getMode();
            set.add(source + " -> " + dest + ((mode != null && !mode.isEmpty()) ? " (" + mode + ")" : ""));
        }
        return List.copyOf(set);
    }

    public Optional<ContainerInfo> find(String shortId) {
        return snapshot().stream().filter(c -> c.id().startsWith(shortId)).findFirst();
    }

    public void start(String id) {
        client.startContainerCmd(id).exec();
    }

    public void stop(String id) {
        client.stopContainerCmd(id).withTimeout(10).exec();
    }

    public void restart(String id) {
        client.restartContainerCmd(id).exec();
    }

    /** Last {@code lines} log lines (stdout + stderr), without ANSI codes. */
    public List<String> logs(String id, int lines) throws Exception {
        List<String> out = new ArrayList<>();
        ResultCallback.Adapter<Frame> callback = frameCollector(out);
        client.logContainerCmd(id)
                .withStdOut(true)
                .withStdErr(true)
                .withTimestamps(true)
                .withTail(lines)
                .exec(callback);
        callback.awaitCompletion(10, TimeUnit.SECONDS);
        callback.close();
        synchronized (out) {
            return List.copyOf(out);
        }
    }

    private static ResultCallback.Adapter<Frame> frameCollector(List<String> out) {
        return new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
                String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                for (String line : text.split("\\R")) {
                    if (!line.isBlank()) {
                        synchronized (out) {
                            out.add(ANSI.matcher(line).replaceAll(""));
                        }
                    }
                }
            }
        };
    }

    // ------------------------------------------------------------ docker CLI operations

    /** Raw container list (what {@code docker ps} shows). */
    public List<Container> listContainers(boolean all) {
        return client.listContainersCmd().withShowAll(all).exec();
    }

    /**
     * Finds a container by exact name, unique id prefix or unique name prefix
     * (the same way the docker CLI accepts them).
     */
    public Optional<ContainerInfo> resolve(String ref) {
        String needle = ref.startsWith("/") ? ref.substring(1) : ref;
        List<ContainerInfo> all = snapshot();
        for (ContainerInfo c : all) {
            if (c.name().equals(needle) || c.id().equals(needle)) {
                return Optional.of(c);
            }
        }
        List<ContainerInfo> byId = all.stream().filter(c -> c.id().startsWith(needle)).toList();
        if (byId.size() == 1) {
            return Optional.of(byId.get(0));
        }
        List<ContainerInfo> byName = all.stream().filter(c -> c.name().startsWith(needle)).toList();
        if (byName.size() == 1) {
            return Optional.of(byName.get(0));
        }
        return Optional.empty();
    }

    public void kill(String id) {
        client.killContainerCmd(id).exec();
    }

    public void pause(String id) {
        client.pauseContainerCmd(id).exec();
    }

    public void unpause(String id) {
        client.unpauseContainerCmd(id).exec();
    }

    public void stop(String id, int timeoutSeconds) {
        client.stopContainerCmd(id).withTimeout(timeoutSeconds).exec();
    }

    public void remove(String id, boolean force, boolean volumes) {
        client.removeContainerCmd(id).withForce(force).withRemoveVolumes(volumes).exec();
    }

    public InspectContainerResponse inspect(String id) {
        return client.inspectContainerCmd(id).exec();
    }

    public List<Image> listImages() {
        return client.listImagesCmd().exec();
    }

    public void removeImage(String ref, boolean force) {
        client.removeImageCmd(ref).withForce(force).exec();
    }

    public List<Network> listNetworks() {
        return client.listNetworksCmd().exec();
    }

    public List<InspectVolumeResponse> listVolumes() {
        var response = client.listVolumesCmd().exec();
        return response.getVolumes() != null ? response.getVolumes() : List.of();
    }

    public Version version() {
        return client.versionCmd().exec();
    }

    public Info info() {
        return client.infoCmd().exec();
    }

    /** {@code docker pull repo[:tag|@digest]}. Blocks until the pull finishes (max 15 min). */
    public void pull(String reference) throws Exception {
        String repo = imageRepository(reference);
        String tag = imageTag(reference);
        ResultCallback.Adapter<PullResponseItem> callback = new ResultCallback.Adapter<PullResponseItem>();
        client.pullImageCmd(repo).withTag(tag).exec(callback);
        boolean finished = callback.awaitCompletion(15, TimeUnit.MINUTES);
        callback.close();
        if (!finished) {
            throw new IllegalStateException("pull timed out after 15 minutes");
        }
    }

    /**
     * {@code docker run -d}: creates and starts a container. If the image is not present
     * locally it is pulled first, exactly like the docker CLI does.
     *
     * @return the id of the new container
     */
    public String run(RunSpec spec) throws Exception {
        String id;
        try {
            id = create(spec);
        } catch (NotFoundException e) {
            String message = String.valueOf(e.getMessage());
            if (!message.contains("No such image")) {
                throw e;
            }
            pull(spec.image);
            id = create(spec);
        }
        client.startContainerCmd(id).exec();
        return id;
    }

    private String create(RunSpec s) {
        HostConfig host = HostConfig.newHostConfig();

        List<ExposedPort> exposed = new ArrayList<>();
        List<PortBinding> bindings = new ArrayList<>();
        for (String p : s.ports) {
            PortBinding binding = PortBinding.parse(p);
            bindings.add(binding);
            exposed.add(binding.getExposedPort());
        }
        if (!bindings.isEmpty()) {
            host.withPortBindings(bindings);
        }
        if (!s.binds.isEmpty()) {
            List<Bind> binds = new ArrayList<>();
            for (String b : s.binds) {
                binds.add(Bind.parse(b));
            }
            host.withBinds(binds);
        }
        if (s.restart != null) {
            host.withRestartPolicy(RestartPolicy.parse(s.restart));
        }
        if (s.autoRemove) {
            host.withAutoRemove(true);
        }
        if (s.privileged) {
            host.withPrivileged(true);
        }
        if (s.publishAll) {
            host.withPublishAllPorts(true);
        }
        if (s.readOnly) {
            host.withReadonlyRootfs(true);
        }
        if (s.network != null) {
            host.withNetworkMode(s.network);
        }
        if (s.pid != null) {
            host.withPidMode(s.pid);
        }
        if (s.memoryBytes != null) {
            host.withMemory(s.memoryBytes);
        }
        if (!s.capAdd.isEmpty()) {
            Capability[] caps = s.capAdd.stream()
                    .map(c -> Capability.valueOf(c.toUpperCase(java.util.Locale.ROOT).replaceFirst("^CAP_", "")))
                    .toArray(Capability[]::new);
            host.withCapAdd(caps);
        }
        if (!s.devices.isEmpty()) {
            List<Device> devices = new ArrayList<>();
            for (String d : s.devices) {
                devices.add(Device.parse(d));
            }
            host.withDevices(devices);
        }

        CreateContainerCmd cmd = client.createContainerCmd(s.image).withHostConfig(host);
        if (!exposed.isEmpty()) {
            cmd.withExposedPorts(exposed);
        }
        if (s.name != null) {
            cmd.withName(s.name);
        }
        if (!s.env.isEmpty()) {
            cmd.withEnv(s.env);
        }
        if (!s.cmd.isEmpty()) {
            cmd.withCmd(s.cmd);
        }
        if (s.entrypoint != null) {
            cmd.withEntrypoint(List.of(s.entrypoint.trim().split("\\s+")));
        }
        if (s.workdir != null) {
            cmd.withWorkingDir(s.workdir);
        }
        if (s.user != null) {
            cmd.withUser(s.user);
        }
        if (s.hostname != null) {
            cmd.withHostName(s.hostname);
        }
        if (!s.labels.isEmpty()) {
            cmd.withLabels(s.labels);
        }
        return cmd.exec().getId();
    }

    /** {@code docker exec}: runs a command inside a running container and returns its output. */
    public List<String> exec(String id, List<String> command) throws Exception {
        ExecCreateCmdResponse created = client.execCreateCmd(id)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withCmd(command.toArray(String[]::new))
                .exec();
        List<String> out = new ArrayList<>();
        ResultCallback.Adapter<Frame> callback = frameCollector(out);
        client.execStartCmd(created.getId()).exec(callback);
        boolean finished = callback.awaitCompletion(30, TimeUnit.SECONDS);
        callback.close();
        if (!finished) {
            out.add("(output cut: the command was still running after 30s)");
        }
        synchronized (out) {
            return List.copyOf(out);
        }
    }

    // ------------------------------------------------------------ image reference helpers

    /** "nginx:1.27" -> "nginx"; "localhost:5000/app:v1" -> "localhost:5000/app"; "app@sha256:..." -> "app". */
    static String imageRepository(String reference) {
        int at = reference.indexOf('@');
        if (at >= 0) {
            return reference.substring(0, at);
        }
        int colon = reference.lastIndexOf(':');
        int slash = reference.lastIndexOf('/');
        return colon > slash ? reference.substring(0, colon) : reference;
    }

    /** Tag (or digest) of the reference; "latest" if none was given. */
    static String imageTag(String reference) {
        int at = reference.indexOf('@');
        if (at >= 0) {
            return reference.substring(at + 1);
        }
        int colon = reference.lastIndexOf(':');
        int slash = reference.lastIndexOf('/');
        return colon > slash ? reference.substring(colon + 1) : "latest";
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // nothing to do on shutdown
        }
    }
}
