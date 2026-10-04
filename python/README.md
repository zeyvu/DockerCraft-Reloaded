# python/ — Python script (RCON)

`dockercraft_reloaded.py` connects to server over RCON and handles Docker panels.
`dockercraft_cli.py` docker command interpreter (keep both files together).

```bash
pip install docker rcon
python dockercraft_reloaded.py
```

All settings (RCON host/port/password, coordinates, allowed players…) are constants at the
top of `dockercraft_reloaded.py`.

## Docker commands

### From the terminal (always on)
Type in the console where the script is running, with or without the leading `docker`:

```
docker ps            docker ps -a         docker images        docker image ls
docker pull nginx    docker run -d --name web -p 8080:80 nginx
docker stop web      docker logs --tail 30 web      docker rm -f web
docker exec web ls /     docker inspect web     docker network ls     help
```
The terminal is trusted so `docker run` has no restrictions there.

### From the Minecraft chat (opt-in)
RCON cannot read player chat, so the script follows the server log. Configure:

```python
CHAT_LOG_PATH = "/srv/minecraft/logs/latest.log"   # None = disabled
COMMAND_PLAYERS = {"Steve", "Alex"}                # empty = nobody
```
Allowed players then write `!docker ps`, `!dc images`, `!docker run -d -p 8080:80 nginx`...
and get the answer by `tellraw`. Chat commands are subject to `DOCKER_SAFE_MODE = True`
(no `--privileged`, `--cap-add`, `--device`, `--pid host`, `--network host`, host-path bind mounts).

> Chat is public: every player sees what you type (including `-e PASSWORD=...`).
> For private commands use the terminal or the Paper plugin (`/docker ...`).

Supported: `ps images pull run start stop restart kill pause unpause rm rmi logs exec inspect
network ls volume ls version info help`. `docker run` options: `-d --name -p -v -e --rm --restart
--network -w -u -m --entrypoint -l -h --privileged --cap-add --device --pid -P --read-only`.
Containers are always started detached.

## Upgrading from McDocker
Tags and scoreboards were renamed (`mcdocker_*` → `dockercraft_*`, `mcd_*` → `dc_*`). Panels drawn by the
old version are not recognised any more: remove them once with
`/kill @e[tag=mcdocker_card]` and `/kill @e[tag=mcdocker_button]`, then restart the script.
(The Paper plugin cleans the old entities by itself on `/dockercraft rebuild`.)
