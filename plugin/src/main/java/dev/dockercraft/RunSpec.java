package dev.dockercraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed options of a {@code docker run}. Filled by {@link DockerCli}, consumed by {@link DockerService#run}. */
public final class RunSpec {
    public String image;
    public String name;
    public String restart;
    public String network;
    public String workdir;
    public String user;
    public String hostname;
    public String pid;
    public String entrypoint;
    public Long memoryBytes;
    public boolean autoRemove;
    public boolean privileged;
    public boolean publishAll;
    public boolean readOnly;
    public final List<String> cmd = new ArrayList<>();
    public final List<String> env = new ArrayList<>();
    public final List<String> ports = new ArrayList<>();
    public final List<String> binds = new ArrayList<>();
    public final List<String> capAdd = new ArrayList<>();
    public final List<String> devices = new ArrayList<>();
    public final Map<String, String> labels = new LinkedHashMap<>();
}
