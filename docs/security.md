# Security

## Threat model
DockerCraft: Reloaded can start, stop and restart containers and read their logs. Access to the Docker
socket is roughly root on the host. Assume that **anyone allowed to control is a server administrator**.

## Recommendations
1. **Least privilege**: give `dockercraft.control` only to trusted OPs. In the Python script,
   fill in `ALLOWED_PLAYERS` (empty = anyone can control).

2. **RCON** (Python script): long, unique password; keep port 25575 closed to the outside.
3. **Secrets**: the original script has `RCON_PASSWORD` in the code.
4. **Docker commands** (`/docker run`, `docker exec`...): `dockercraft.docker.run` is effectively root on the host,
   because a container can mount `/` or run `--privileged`. `commands.safe-mode: true` (default) blocks the obvious
   escapes (`--privileged`, `--cap-add`, `--device`, `--pid host`, `--network host`, host-path bind mounts) but it is a
   guard rail, not a sandbox: still grant the permission only to trusted OPs. Every state-changing command is logged
   with the player's name. In the Python script, chat commands are off unless `CHAT_LOG_PATH` is set, only work for
   `COMMAND_PLAYERS`, and are visible to every player in the public chat; the terminal is trusted.
5. **Logs**: LOGS shows container output to anyone with `dockercraft.view` (everyone by default).
   Logs can contain sensitive data; remove that permission by default if it is a concern.

## Reporting vulnerabilities
Open a private *Security Advisory* on GitHub (Settings → Security) instead of a public Issue.
