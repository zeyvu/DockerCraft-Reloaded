package dev.mcdocker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Frame;
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
                .responseTimeout(Duration.ofSeconds(30))
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
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
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

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // nothing to do on shutdown
        }
    }
}
