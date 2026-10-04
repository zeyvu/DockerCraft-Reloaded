# ============================================================
# DOCKERCRAFT: RELOADED - DOCKER COMMAND INTERPRETER
# ============================================================
#
#  Usual docker CLI:
#    docker ps [-a]                      docker images            docker pull nginx:1.27
#    docker run -d --name web -p 8080:80 nginx
#    docker start|stop|restart|kill|pause|unpause <name>
#    docker rm [-f] <name>               docker rmi <image>
#    docker logs [--tail N] <name>       docker exec <name> <cmd...>
#    docker inspect <name>               docker network ls | volume ls | version | info
#

import re
import shlex
from datetime import datetime, timezone

import docker


# ============================================================
# RESULT LINES
# ============================================================
# A result is a list of (text, color) tuples. "color" is a Minecraft
# color name ("white", "gray", "green", "red", "aqua", "gold", ...).

ANSI = re.compile(r"\x1b\[[0-9;]*m")

COMMANDS = [
    "ps", "images", "pull", "run", "start", "stop", "restart", "kill",
    "pause", "unpause", "rm", "rmi", "logs", "exec", "inspect",
    "network", "volume", "version", "info", "help",
]

# Commands that only read state: no need to refresh the Minecraft panel afterwards.
READ_ONLY = {"ps", "images", "logs", "inspect", "network", "volume", "version", "info", "help"}

IMAGE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/@+-]*$")
WINDOWS_DRIVE = re.compile(r"^[A-Za-z]:")  # a named volume needs 2+ chars, so "C:" is always a drive


class DockerCliError(Exception):
    """A user mistake (bad syntax, unknown container, blocked by safe-mode...). Shown in red."""


# ============================================================
# PARSING
# ============================================================

def tokenize(line):
    """Splits a command line honouring "double" and 'single' quotes."""
    try:
        return shlex.split(line)
    except ValueError as error:
        raise DockerCliError(f"Could not parse the command: {error}")


def normalize(tokens):
    """
    Maps the many spellings of the docker CLI to one canonical form:
    "container ls" -> ps, "image ls" / "images ls" -> images, "image rm" -> rmi, ...
    Also strips a leading "docker" / "dockercraft" / "dc" / "/dockercraft".
    """
    t = list(tokens)

    while t and t[0].lstrip("/").lower() in ("docker", "dockercraft", "dc"):
        t.pop(0)

    if not t:
        return t

    t[0] = t[0].lower()
    first = t[0]
    second = t[1].lower() if len(t) > 1 else ""

    if first == "container":
        t.pop(0)
        if t:
            t[0] = {"ls": "ps", "list": "ps", "remove": "rm"}.get(t[0].lower(), t[0].lower())
    elif first == "image":
        t.pop(0)
        if t:
            t[0] = {"ls": "images", "list": "images", "rm": "rmi", "remove": "rmi"}.get(
                t[0].lower(), t[0].lower()
            )
    elif first == "images" and second in ("ls", "list"):
        t.pop(1)
    elif first == "system" and second == "info":
        t.pop(0)
    elif first in ("ls", "list"):
        t[0] = "ps"

    return t


def parse_image(reference):
    """'nginx:1.27' -> ('nginx', '1.27'); 'localhost:5000/app' -> ('localhost:5000/app', 'latest')."""
    if "@" in reference:
        repo, digest = reference.split("@", 1)
        return repo, digest
    colon = reference.rfind(":")
    slash = reference.rfind("/")
    if colon > slash:
        return reference[:colon], reference[colon + 1:]
    return reference, "latest"


def check_image_name(image):
    if not image or image.startswith("-") or len(image) > 255 or not IMAGE_NAME.match(image):
        raise DockerCliError(f"Invalid image name: {image}")


def _value_after(args, index, option):
    if index + 1 >= len(args):
        raise DockerCliError(f"Option {option} needs a value")
    return args[index + 1]


def _int(value, option):
    try:
        return int(value)
    except ValueError:
        raise DockerCliError(f"Option {option} needs a number, got '{value}'")


# ============================================================
# docker run
# ============================================================

VALUE_OPTIONS = {
    "--name", "--publish", "--volume", "--env", "--restart", "--network", "--workdir", "--user",
    "--memory", "--entrypoint", "--label", "--hostname", "--cap-add", "--device", "--pid",
}

SHORT_TO_LONG = {
    "-p": "--publish", "-v": "--volume", "-e": "--env", "-w": "--workdir", "-u": "--user",
    "-m": "--memory", "-l": "--label", "-h": "--hostname", "--net": "--network",
}

IGNORED_FLAGS = {
    "-d", "--detach", "-i", "--interactive", "-t", "--tty", "--init",
    "-it", "-ti", "-dit", "-dti", "-di", "-dt", "-id", "-td", "-itd", "-tid",
}


def parse_run(args):
    """Returns a dict of options for `docker run`. Always detached (there is no TTY in chat)."""
    spec = {
        "image": None, "command": [], "name": None, "ports": [], "volumes": [], "environment": [],
        "restart": None, "network": None, "workdir": None, "user": None, "memory": None,
        "entrypoint": None, "labels": {}, "hostname": None, "cap_add": [], "devices": [],
        "pid": None, "auto_remove": False, "privileged": False, "publish_all": False,
        "read_only": False,
    }

    i = 0
    while i < len(args):
        tok = args[i]
        if not tok.startswith("-") or tok == "-":
            break  # first non-option = image

        name, value = tok, None
        if tok.startswith("--") and "=" in tok:
            name, value = tok.split("=", 1)
        elif not tok.startswith("--") and len(tok) > 2 and SHORT_TO_LONG.get(tok[:2]) in VALUE_OPTIONS:
            name, value = tok[:2], tok[2:]  # -p8080:80, -eFOO=bar
        name = SHORT_TO_LONG.get(name, name)

        if name in VALUE_OPTIONS:
            if value is None:
                value = _value_after(args, i, name)
                i += 1
            _apply_run_value(spec, name, value)
        elif name == "--rm":
            spec["auto_remove"] = True
        elif name == "--privileged":
            spec["privileged"] = True
        elif name in ("-P", "--publish-all"):
            spec["publish_all"] = True
        elif name == "--read-only":
            spec["read_only"] = True
        elif name in IGNORED_FLAGS:
            pass
        else:
            raise DockerCliError(
                f"docker run: unsupported option {tok}. Supported: -d --name -p -v -e --rm "
                "--restart --network -w -u -m --entrypoint -l -h --privileged --cap-add --device "
                "--pid -P --read-only"
            )
        i += 1

    if i >= len(args):
        raise DockerCliError("Usage: docker run [options] <image> [command...]")

    spec["image"] = args[i]
    spec["command"] = args[i + 1:]
    return spec


def _apply_run_value(spec, option, value):
    if option == "--name":
        spec["name"] = value
    elif option == "--publish":
        spec["ports"].append(value)
    elif option == "--volume":
        spec["volumes"].append(value)
    elif option == "--env":
        spec["environment"].append(value)
    elif option == "--restart":
        spec["restart"] = value
    elif option == "--network":
        spec["network"] = value
    elif option == "--workdir":
        spec["workdir"] = value
    elif option == "--user":
        spec["user"] = value
    elif option == "--memory":
        spec["memory"] = value
    elif option == "--entrypoint":
        spec["entrypoint"] = value
    elif option == "--hostname":
        spec["hostname"] = value
    elif option == "--cap-add":
        spec["cap_add"].append(value)
    elif option == "--device":
        spec["devices"].append(value)
    elif option == "--pid":
        spec["pid"] = value
    elif option == "--label":
        key, _, val = value.partition("=")
        spec["labels"][key] = val


def _is_host_path_bind(bind):
    if WINDOWS_DRIVE.match(bind):
        return True
    if ":" not in bind:
        return False  # anonymous volume ("-v /data") lives inside Docker
    source = bind.split(":", 1)[0]
    return source.startswith(("/", ".", "~"))


def safe_mode_violation(spec):
    """Returns why the spec is dangerous, or None if it is acceptable under safe-mode."""
    if spec["privileged"]:
        return "--privileged gives the container full control of the host"
    if spec["cap_add"]:
        return "--cap-add"
    if spec["devices"]:
        return "--device"
    if (spec["pid"] or "").lower() == "host":
        return "--pid host"
    if (spec["network"] or "").lower() == "host":
        return "--network host"
    for bind in spec["volumes"]:
        if _is_host_path_bind(bind):
            return f"bind-mounting a host path ({bind}). Use a named volume instead"
    return None


def _port_mapping(ports):
    """['8080:80', '127.0.0.1:9000:90/udp', '25565'] -> docker SDK `ports` dict."""
    mapping = {}
    for p in ports:
        proto = "tcp"
        body = p
        if "/" in body:
            body, proto = body.rsplit("/", 1)
        parts = body.split(":")
        try:
            if len(parts) == 1:
                container_port, host = parts[0], None
            elif len(parts) == 2:
                container_port, host = parts[1], int(parts[0])
            elif len(parts) == 3:
                container_port, host = parts[2], (parts[0], int(parts[1]))
            else:
                raise ValueError
            int(container_port)
        except ValueError:
            raise DockerCliError(f"Invalid port mapping: {p}")
        mapping[f"{container_port}/{proto}"] = host
    return mapping


def _restart_policy(value):
    name, _, retries = value.partition(":")
    policy = {"Name": "" if name == "no" else name}
    if retries:
        policy["MaximumRetryCount"] = _int(retries, "--restart")
    return policy


def run_container(client, spec):
    kwargs = {
        "detach": True,
        "name": spec["name"],
        "ports": _port_mapping(spec["ports"]) or None,
        "volumes": spec["volumes"] or None,
        "environment": spec["environment"] or None,
        "labels": spec["labels"] or None,
        "restart_policy": _restart_policy(spec["restart"]) if spec["restart"] else None,
        "network_mode": spec["network"],
        "working_dir": spec["workdir"],
        "user": spec["user"],
        "mem_limit": spec["memory"],
        "entrypoint": spec["entrypoint"].split() if spec["entrypoint"] else None,
        "hostname": spec["hostname"],
        "cap_add": spec["cap_add"] or None,
        "devices": spec["devices"] or None,
        "pid_mode": spec["pid"],
        "auto_remove": spec["auto_remove"],
        "privileged": spec["privileged"],
        "publish_all_ports": spec["publish_all"],
        "read_only": spec["read_only"],
    }
    kwargs = {k: v for k, v in kwargs.items() if v is not None and v is not False}
    kwargs["detach"] = True
    # containers.run() pulls the image automatically if it is not present, like the docker CLI.
    return client.containers.run(spec["image"], spec["command"] or None, **kwargs)


# ============================================================
# HELPERS
# ============================================================

def resolve_container(client, ref):
    """Finds a container by exact name, unique id prefix or unique name prefix."""
    needle = ref[1:] if ref.startswith("/") else ref
    containers = client.containers.list(all=True)

    for c in containers:
        if c.name == needle or c.id == needle:
            return c

    by_id = [c for c in containers if c.id.startswith(needle)]
    if len(by_id) == 1:
        return by_id[0]

    by_name = [c for c in containers if c.name.startswith(needle)]
    if len(by_name) == 1:
        return by_name[0]

    raise DockerCliError(f"No such container: {ref}")


def _short(identifier):
    identifier = identifier or "?"
    if identifier.startswith("sha256:"):
        identifier = identifier[7:]
    return identifier[:12]


def _human_size(size):
    if size is None:
        return "?"
    value = float(size)
    for unit in ("B", "KB", "MB", "GB"):
        if value < 1000:
            return f"{int(value)} B" if unit == "B" else f"{value:.1f} {unit}"
        value /= 1000
    return f"{value:.1f} TB"


def _age(created):
    """Docker returns an RFC 3339 string (nanoseconds, 'Z')."""
    if not created:
        return ""
    try:
        text = re.sub(r"\.(\d{6})\d*", r".\1", created).replace("Z", "+00:00")
        delta = datetime.now(timezone.utc) - datetime.fromisoformat(text)
    except ValueError:
        return ""
    days = delta.days
    if days >= 365:
        return f"{days // 365} years ago"
    if days >= 30:
        return f"{days // 30} months ago"
    if days >= 1:
        return f"{days} days ago"
    hours = delta.seconds // 3600
    if hours >= 1:
        return f"{hours} hours ago"
    return f"{delta.seconds // 60} minutes ago"


def _clean_error(error):
    explanation = getattr(error, "explanation", None)
    message = explanation if explanation else str(error)
    return message.replace("\n", " ")[:200]


def _title(text, right=""):
    return (f"== {text}" + (f"  ({right})" if right else "") + " ==", "aqua")


def _ok(text):
    return (f"DockerCraft: {text}", "green")


def _port_list(container):
    ports = []
    for container_port, bindings in (container.ports or {}).items():
        for binding in bindings or []:
            ports.append(f"{binding.get('HostPort')}->{container_port}")
    return sorted(set(ports))


STATE_COLORS = {
    "running": "green", "exited": "red", "paused": "yellow", "restarting": "gold",
    "created": "aqua", "dead": "dark_red",
}


# ============================================================
# COMMANDS
# ============================================================

def help_lines():
    rows = [
        ("docker ps [-a]", "list containers"),
        ("docker images", "list images"),
        ("docker pull <image[:tag]>", "download an image"),
        ("docker run [opts] <image> [cmd]", "-d --name -p -v -e --rm --restart --network -m ..."),
        ("docker start|stop|restart|kill <name>", "control containers"),
        ("docker rm [-f] <name>  /  docker rmi <image>", "remove container / image"),
        ("docker logs [--tail N] <name>", "last log lines"),
        ("docker exec <name> <cmd>", "run a command inside a container"),
        ("docker inspect <name>", "details of a container"),
        ("docker network ls | volume ls | version | info", "misc"),
    ]
    lines = [_title("DOCKERCRAFT: RELOADED", "docker commands")]
    for command, description in rows:
        lines.append((f" {command}   - {description}", "white"))
    lines.append((" Also: container ls, image ls, images ls, container rm, image rm...", "gray"))
    return lines


def cmd_ps(client, args):
    show_all = any(a == "--all" or (a.startswith("-") and not a.startswith("--") and "a" in a) for a in args)
    containers = client.containers.list(all=show_all)
    lines = [_title("DOCKER PS" + (" -a" if show_all else ""), f"{len(containers)} containers")]
    if not containers:
        lines.append((
            "No containers." if show_all else "No running containers. Use 'docker ps -a' to see all of them.",
            "gray",
        ))
    for c in containers:
        image = c.attrs.get("Config", {}).get("Image", "?")
        lines.append((f"{c.short_id} {c.name}  {image}  {c.status}", STATE_COLORS.get(c.status, "white")))
        ports = _port_list(c)
        if ports:
            lines.append((f"   ports: {', '.join(ports)}", "dark_gray"))
    return lines


def cmd_images(client, args):
    images = client.images.list()
    lines = [_title("DOCKER IMAGES", f"{len(images)} images")]
    if not images:
        lines.append(("No images. Try: docker pull nginx", "gray"))
    for image in images:
        tags = image.tags or ["<none>"]
        for tag in tags:
            lines.append((
                f"{_short(image.id)} {tag}  {_human_size(image.attrs.get('Size'))}  "
                f"{_age(image.attrs.get('Created'))}",
                "white",
            ))
    return lines


def cmd_pull(client, args):
    images = [a for a in args if not a.startswith("-")]
    if not images:
        raise DockerCliError("Usage: docker pull <image[:tag]>")
    lines = []
    for reference in images:
        check_image_name(reference)
        repo, tag = parse_image(reference)
        client.images.pull(repo, tag=tag)  # explicit tag: never pull ALL tags by accident
        lines.append(_ok(f"Pulled {repo}:{tag}"))
    return lines


def cmd_run(client, args, safe_mode):
    spec = parse_run(args)
    check_image_name(spec["image"])
    if safe_mode:
        problem = safe_mode_violation(spec)
        if problem:
            raise DockerCliError(
                f"Blocked by safe-mode: {problem}. (Set DOCKER_SAFE_MODE = False to allow it.)"
            )
    container = run_container(client, spec)
    return [
        _ok(f"Container started: {container.short_id}" + (f" ({spec['name']})" if spec["name"] else "")),
        ("Runs detached (-d). It will show up on the panel in a few seconds.", "gray"),
    ]


def cmd_control(client, command, args):
    timeout = 10
    refs = []
    i = 0
    while i < len(args):
        a = args[i]
        if a in ("-t", "--time") and command != "kill":
            timeout = _int(_value_after(args, i, a), a)
            i += 1
        elif not a.startswith("-"):
            refs.append(a)
        i += 1
    if not refs:
        raise DockerCliError(f"Usage: docker {command} <container...>")

    lines = []
    for ref in refs:
        try:
            container = resolve_container(client, ref)
            if command == "start":
                container.start()
            elif command == "stop":
                container.stop(timeout=timeout)
            elif command == "restart":
                container.restart(timeout=timeout)
            elif command == "kill":
                container.kill()
            elif command == "pause":
                container.pause()
            elif command == "unpause":
                container.unpause()
            lines.append(_ok(f"{command} -> {container.name}"))
        except DockerCliError as error:
            lines.append((f"DockerCraft: {error}", "red"))
        except docker.errors.DockerException as error:
            lines.append((f"DockerCraft: {command} {ref}: {_clean_error(error)}", "red"))
    return lines


def cmd_rm(client, args):
    force = volumes = False
    refs = []
    for a in args:
        if a == "--force":
            force = True
        elif a == "--volumes":
            volumes = True
        elif a.startswith("-") and not a.startswith("--") and len(a) > 1:
            force = force or "f" in a
            volumes = volumes or "v" in a
        elif not a.startswith("-"):
            refs.append(a)
    if not refs:
        raise DockerCliError("Usage: docker rm [-f] [-v] <container...>")

    lines = []
    for ref in refs:
        try:
            container = resolve_container(client, ref)
            container.remove(force=force, v=volumes)
            lines.append(_ok(f"Removed {container.name}"))
        except DockerCliError as error:
            lines.append((f"DockerCraft: {error}", "red"))
        except docker.errors.DockerException as error:
            lines.append((f"DockerCraft: rm {ref}: {_clean_error(error)}", "red"))
    return lines


def cmd_rmi(client, args):
    force = "-f" in args or "--force" in args
    refs = [a for a in args if not a.startswith("-")]
    if not refs:
        raise DockerCliError("Usage: docker rmi [-f] <image...>")
    lines = []
    for ref in refs:
        try:
            client.images.remove(image=ref, force=force)
            lines.append(_ok(f"Removed image {ref}"))
        except docker.errors.DockerException as error:
            lines.append((f"DockerCraft: rmi {ref}: {_clean_error(error)}", "red"))
    return lines


def cmd_logs(client, args, default_lines):
    lines_wanted = default_lines
    ref = None
    i = 0
    while i < len(args):
        a = args[i]
        if a in ("--tail", "-n"):
            lines_wanted = _int(_value_after(args, i, a), a)
            i += 1
        elif a.startswith("--tail="):
            lines_wanted = _int(a.split("=", 1)[1], "--tail")
        elif not a.startswith("-"):
            ref = a  # -f / --timestamps ... are accepted and ignored
        i += 1
    if ref is None:
        raise DockerCliError("Usage: docker logs [--tail N] <container>")

    lines_wanted = max(1, min(lines_wanted, 200))
    container = resolve_container(client, ref)
    raw = container.logs(tail=lines_wanted, timestamps=True, stdout=True, stderr=True)
    log = [ANSI.sub("", line) for line in raw.decode("utf-8", errors="replace").splitlines() if line.strip()]

    out = [_title(f"DOCKER LOGS | {container.name}", f"last {len(log)} lines")]
    if not log:
        out.append(("No logs available.", "gray"))
    out.extend((f"| {line[:250]}", "white") for line in log)
    return out


def cmd_exec(client, args):
    i = 0
    while i < len(args) and args[i].startswith("-"):
        i += 1  # -i, -t, -it ... (no TTY in chat)
    if i >= len(args) - 1:
        raise DockerCliError("Usage: docker exec <container> <command...>")
    container = resolve_container(client, args[i])
    command = args[i + 1:]
    exit_code, output = container.exec_run(command)
    text = (output or b"").decode("utf-8", errors="replace")
    out = [_title(f"DOCKER EXEC | {container.name}", " ".join(command))]
    result = [ANSI.sub("", line) for line in text.splitlines() if line.strip()]
    if not result:
        out.append(("(no output)", "gray"))
    out.extend((f"| {line[:250]}", "white") for line in result)
    if exit_code not in (0, None):
        out.append((f"exit code: {exit_code}", "red"))
    return out


def cmd_inspect(client, args):
    if not args:
        raise DockerCliError("Usage: docker inspect <container>")
    c = resolve_container(client, args[0])
    attrs = c.attrs
    state = attrs.get("State", {})
    networks = attrs.get("NetworkSettings", {}).get("Networks", {}) or {}
    mounts = [
        f"{m.get('Source') or m.get('Name')} -> {m.get('Destination')}" for m in attrs.get("Mounts", [])
    ]
    out = [
        _title(f"DOCKER INSPECT | {c.name}"),
        (f"ID: {attrs.get('Id')}", "white"),
        (f"Image: {attrs.get('Config', {}).get('Image', '?')}", "white"),
        (f"Created: {attrs.get('Created')}", "white"),
        (f"State: {state.get('Status')}  (started {state.get('StartedAt')})", "white"),
        (f"Restart: {attrs.get('HostConfig', {}).get('RestartPolicy', {}).get('Name') or 'no'}", "white"),
    ]
    for name, net in networks.items():
        out.append((f"Network {name}: {net.get('IPAddress') or '-'}", "white"))
    out.append((f"Ports: {', '.join(_port_list(c)) or 'none'}", "white"))
    out.append((f"Volumes: {', '.join(mounts) or 'none'}", "white"))
    return out


def cmd_network(client, args):
    if not args or args[0] not in ("ls", "list"):
        raise DockerCliError("Usage: docker network ls")
    networks = client.networks.list()
    out = [_title("DOCKER NETWORKS", f"{len(networks)} networks")]
    out.extend(
        (f"{n.short_id} {n.name}  {n.attrs.get('Driver')}/{n.attrs.get('Scope')}", "white") for n in networks
    )
    return out


def cmd_volume(client, args):
    if not args or args[0] not in ("ls", "list"):
        raise DockerCliError("Usage: docker volume ls")
    volumes = client.volumes.list()
    out = [_title("DOCKER VOLUMES", f"{len(volumes)} volumes")]
    out.extend((f"{v.name}  {v.attrs.get('Driver')}", "white") for v in volumes)
    return out


def cmd_version(client):
    v = client.version()
    return [
        _title("DOCKER VERSION"),
        (f"Engine: {v.get('Version')}", "white"),
        (f"API: {v.get('ApiVersion')}", "white"),
        (f"OS/Arch: {v.get('Os')}/{v.get('Arch')}", "white"),
    ]


def cmd_info(client):
    i = client.info()
    return [
        _title("DOCKER INFO"),
        (f"Server version: {i.get('ServerVersion')}", "white"),
        (f"Containers: {i.get('Containers')} ({i.get('ContainersRunning')} running)", "white"),
        (f"Images: {i.get('Images')}", "white"),
        (f"Operating system: {i.get('OperatingSystem')}", "white"),
    ]


# ============================================================
# ENTRY POINT
# ============================================================

def execute_docker_command(client, tokens, safe_mode=True, log_lines=20):
    """
    Runs one docker command. BLOCKING: call it from a thread (run_in_executor).

    tokens:    the command, with or without the leading "docker" (e.g. ["run", "-d", "nginx"]).
    safe_mode: refuse dangerous `docker run` options (privileged, host mounts...).
    Returns (command, lines): the canonical command name and a list of (text, color).
    """
    args = normalize(tokens)
    if not args or args[0] == "help":
        return "help", help_lines()

    command, rest = args[0], args[1:]
    if command not in COMMANDS:
        return command, [(f"DockerCraft: '{command}' is not a supported command. Try: docker help", "red")]

    try:
        if command == "ps":
            lines = cmd_ps(client, rest)
        elif command == "images":
            lines = cmd_images(client, rest)
        elif command == "pull":
            lines = cmd_pull(client, rest)
        elif command == "run":
            lines = cmd_run(client, rest, safe_mode)
        elif command in ("start", "stop", "restart", "kill", "pause", "unpause"):
            lines = cmd_control(client, command, rest)
        elif command == "rm":
            lines = cmd_rm(client, rest)
        elif command == "rmi":
            lines = cmd_rmi(client, rest)
        elif command == "logs":
            lines = cmd_logs(client, rest, log_lines)
        elif command == "exec":
            lines = cmd_exec(client, rest)
        elif command == "inspect":
            lines = cmd_inspect(client, rest)
        elif command == "network":
            lines = cmd_network(client, rest)
        elif command == "volume":
            lines = cmd_volume(client, rest)
        elif command == "version":
            lines = cmd_version(client)
        else:
            lines = cmd_info(client)
    except DockerCliError as error:
        lines = [(f"DockerCraft: {error}", "red")]
    except docker.errors.DockerException as error:
        lines = [(f"DockerCraft: docker {command}: {_clean_error(error)}", "red")]

    return command, lines
