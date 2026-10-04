import asyncio
import json
import os
import shlex
import re
import sys
import threading

import docker
from rcon.source import rcon

from dockercraft_cli import (
    DockerCliError,
    READ_ONLY,
    execute_docker_command,
    tokenize,
)


# ============================================================
# DOCKERCRAFT: RELOADED - MINECRAFT MANAGER
# ============================================================
#
#
# Controls:
#
#   INFO | START | STOP | RESTART | LOGS
#
# Docker commands (see "DOCKER COMMANDS" below):
#
#   - Type them in THIS terminal:        docker ps | docker run -d nginx | ...
#   - Or in the Minecraft chat (opt-in): !docker ps | !dc pull nginx
#
# Each container generates:
#
#   - A 7x9 BLACK CONCRETE wall
#   - Armor stands with information
#   - Control buttons
#   - Interaction entities to detect clicks
#
# ============================================================


# ============================================================
# RCON CONFIGURATION
# ============================================================

RCON_HOST = "127.0.0.1"
RCON_PORT = 25575
RCON_PASSWORD = "EXAMPLE"


# ============================================================
# INTERVALS
# ============================================================

INTERACTION_INTERVAL = 0.20

SYNC_INTERVAL = 5


# ============================================================
# LOGS
# ============================================================

LOG_LINES = 20


# ============================================================
# MANAGER POSITION
# ============================================================

BASE_X = 0
BASE_Z = 0

BUTTON_Y = -60


# ============================================================
# SPACING BETWEEN CONTAINERS
# ============================================================

CARD_SPACING = 7


# ============================================================
# WALL OF EACH CONTAINER
# ============================================================

WALL_WIDTH = 7

WALL_HEIGHT = 8

WALL_BLOCK = "minecraft:black_concrete"

WALL_Z = BASE_Z


# ============================================================
# TEXT POSITION
# ============================================================

LINE_SPACING = 0.30


# ============================================================
# CONTROL BLOCKS
# ============================================================

BUTTON_INFO = "minecraft:blue_stained_glass"

BUTTON_START = "minecraft:green_stained_glass"

BUTTON_STOP = "minecraft:red_stained_glass"

BUTTON_RESTART = "minecraft:yellow_stained_glass"

BUTTON_LOGS = "minecraft:purple_stained_glass"


# ============================================================
# SCOREBOARDS
# ============================================================

SCORE_ACTION = "dc_action"

SCORE_CONTAINER = "dc_container"

SCORE_REQUEST = "dc_request"

SCORE_RESULT = "dc_result"


# ============================================================
# ACTIONS
# ============================================================

ACTION_NONE = 0

ACTION_INFO = 1

ACTION_START = 2

ACTION_STOP = 3

ACTION_RESTART = 4

ACTION_LOGS = 5


# ============================================================
# RESULTS
# ============================================================

RESULT_NONE = 0

RESULT_SUCCESS = 1

RESULT_ERROR = 2


# ============================================================
# ALLOWED PLAYERS
# ============================================================

ALLOWED_PLAYERS = set()


# ============================================================
# DOCKER COMMANDS
# ============================================================
#
# docker ps | docker images | docker pull X | docker run X | docker logs X ...
#
# 1) TERMINAL: just type the command in the console where this script
#    is running (with or without the leading "docker"). The terminal
#    is trusted: you already have access to Docker, so safe-mode does
#    not apply there.
#
# 2) MINECRAFT CHAT (optional): RCON cannot read player chat, so the
#    script reads the server log instead. Set CHAT_LOG_PATH to your
#    server's logs/latest.log and list the players who may use it in
#    COMMAND_PLAYERS (empty = nobody). Players then write:
#
#        !docker ps        !dc images        !docker run -d --name web -p 8080:80 nginx
#
#    WARNING: chat is public. Everything typed (including -e SECRET=...)
#    is visible to every player. For private commands use the Paper plugin
#    (/docker ...) or the terminal.
#
# ============================================================

DOCKER_COMMANDS_ENABLED = True

# Path to the Minecraft server log, e.g. "/srv/minecraft/logs/latest.log".
# None = chat commands disabled (terminal commands still work).
CHAT_LOG_PATH = None

# Players allowed to run docker commands from the chat (case-insensitive).
COMMAND_PLAYERS = set()

# Chat messages starting with one of these are treated as docker commands.
CHAT_COMMAND_PREFIXES = ("!docker", "!dc")

# True = chat commands cannot run `docker run` with --privileged, --cap-add,
# --device, --pid host, --network host or host-path bind mounts.
DOCKER_SAFE_MODE = True

# How many chat lines each tellraw packet may carry (RCON packets are small).
CHAT_PACKET_LIMIT = 900


# ============================================================
# CONTROLS
# ============================================================

CONTROLS = [

    {
        "offset": 0,
        "block": BUTTON_INFO,
        "text": "INFO",
        "color": "white",
        "action": "info",
        "action_id": ACTION_INFO,
    },

    {
        "offset": 1,
        "block": BUTTON_START,
        "text": "START",
        "color": "white",
        "action": "start",
        "action_id": ACTION_START,
    },

    {
        "offset": 2,
        "block": BUTTON_STOP,
        "text": "STOP",
        "color": "white",
        "action": "stop",
        "action_id": ACTION_STOP,
    },

    {
        "offset": 3,
        "block": BUTTON_RESTART,
        "text": "RESTART",
        "color": "black",
        "action": "restart",
        "action_id": ACTION_RESTART,
    },

    {
        "offset": 4,
        "block": BUTTON_LOGS,
        "text": "LOGS",
        "color": "white",
        "action": "logs",
        "action_id": ACTION_LOGS,
    },

]


# ============================================================
# RCON
# ============================================================

async def minecraft_command(command: str):

    return await rcon(
        command,
        host=RCON_HOST,
        port=RCON_PORT,
        passwd=RCON_PASSWORD,
    )


# ============================================================
# DOCKER
# ============================================================

def get_docker_client():

    return docker.from_env()


def get_containers(client):

    return client.containers.list(
        all=True
    )


def get_container_image(container):

    tags = container.image.tags

    if tags:

        return tags[0]

    return container.attrs.get(
        "Config",
        {}
    ).get(
        "Image",
        "unknown"
    )


# ============================================================
# PORTS
# ============================================================

def get_container_ports(container):

    ports = container.attrs.get(
        "NetworkSettings",
        {}
    ).get(
        "Ports",
        {}
    )

    result = []

    if not ports:

        return result

    for container_port, bindings in ports.items():

        if not bindings:

            result.append(
                f"{container_port} -> not published"
            )

            continue

        for binding in bindings:

            host_ip = binding.get(
                "HostIp",
                ""
            )

            host_port = binding.get(
                "HostPort",
                ""
            )

            if (
                host_ip
                and
                host_ip != "0.0.0.0"
            ):

                result.append(
                    f"{container_port} -> "
                    f"{host_ip}:{host_port}"
                )

            else:

                result.append(
                    f"{container_port} -> "
                    f"{host_port}"
                )

    return result


# ============================================================
# VOLUMES
# ============================================================

def get_container_volumes(container):

    mounts = container.attrs.get(
        "Mounts",
        []
    )

    result = []

    for mount in mounts:

        source = mount.get(
            "Source",
            "unknown"
        )

        destination = mount.get(
            "Destination",
            "unknown"
        )

        mode = mount.get(
            "Mode",
            ""
        )

        if mode:

            result.append(
                f"{source} -> "
                f"{destination} ({mode})"
            )

        else:

            result.append(
                f"{source} -> "
                f"{destination}"
            )

    return result


# ============================================================
# STATUS
# ============================================================

def get_status_color(status):

    if status == "running":

        return "green"

    if status == "exited":

        return "red"

    if status == "paused":

        return "yellow"

    if status == "restarting":

        return "yellow"

    if status == "created":

        return "aqua"

    if status == "dead":

        return "dark_red"

    return "gray"


# ============================================================
# SYMBOL
# ============================================================

def get_status_symbol(status):

    return "●"


# ============================================================
# TEXT COMPONENT
# ============================================================

def text_component(
    text,
    color="white",
    bold=False
):

    return json.dumps(
        {
            "text": text,
            "color": color,
            "bold": bold
        },
        ensure_ascii=False
    )


# ============================================================
# CREATE ARMOR STAND
# ============================================================

async def create_card_line(
    line_tag,
    container_id,
    x,
    y,
    z,
    text,
    color="white",
    bold=False
):

    custom_name = text_component(
        text,
        color,
        bold
    )

    command = (
        f"summon minecraft:armor_stand "
        f"{x} {y} {z} "
        f"{{"
        f"CustomName:{custom_name},"
        f"CustomNameVisible:1b,"
        f"Invisible:1b,"
        f"Invulnerable:1b,"
        f"NoGravity:1b,"
        f"Marker:1b,"
        f"PersistenceRequired:1b,"
        f"Tags:["
        f"\"dockercraft_card\","
        f"\"dockercraft_id_{container_id}\","
        f"\"{line_tag}\""
        f"]"
        f"}}"
    )

    await minecraft_command(
        command
    )


# ============================================================
# SCOREBOARDS
# ============================================================

async def setup_scoreboards():

    print(
        "[*] Preparing scoreboards..."
    )

    objectives = [

        SCORE_ACTION,

        SCORE_CONTAINER,

        SCORE_REQUEST,

        SCORE_RESULT,

    ]

    for objective in objectives:

        response = await minecraft_command(
            f"scoreboard objectives add "
            f"{objective} dummy"
        )

        print(
            f"    {objective}: {response}"
        )

    print(
        "[+] Scoreboards ready."
    )


# ============================================================
# PLAYER RESET
# ============================================================

async def reset_player_request(
    player
):

    safe_player = shlex.quote(
        player
    )

    await minecraft_command(
        f"scoreboard players set "
        f"{safe_player} "
        f"{SCORE_ACTION} 0"
    )

    await minecraft_command(
        f"scoreboard players set "
        f"{safe_player} "
        f"{SCORE_CONTAINER} 0"
    )

    await minecraft_command(
        f"scoreboard players set "
        f"{safe_player} "
        f"{SCORE_REQUEST} 0"
    )

    await minecraft_command(
        f"scoreboard players set "
        f"{safe_player} "
        f"{SCORE_RESULT} 0"
    )


# ============================================================
# CREATE INTERACTION
# ============================================================

async def create_button_interaction(
    x,
    y,
    z,
    container_id,
    container_index,
    action
):

    short_id = container_id[:12]

    button_tag = (
        f"dockercraft_button_"
        f"{short_id}_"
        f"{action}"
    )

    command = (
        f"summon minecraft:interaction "
        f"{x} {y + 0.25} {z + 0.75} "
        f"{{"
        f"width:1f,"
        f"height:0.55f,"
        f"response:1b,"
        f"Tags:["
        f"\"dockercraft_button\","
        f"\"dockercraft_container_{container_index}\","
        f"\"dockercraft_id_{short_id}\","
        f"\"dockercraft_action_{action}\","
        f"\"{button_tag}\""
        f"]"
        f"}}"
    )

    response = await minecraft_command(
        command
    )

    print(
        f"        Interaction -> "
        f"{action.upper()} | {response}"
    )


# ============================================================
# CHECK CARDS
# ============================================================

async def cards_exist():

    response = await minecraft_command(
        "execute if entity "
        "@e[type=minecraft:armor_stand,tag=dockercraft_card]"
    )

    response = str(
        response
    ).lower()

    if "success" in response:

        return True

    if response.strip() == "1":

        return True

    return False


# ============================================================
# WAIT FOR THE ARMOR STANDS TO DISAPPEAR
# ============================================================

async def wait_until_cards_are_gone(
    timeout=5.0
):

    start_time = asyncio.get_running_loop().time()

    while True:

        exists = await cards_exist()

        if not exists:

            print(
                "    [+] Armor stands removed."
            )

            return True

        elapsed = (
            asyncio.get_running_loop().time()
            -
            start_time
        )

        if elapsed >= timeout:

            print(
                "    [!] Timeout waiting "
                "for the armor stands to disappear."
            )

            return False

        await asyncio.sleep(
            0.10
        )


# ============================================================
# REMOVE SIGN
# ============================================================

async def clear_button_sign(
    x,
    y,
    z
):

    await minecraft_command(
        f"setblock "
        f"{x} {y} {z} "
        f"minecraft:air"
    )


# ============================================================
# REMOVE GLASS
# ============================================================

async def clear_button_block(
    x,
    y,
    z
):

    await minecraft_command(
        f"setblock "
        f"{x} {y} {z} "
        f"minecraft:air"
    )


# ============================================================
# REMOVE WALL
# ============================================================

async def clear_container_wall(
    index
):

    center_x = (
        BASE_X +
        (
            index *
            CARD_SPACING
        )
    )

    start_x = (
        center_x -
        (
            WALL_WIDTH // 2
        )
    )

    end_x = (
        start_x +
        WALL_WIDTH -
        1
    )

    start_y = BUTTON_Y

    end_y = (
        BUTTON_Y +
        WALL_HEIGHT -
        1
    )

    response = await minecraft_command(
        f"fill "
        f"{start_x} "
        f"{start_y} "
        f"{WALL_Z} "
        f"{end_x} "
        f"{end_y} "
        f"{WALL_Z} "
        f"minecraft:air"
    )

    print(
        f"    Wall {index}: {response}"
    )


# ============================================================
# REMOVE ELEMENTS
# ============================================================
#
# ORDER MATTERS:
#
#   1. Armor stands
#   2. Wait for them to disappear
#   3. Signs
#   4. Glass blocks
#   5. Walls
#
# container_count must be the number of positions that
# could exist BEFORE the rebuild.
#
# ============================================================

async def clear_all_cards(
    container_count=0
):

    print()
    print(
        "[*] Removing previous elements..."
    )

    print(
        f"    Positions to clear: "
        f"{container_count}"
    )

    # ========================================================
    # 1. ARMOR STANDS
    # ========================================================

    print()
    print(
        "[1/5] Removing armor stands..."
    )

    response = await minecraft_command(
        "kill @e["
        "type=minecraft:armor_stand,"
        "tag=dockercraft_card"
        "]"
    )

    print(
        f"    Armor stands: {response}"
    )

    # ========================================================
    # 2. WAIT UNTIL THEY ARE REALLY GONE
    # ========================================================

    print(
        "[2/5] Checking that the armor stands "
        "no longer exist..."
    )

    await wait_until_cards_are_gone()

    # ========================================================
    # INTERACTIONS
    # ========================================================
    #
    # Interactions are removed after the armor stands
    # and before touching the blocks.
    #
    # ========================================================

    response = await minecraft_command(
        "kill @e["
        "type=minecraft:interaction,"
        "tag=dockercraft_button"
        "]"
    )

    print(
        f"    Interaction entities: {response}"
    )

    # ========================================================
    # 3. SIGNS
    # ========================================================

    print()
    print(
        "[3/5] Removing signs..."
    )

    for index in range(
        container_count
    ):

        center_x = (
            BASE_X +
            (
                index *
                CARD_SPACING
            )
        )

        center_z = (
            BASE_Z +
            1
        )

        sign_y = BUTTON_Y

        # Signs are created at:
        #
        # x = center + offset
        # y = BUTTON_Y
        # z = center_z + 1
        #
        # Therefore:
        #
        # z = BASE_Z + 2

        for control in CONTROLS:

            offset = (
                control["offset"] - 2
            )

            x = (
                center_x +
                offset
            )

            z = (
                center_z +
                1
            )

            await clear_button_sign(
                x,
                sign_y,
                z
            )

    print(
        "    [+] Signs removed."
    )

    # ========================================================
    # 4. GLASS BLOCKS
    # ========================================================

    print()
    print(
        "[4/5] Removing button glass blocks..."
    )

    for index in range(
        container_count
    ):

        center_x = (
            BASE_X +
            (
                index *
                CARD_SPACING
            )
        )

        center_z = (
            BASE_Z +
            1
        )

        for control in CONTROLS:

            offset = (
                control["offset"] - 2
            )

            x = (
                center_x +
                offset
            )

            z = center_z

            await clear_button_block(
                x,
                BUTTON_Y,
                z
            )

    print(
        "    [+] Glass blocks removed."
    )

    # ========================================================
    # 5. WALLS
    # ========================================================

    print()
    print(
        "[5/5] Removing walls..."
    )

    for index in range(
        container_count
    ):

        await clear_container_wall(
            index
        )

    print(
        "    [+] Walls removed."
    )

    print()
    print(
        "[+] Cleanup completed."
    )
    print()


# ============================================================
# CREATE WALL
# ============================================================

async def create_container_wall(
    container,
    index
):

    center_x = (
        BASE_X +
        (
            index *
            CARD_SPACING
        )
    )

    start_x = (
        center_x -
        (
            WALL_WIDTH // 2
        )
    )

    end_x = (
        start_x +
        WALL_WIDTH -
        1
    )

    start_y = BUTTON_Y

    end_y = (
        BUTTON_Y +
        WALL_HEIGHT -
        1
    )

    print()

    print(
        f"[*] Creating wall: "
        f"{container.name}"
    )

    print(
        f"    Size: "
        f"{WALL_WIDTH}x{WALL_HEIGHT}"
    )

    print(
        f"    From: "
        f"({start_x}, {start_y}, {WALL_Z})"
    )

    print(
        f"    To: "
        f"({end_x}, {end_y}, {WALL_Z})"
    )

    response = await minecraft_command(
        f"fill "
        f"{start_x} "
        f"{start_y} "
        f"{WALL_Z} "
        f"{end_x} "
        f"{end_y} "
        f"{WALL_Z} "
        f"{WALL_BLOCK}"
    )

    print(
        f"    Result: {response}"
    )

    print(
        f"[+] Wall created: "
        f"{container.name}"
    )


# ============================================================
# PLACE BLOCK
# ============================================================

async def set_block(
    x,
    y,
    z,
    block
):

    await minecraft_command(
        f"setblock "
        f"{x} {y} {z} "
        f"{block}"
    )


# ============================================================
# CREATE SIGN
# ============================================================

async def create_button_sign(
    x,
    y,
    z,
    text,
    color="white"
):

    text_component_snbt = (
        "{"
        f"text:'{text}',"
        f"color:'{color}',"
        "bold:true"
        "}"
    )

    empty_component = (
        "{text:''}"
    )

    command = (
        f"setblock "
        f"{x} {y} {z} "
        f"minecraft:oak_wall_sign"
        f"[facing=south]"
        f"{{"
        f"front_text:{{"
        f"messages:["
        f"{text_component_snbt},"
        f"{empty_component},"
        f"{empty_component},"
        f"{empty_component}"
        f"]"
        f"}}"
        f"}}"
    )

    await minecraft_command(
        command
    )


# ============================================================
# CREATE BUTTON
# ============================================================

async def create_button(
    x,
    y,
    z,
    block,
    text,
    color,
    container_id,
    container_index,
    action
):

    # ========================================================
    # GLASS BLOCK
    # ========================================================

    await set_block(
        x,
        y,
        z,
        block
    )

    # ========================================================
    # SIGN
    # ========================================================

    await create_button_sign(
        x,
        y,
        z + 1,
        text,
        color
    )

    # ========================================================
    # INTERACTION
    # ========================================================

    await create_button_interaction(
        x,
        y,
        z,
        container_id,
        container_index,
        action
    )


# ============================================================
# CREATE CONTROLS
# ============================================================

async def create_container_buttons(
    container,
    index,
    button_y
):

    container_id = container.id

    center_x = (
        BASE_X +
        (
            index *
            CARD_SPACING
        )
    )

    center_z = BASE_Z + 1

    print()

    print(
        f"[*] Building controls: "
        f"{container.name}"
    )

    for control in CONTROLS:

        offset = (
            control["offset"] - 2
        )

        x = (
            center_x +
            offset
        )

        z = center_z

        text = control["text"]

        block = control["block"]

        color = control["color"]

        action = control["action"]

        print(
            f"    [+] "
            f"{text} -> "
            f"({x}, {button_y}, {z})"
        )

        await create_button(
            x,
            button_y,
            z,
            block,
            text,
            color,
            container_id,
            index,
            action
        )

    print(
        f"[+] Controls created: "
        f"{container.name}"
    )


# ============================================================
# CREATE CARD
# ============================================================

async def create_container_card(
    container,
    index
):

    name = container.name

    status = container.status

    container_id = container.id

    short_id = container_id[:12]

    image = get_container_image(
        container
    )

    ports = get_container_ports(
        container
    )

    volumes = get_container_volumes(
        container
    )

    status_color = get_status_color(
        status
    )

    symbol = get_status_symbol(
        status
    )

    x = (
        BASE_X +
        (
            index *
            CARD_SPACING
        )
    )

    z = BASE_Z + 1

    card_prefix = (
        f"dockercraft_card_{short_id}"
    )

    lines = []

    lines.append(
        (
            f"{card_prefix}_name",
            name.upper(),
            "aqua",
            True
        )
    )

    lines.append(
        (
            f"{card_prefix}_separator",
            "━━━━━━━━━━━━━━━━━━━━",
            "dark_gray",
            False
        )
    )

    lines.append(
        (
            f"{card_prefix}_status",
            f"{symbol} {status.upper()}",
            status_color,
            True
        )
    )

    lines.append(
        (
            f"{card_prefix}_image",
            f"Image: {image}",
            "white",
            False
        )
    )

    lines.append(
        (
            f"{card_prefix}_id",
            f"ID: {short_id}",
            "gray",
            False
        )
    )

    lines.append(
        (
            f"{card_prefix}_technical",
            "──────────────",
            "dark_gray",
            False
        )
    )

    lines.append(
        (
            f"{card_prefix}_ports_title",
            "Ports:",
            "gold",
            True
        )
    )

    if ports:

        for port_index, port in enumerate(
            ports
        ):

            lines.append(
                (
                    f"{card_prefix}_port_{port_index}",
                    f"  {port}",
                    "white",
                    False
                )
            )

    else:

        lines.append(
            (
                f"{card_prefix}_ports_none",
                "  None",
                "gray",
                False
            )
        )

    lines.append(
        (
            f"{card_prefix}_volumes_title",
            "Volumes:",
            "gold",
            True
        )
    )

    if volumes:

        for volume_index, volume in enumerate(
            volumes
        ):

            lines.append(
                (
                    f"{card_prefix}_volume_{volume_index}",
                    f"  {volume}",
                    "white",
                    False
                )
            )

    else:

        lines.append(
            (
                f"{card_prefix}_volumes_none",
                "  None",
                "gray",
                False
            )
        )

    await create_container_buttons(
        container,
        index,
        BUTTON_Y
    )

    text_base_y = (
        BUTTON_Y +
        2
    )

    for line_index, (
        line_tag,
        text,
        color,
        bold
    ) in enumerate(lines):

        reverse_index = (
            len(lines)
            -
            1
            -
            line_index
        )

        line_y = (
            text_base_y
            +
            (
                reverse_index
                *
                LINE_SPACING
            )
        )

        await create_card_line(
            line_tag,
            container_id,
            x,
            line_y,
            z,
            text,
            color,
            bold
        )

    print()

    print(
        f"[+] Card created: "
        f"{name} ({short_id})"
    )

    print(
        f"    Status: {status}"
    )

    print(
        f"    Ports: {len(ports)}"
    )

    print(
        f"    Volumes: {len(volumes)}"
    )


# ============================================================
# DOCKER SIGNATURE
# ============================================================

def get_docker_signature(
    containers
):

    data = []

    for container in containers:

        ports = sorted(
            get_container_ports(
                container
            )
        )

        volumes = sorted(
            get_container_volumes(
                container
            )
        )

        data.append(
            {
                "id": container.id,
                "name": container.name,
                "status": container.status,
                "image": get_container_image(
                    container
                ),
                "ports": ports,
                "volumes": volumes,
            }
        )

    data.sort(
        key=lambda item:
        item["id"]
    )

    return json.dumps(
        data,
        sort_keys=True,
        ensure_ascii=False
    )


# ============================================================
# SYNCHRONIZE
# ============================================================

async def synchronize(
    containers,
    clear_count=None
):

    print()

    print(
        "======================================"
    )

    print(
        "[*] SYNCHRONIZING MINECRAFT"
    )

    print(
        "======================================"
    )

    print()

    if clear_count is None:

        clear_count = len(
            containers
        )

    # ========================================================
    # FULL CLEANUP
    # ========================================================
    #
    # IMPORTANT:
    # clear_count is the number of positions that
    # could exist BEFORE this rebuild.
    #
    # If there were 10 and now there are 7:
    #
    #   clear_count = 10
    #
    # All 10 positions are cleared.
    #
    # Then only the 7 current ones are created.
    #
    # ========================================================

    await clear_all_cards(
        clear_count
    )

    # ========================================================
    # CREATE EACH CURRENT CONTAINER
    # ========================================================

    print(
        f"[*] Creating "
        f"{len(containers)} "
        f"current containers..."
    )

    for index, container in enumerate(
        containers
    ):

        # The wall first.

        await create_container_wall(
            container,
            index
        )

        # Then texts and controls.

        await create_container_card(
            container,
            index
        )

    print()

    print(
        "[+] Minecraft updated."
    )

    print()


# ============================================================
# SHOW CONTAINERS
# ============================================================

def print_containers(
    containers
):

    print()

    print(
        "Docker containers:"
    )

    print()

    if not containers:

        print(
            "    No containers."
        )

        return

    for container in containers:

        print(
            f"- "
            f"{container.name} | "
            f"{container.status} | "
            f"{container.id[:12]} | "
            f"{get_container_image(container)}"
        )


# ============================================================
# INFO TERMINAL
# ============================================================

def print_container_info(
    container
):

    image = get_container_image(
        container
    )

    ports = get_container_ports(
        container
    )

    volumes = get_container_volumes(
        container
    )

    print()

    print(
        "======================================"
    )

    print(
        f"        INFO: {container.name}"
    )

    print(
        "======================================"
    )

    print(
        f"Name:      {container.name}"
    )

    print(
        f"ID:        {container.id}"
    )

    print(
        f"Short ID:  {container.id[:12]}"
    )

    print(
        f"Status:    {container.status}"
    )

    print(
        f"Image:     {image}"
    )

    print()

    print(
        "Ports:"
    )

    if ports:

        for port in ports:

            print(
                f"  - {port}"
            )

    else:

        print(
            "  - None"
        )

    print()

    print(
        "Volumes:"
    )

    if volumes:

        for volume in volumes:

            print(
                f"  - {volume}"
            )

    else:

        print(
            "  - None"
        )

    print()

    print(
        "======================================"
    )

    print()


# ============================================================
# INFO CHAT
# ============================================================

async def send_container_info_to_player(
    player,
    container
):

    image = get_container_image(
        container
    )

    ports = get_container_ports(
        container
    )

    volumes = get_container_volumes(
        container
    )

    status = container.status

    status_color = get_status_color(
        status
    )

    safe_player = shlex.quote(
        player
    )

    header = json.dumps(
        [
            {
                "text": "══════════════════════════════\n",
                "color": "dark_aqua"
            },
            {
                "text": " DOCKERCRAFT | ",
                "color": "aqua",
                "bold": True
            },
            {
                "text": container.name,
                "color": "white",
                "bold": True
            },
            {
                "text": "\n══════════════════════════════",
                "color": "dark_aqua"
            }
        ],
        ensure_ascii=False
    )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f"{header}"
    )

    status_message = json.dumps(
        [
            {
                "text": "Status: ",
                "color": "gray"
            },
            {
                "text": status.upper(),
                "color": status_color,
                "bold": True
            }
        ],
        ensure_ascii=False
    )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f"{status_message}"
    )

    id_message = json.dumps(
        [
            {
                "text": "ID: ",
                "color": "gray"
            },
            {
                "text": container.id[:12],
                "color": "white"
            }
        ],
        ensure_ascii=False
    )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f"{id_message}"
    )

    image_message = json.dumps(
        [
            {
                "text": "Image: ",
                "color": "gray"
            },
            {
                "text": image,
                "color": "white"
            }
        ],
        ensure_ascii=False
    )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f"{image_message}"
    )

    ports_header = json.dumps(
        [
            {
                "text": "Ports:",
                "color": "gold",
                "bold": True
            }
        ],
        ensure_ascii=False
    )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f"{ports_header}"
    )

    if ports:

        for port in ports:

            message = json.dumps(
                [
                    {
                        "text": "  • ",
                        "color": "dark_gray"
                    },
                    {
                        "text": port,
                        "color": "white"
                    }
                ],
                ensure_ascii=False
            )

            await minecraft_command(
                f"tellraw "
                f"{safe_player} "
                f"{message}"
            )

    else:

        await minecraft_command(
            f"tellraw "
            f"{safe_player} "
            f'{{"text":"  • None","color":"gray"}}'
        )

    volumes_header = json.dumps(
        [
            {
                "text": "Volumes:",
                "color": "gold",
                "bold": True
            }
        ],
        ensure_ascii=False
    )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f"{volumes_header}"
    )

    if volumes:

        for volume in volumes:

            message = json.dumps(
                [
                    {
                        "text": "  • ",
                        "color": "dark_gray"
                    },
                    {
                        "text": volume,
                        "color": "white"
                    }
                ],
                ensure_ascii=False
            )

            await minecraft_command(
                f"tellraw "
                f"{safe_player} "
                f"{message}"
            )

    else:

        await minecraft_command(
            f"tellraw "
            f"{safe_player} "
            f'{{"text":"  • None","color":"gray"}}'
        )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f'{{"text":"══════════════════════════════","color":"dark_aqua"}}'
    )


# ============================================================
# GET LOGS
# ============================================================

def get_container_logs(
    container,
    lines=LOG_LINES
):

    try:

        raw_logs = container.logs(
            tail=lines,
            timestamps=True,
            stdout=True,
            stderr=True
        )

        if isinstance(
            raw_logs,
            bytes
        ):

            raw_logs = raw_logs.decode(
                "utf-8",
                errors="replace"
            )

        else:

            raw_logs = str(
                raw_logs
            )

        return raw_logs.splitlines()

    except Exception as error:

        print()

        print(
            "[DOCKER] Error getting logs:"
        )

        print(
            error
        )

        return [
            f"ERROR: {error}"
        ]


# ============================================================
# CLEAN LOG FOR MINECRAFT
# ============================================================

def clean_log_line(
    line
):

    line = str(
        line
    )

    line = re.sub(
        r"\x1b\[[0-9;]*m",
        "",
        line
    )

    line = "".join(
        char
        for char in line
        if char.isprintable()
        or char == "\t"
    )

    return line


# ============================================================
# LOGS IN CHAT
# ============================================================

async def send_container_logs_to_player(
    player,
    container
):

    safe_player = shlex.quote(
        player
    )

    logs = get_container_logs(
        container,
        LOG_LINES
    )

    header = json.dumps(
        [
            {
                "text": "══════════════════════════════\n",
                "color": "dark_purple"
            },
            {
                "text": " DOCKERCRAFT | LOGS | ",
                "color": "light_purple",
                "bold": True
            },
            {
                "text": container.name,
                "color": "white",
                "bold": True
            },
            {
                "text": f"\n Last {LOG_LINES} lines",
                "color": "gray"
            },
            {
                "text": "\n══════════════════════════════",
                "color": "dark_purple"
            }
        ],
        ensure_ascii=False
    )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f"{header}"
    )

    if not logs:

        await minecraft_command(
            f"tellraw "
            f"{safe_player} "
            f'{{"text":"No logs available.","color":"gray"}}'
        )

    else:

        for line in logs:

            line = clean_log_line(
                line
            )

            if len(line) > 250:

                line = (
                    line[:247]
                    +
                    "..."
                )

            message = json.dumps(
                [
                    {
                        "text": "│ ",
                        "color": "dark_purple"
                    },
                    {
                        "text": line,
                        "color": "white"
                    }
                ],
                ensure_ascii=False
            )

            await minecraft_command(
                f"tellraw "
                f"{safe_player} "
                f"{message}"
            )

    await minecraft_command(
        f"tellraw "
        f"{safe_player} "
        f'{{"text":"══════════════════════════════","color":"dark_purple"}}'
    )


# ============================================================
# RUN DOCKER ACTION
# ============================================================

def execute_docker_action(
    container,
    action
):

    try:

        if action == ACTION_INFO:

            print_container_info(
                container
            )

            return True

        if action == ACTION_START:

            print(
                f"[DOCKER] START -> "
                f"{container.name}"
            )

            container.start()

            container.reload()

            print(
                f"[DOCKER] START OK -> "
                f"{container.name}"
            )

            return True

        if action == ACTION_STOP:

            print(
                f"[DOCKER] STOP -> "
                f"{container.name}"
            )

            container.stop()

            container.reload()

            print(
                f"[DOCKER] STOP OK -> "
                f"{container.name}"
            )

            return True

        if action == ACTION_RESTART:

            print(
                f"[DOCKER] RESTART -> "
                f"{container.name}"
            )

            container.restart()

            container.reload()

            print(
                f"[DOCKER] RESTART OK -> "
                f"{container.name}"
            )

            return True

        if action == ACTION_LOGS:

            print(
                f"[DOCKER] LOGS -> "
                f"{container.name}"
            )

            return True

        print(
            f"[DOCKER] Unknown action: "
            f"{action}"
        )

        return False

    except Exception as error:

        print()

        print(
            "======================================"
        )

        print(
            "[DOCKER ERROR]"
        )

        print(
            f"Container: {container.name}"
        )

        print(
            f"Action:    {action}"
        )

        print(
            f"Error:     {error}"
        )

        print(
            "======================================"
        )

        print()

        return False


# ============================================================
# PROCESS ONE INTERACTION
# ============================================================

async def process_interaction_tag(
    tag,
    action,
    container_index
):

    command = (
        f"execute as "
        f"@e[type=minecraft:interaction,"
        f"tag={tag}] "
        f"if data entity @s interaction "
        f"on target run "
        f"scoreboard players set "
        f"@s {SCORE_ACTION} {action}"
    )

    await minecraft_command(
        command
    )

    command = (
        f"execute as "
        f"@e[type=minecraft:interaction,"
        f"tag={tag}] "
        f"if data entity @s interaction "
        f"on target run "
        f"scoreboard players set "
        f"@s {SCORE_CONTAINER} "
        f"{container_index}"
    )

    await minecraft_command(
        command
    )

    command = (
        f"execute as "
        f"@e[type=minecraft:interaction,"
        f"tag={tag}] "
        f"if data entity @s interaction "
        f"on target run "
        f"scoreboard players set "
        f"@s {SCORE_REQUEST} 1"
    )

    await minecraft_command(
        command
    )

    command = (
        f"execute as "
        f"@e[type=minecraft:interaction,"
        f"tag={tag}] "
        f"if data entity @s interaction "
        f"run data remove entity "
        f"@s interaction"
    )

    await minecraft_command(
        command
    )


# ============================================================
# DETECT INTERACTIONS
# ============================================================

async def detect_interactions(
    containers
):

    for index, container in enumerate(
        containers
    ):

        short_id = container.id[:12]

        for control in CONTROLS:

            action_name = control[
                "action"
            ]

            action_id = control[
                "action_id"
            ]

            tag = (
                f"dockercraft_button_"
                f"{short_id}_"
                f"{action_name}"
            )

            await process_interaction_tag(
                tag,
                action_id,
                index
            )


# ============================================================
# ONLINE PLAYERS
# ============================================================

async def get_online_players():

    response = await minecraft_command(
        "list"
    )

    response = str(
        response
    ).strip()

    if not response:

        return []

    if ":" not in response:

        return []

    players_part = (
        response.rsplit(
            ":",
            1
        )[1]
        .strip()
    )

    if not players_part:

        return []

    players = []

    for player in players_part.split(","):

        player = player.strip()

        if player:

            players.append(
                player
            )

    return players


# ============================================================
# READ SCOREBOARD
# ============================================================

async def get_player_score(
    player,
    objective
):

    safe_player = shlex.quote(
        player
    )

    response = await minecraft_command(
        f"scoreboard players get "
        f"{safe_player} "
        f"{objective}"
    )

    response = str(
        response
    )

    match = re.search(
        r"\bhas\s+(-?\d+)",
        response
    )

    if not match:

        return 0

    return int(
        match.group(1)
    )


# ============================================================
# READ REQUEST
# ============================================================

async def get_player_request(
    player
):

    action = await get_player_score(
        player,
        SCORE_ACTION
    )

    container_index = await get_player_score(
        player,
        SCORE_CONTAINER
    )

    request = await get_player_score(
        player,
        SCORE_REQUEST
    )

    return (
        action,
        container_index,
        request
    )


# ============================================================
# RESULT
# ============================================================

async def set_player_result(
    player,
    result
):

    safe_player = shlex.quote(
        player
    )

    await minecraft_command(
        f"scoreboard players set "
        f"{safe_player} "
        f"{SCORE_RESULT} "
        f"{result}"
    )


# ============================================================
# SIMPLE MESSAGE
# ============================================================

async def tell_player(
    player,
    message,
    color="white"
):

    component = json.dumps(
        {
            "text": message,
            "color": color
        },
        ensure_ascii=False
    )

    await minecraft_command(
        f"tellraw "
        f"{shlex.quote(player)} "
        f"{component}"
    )


# ============================================================
# PROCESS REQUESTS
# ============================================================

async def process_player_requests(
    client,
    containers
):

    try:

        players = await get_online_players()

    except Exception as error:

        print(
            f"[RCON] Error getting players: "
            f"{error}"
        )

        return

    if not players:

        return

    for player in players:

        try:

            (
                action,
                container_index,
                request
            ) = await get_player_request(
                player
            )

            if request < 1:

                continue

            print()

            print(
                "======================================"
            )

            print(
                "[+] MINECRAFT REQUEST"
            )

            print(
                f"    Player:    {player}"
            )

            print(
                f"    Container: {container_index}"
            )

            print(
                f"    Action:    {action}"
            )

            print(
                "======================================"
            )

            if (
                ALLOWED_PLAYERS
                and
                player not in ALLOWED_PLAYERS
            ):

                print(
                    f"[SECURITY] "
                    f"{player} not authorized."
                )

                await tell_player(
                    player,
                    "You are not authorized to use DockerCraft: Reloaded.",
                    "red"
                )

                await set_player_result(
                    player,
                    RESULT_ERROR
                )

                await reset_player_request(
                    player
                )

                continue

            if (
                container_index < 0
                or
                container_index >= len(containers)
            ):

                print(
                    "[ERROR] Invalid container index."
                )

                await tell_player(
                    player,
                    "Invalid container.",
                    "red"
                )

                await set_player_result(
                    player,
                    RESULT_ERROR
                )

                await reset_player_request(
                    player
                )

                continue

            container = containers[
                container_index
            ]

            if action == ACTION_INFO:

                print(
                    f"[DOCKERCRAFT] INFO -> "
                    f"{container.name}"
                )

                await send_container_info_to_player(
                    player,
                    container
                )

                print_container_info(
                    container
                )

                await set_player_result(
                    player,
                    RESULT_SUCCESS
                )

                await reset_player_request(
                    player
                )

                continue

            if action == ACTION_LOGS:

                print(
                    f"[DOCKERCRAFT] LOGS -> "
                    f"{container.name}"
                )

                await send_container_logs_to_player(
                    player,
                    container
                )

                await set_player_result(
                    player,
                    RESULT_SUCCESS
                )

                await reset_player_request(
                    player
                )

                continue

            success = execute_docker_action(
                container,
                action
            )

            if success:

                action_names = {

                    ACTION_START:
                        "START",

                    ACTION_STOP:
                        "STOP",

                    ACTION_RESTART:
                        "RESTART",

                }

                action_name = action_names.get(
                    action,
                    "UNKNOWN"
                )

                await tell_player(
                    player,
                    f"DockerCraft: {action_name} executed -> {container.name}",
                    "green"
                )

                await set_player_result(
                    player,
                    RESULT_SUCCESS
                )

            else:

                await tell_player(
                    player,
                    f"DockerCraft: error running the action on {container.name}",
                    "red"
                )

                await set_player_result(
                    player,
                    RESULT_ERROR
                )

            await reset_player_request(
                player
            )

        except Exception as error:

            print()

            print(
                "[ERROR] Processing request:"
            )

            print(
                f"Player: {player}"
            )

            print(
                f"Error: {error}"
            )


# ============================================================
# DOCKER COMMANDS (terminal + Minecraft chat)
# ============================================================

COMMAND_STATE = {
    # set to True after a command that changes Docker, so the panel is redrawn right away
    "force_sync": False,
    # strong references to running command tasks (asyncio only keeps weak ones)
    "tasks": set(),
}

# Matches a chat line of the server log, anchored on the log prefix so a player
# cannot fake somebody else's name by typing "<Admin> !docker ..." in the chat:
#   Paper:    [12:34:56 INFO]: <Steve> !docker ps
#   Vanilla:  [12:34:56] [Server thread/INFO]: <Steve> !docker ps
#   Signed:   [12:34:56 INFO]: [Not Secure] <Steve> !docker ps
CHAT_LINE = re.compile(
    r"^\[[^\]]+\](?: \[[^\]]+\])*: (?:\[Not Secure\] )?<(?P<player>[^<>\s]+)> (?P<message>.*)$"
)


def start_terminal_reader(loop, queue):
    """
    Reads commands typed in this terminal. It runs in a daemon thread
    (a blocking readline inside the event loop would freeze the manager,
    and in an executor it would make Ctrl+C hang).
    """

    def post(item):
        try:
            loop.call_soon_threadsafe(queue.put_nowait, item)
        except RuntimeError:
            pass  # the event loop is already closed

    def reader():
        while True:
            try:
                line = sys.stdin.readline()
            except Exception:
                return

            if line == "":
                try:
                    loop.call_soon_threadsafe(
                        print,
                        "[*] Terminal input closed: terminal commands disabled."
                    )
                except RuntimeError:
                    pass
                return

            line = line.strip()

            if line:
                post(("terminal", None, line))

    threading.Thread(
        target=reader,
        name="dockercraft-terminal",
        daemon=True
    ).start()


class ChatLogWatcher:
    """Follows the server log (like `tail -f`) and extracts the chat commands."""

    def __init__(self, path):
        self.path = path
        self.position = None
        self.warned = False

    def poll(self):
        """Returns [(player, command_line), ...] written since the last call."""
        try:
            size = os.path.getsize(self.path)
        except OSError as error:
            if not self.warned:
                self.warned = True
                print(f"[CHAT] Cannot read {self.path}: {error}")
            return []

        self.warned = False

        if self.position is None:
            self.position = size  # start at the end: ignore old chat
            return []

        if size < self.position:
            self.position = 0  # the log was rotated (new latest.log)

        if size == self.position:
            return []

        try:
            with open(self.path, "rb") as handle:
                handle.seek(self.position)
                data = handle.read(size - self.position)
        except OSError as error:
            print(f"[CHAT] Error reading the log: {error}")
            return []

        end = data.rfind(b"\n")

        if end < 0:
            return []  # incomplete line: wait for the rest

        self.position += end + 1

        found = []

        for raw in data[:end].split(b"\n"):
            line = raw.decode("utf-8", errors="replace").rstrip("\r")
            match = CHAT_LINE.match(line)

            if not match:
                continue

            command = extract_chat_command(match.group("message"))

            if command is not None:
                found.append((match.group("player"), command))

        return found


def extract_chat_command(message):
    """'!docker ps -a' -> 'ps -a'; '!dc' -> ''; 'hello' -> None."""
    message = message.strip()
    lowered = message.lower()

    for prefix in CHAT_COMMAND_PREFIXES:
        if lowered.startswith(prefix) and (
            len(message) == len(prefix) or message[len(prefix)].isspace()
        ):
            return message[len(prefix):].strip()

    return None


async def tell_player_lines(
    player,
    lines
):
    """Sends several lines to a player grouped in a few tellraw packets."""

    batch = []
    size = 0

    async def flush():
        nonlocal batch, size

        if batch:
            component = json.dumps(
                [{"text": ""}] + batch,
                ensure_ascii=False
            )

            await minecraft_command(
                f"tellraw "
                f"{shlex.quote(player)} "
                f"{component}"
            )

        batch = []
        size = 0

    for text, color in lines:

        parts = [{"text": str(text)[:250], "color": color}]

        if batch:
            parts.insert(0, {"text": "\n"})

        length = len(
            json.dumps(
                parts,
                ensure_ascii=False
            ).encode("utf-8")
        )

        if batch and size + length > CHAT_PACKET_LIMIT:
            await flush()
            parts = parts[-1:]
            length = len(
                json.dumps(
                    parts,
                    ensure_ascii=False
                ).encode("utf-8")
            )

        batch.extend(parts)
        size += length

    await flush()


async def run_command_job(
    client,
    source,
    player,
    line
):

    origin = "terminal" if source == "terminal" else f"chat:{player}"

    try:

        print()
        print(f"[CMD] ({origin}) docker {line}")

        try:
            tokens = tokenize(line)
        except DockerCliError as error:
            command = "error"
            lines = [(f"DockerCraft: {error}", "red")]
        else:
            # The terminal is trusted (the person already controls Docker).
            # Chat is public, so it gets safe-mode.
            safe_mode = DOCKER_SAFE_MODE and source != "terminal"

            command, lines = await asyncio.get_running_loop().run_in_executor(
                None,
                lambda: execute_docker_command(
                    client,
                    tokens,
                    safe_mode=safe_mode,
                    log_lines=LOG_LINES
                )
            )

        for text, _color in lines:
            print(text)

        if command not in READ_ONLY and command != "error":
            COMMAND_STATE["force_sync"] = True

        if source == "chat":
            await tell_player_lines(
                player,
                lines
            )

    except Exception as error:

        print(f"[ERROR] Docker command failed: {type(error).__name__}: {error}")


def poll_chat_commands(
    watcher,
    queue
):

    if watcher is None:
        return

    for player, line in watcher.poll():
        queue.put_nowait(("chat", player, line))


async def process_commands(
    client,
    queue
):

    while not queue.empty():

        source, player, line = queue.get_nowait()

        if source == "chat":

            allowed = {name.lower() for name in COMMAND_PLAYERS}

            if player.lower() not in allowed:

                print(
                    f"[SECURITY] {player} is not allowed to run "
                    f"docker commands: {line}"
                )

                await tell_player(
                    player,
                    "You are not authorized to use docker commands.",
                    "red"
                )

                continue

        # One task per command: a slow `docker pull` must not freeze
        # the click detection or the panel synchronization.
        task = asyncio.create_task(
            run_command_job(
                client,
                source,
                player,
                line
            )
        )

        COMMAND_STATE["tasks"].add(task)
        task.add_done_callback(COMMAND_STATE["tasks"].discard)


# ============================================================
# MAIN
# ============================================================

async def main():

    print()

    print(
        "======================================"
    )

    print(
        "     DOCKERCRAFT: RELOADED - MINECRAFT MANAGER"
    )

    print(
        "======================================"
    )

    print()

    # ========================================================
    # DOCKER
    # ========================================================

    print(
        "[*] Connecting to Docker..."
    )

    try:

        client = get_docker_client()

        client.ping()

    except Exception as error:

        print(
            "[ERROR] Could not connect "
            "to Docker."
        )

        print(
            error
        )

        return

    print(
        "[+] Docker connected."
    )

    # ========================================================
    # INITIAL CONTAINERS
    # ========================================================

    try:

        containers = get_containers(
            client
        )

    except Exception as error:

        print(
            "[ERROR] Could not get "
            "the containers."
        )

        print(
            error
        )

        return

    print()

    print(
        f"Containers found: "
        f"{len(containers)}"
    )

    for container in containers:

        print(
            f"- "
            f"{container.name} | "
            f"{container.status} | "
            f"{container.id[:12]}"
        )

    # ========================================================
    # RCON
    # ========================================================

    print()

    print(
        "[*] Testing RCON..."
    )

    try:

        response = await minecraft_command(
            "list"
        )

        print(
            f"[+] RCON OK: {response}"
        )

    except Exception as error:

        print(
            "[ERROR] Could not connect "
            "to Minecraft over RCON."
        )

        print(
            error
        )

        return

    # ========================================================
    # SCOREBOARDS
    # ========================================================

    try:

        await setup_scoreboards()

    except Exception as error:

        print(
            "[ERROR] Could not prepare "
            "the scoreboards."
        )

        print(
            error
        )

        return

    # ========================================================
    # INITIAL SIGNATURE
    # ========================================================

    previous_signature = (
        get_docker_signature(
            containers
        )
    )

    # ========================================================
    # INITIAL CONTAINER COUNT
    # ========================================================
    #
    # THIS IS THE COUNTER WE USE TO KNOW HOW MANY
    # MINECRAFT POSITIONS COULD EXIST BEFORE.
    #
    # ========================================================

    previous_container_count = len(
        containers
    )

    print(
        f"[*] Initial container count: "
        f"{previous_container_count}"
    )

    # ========================================================
    # CHECK CARDS
    # ========================================================

    print()

    print(
        "[*] Checking cards..."
    )

    try:

        existing_cards = (
            await cards_exist()
        )

    except Exception as error:

        print(
            "[ERROR] Could not check "
            "Minecraft."
        )

        print(
            error
        )

        return

    if existing_cards:

        print(
            "[=] The cards already exist."
        )

        print(
            "[=] The structure will "
            "not be rebuilt."
        )

    else:

        print(
            "[!] No cards exist."
        )

        print(
            "[*] Creating structure..."
        )

        try:

            await synchronize(
                containers,
                len(containers)
            )

        except Exception as error:

            print(
                "[ERROR] Failed to create "
                "the structure."
            )

            print(
                error
            )

            return

    # ========================================================
    # MANAGER STARTED
    # ========================================================

    print()

    print(
        "======================================"
    )

    print(
        "[+] DOCKERCRAFT MANAGER STARTED"
    )

    print(
        "======================================"
    )

    print(
        f"[*] Clicks: every "
        f"{INTERACTION_INTERVAL}s"
    )

    print(
        f"[*] Docker: every "
        f"{SYNC_INTERVAL}s"
    )

    print(
        "[*] Interaction: 1x1x1"
    )

    print(
        f"[*] LOGS: last "
        f"{LOG_LINES} lines"
    )

    print(
        "[*] Wall: 7x9 black_concrete"
    )

    print(
        "[*] Controls together and centered"
    )

    print(
        "[*] INFO -> Minecraft chat"
    )

    print(
        "[*] LOGS -> Minecraft chat"
    )

    print(
        "[*] START / STOP / RESTART enabled"
    )

    if DOCKER_COMMANDS_ENABLED:
        print(
            "[*] Docker commands: type them here "
            "(docker ps, docker images, docker pull X, "
            "docker run X, ... 'help' lists them)"
        )

        if CHAT_LOG_PATH and COMMAND_PLAYERS:
            print(
                f"[*] Chat commands (!docker / !dc) for: "
                f"{', '.join(sorted(COMMAND_PLAYERS))}"
            )
        elif CHAT_LOG_PATH:
            print(
                "[!] CHAT_LOG_PATH is set but COMMAND_PLAYERS "
                "is empty: nobody can use the chat commands."
            )
        else:
            print(
                "[*] Chat commands disabled "
                "(set CHAT_LOG_PATH and COMMAND_PLAYERS)"
            )

    print(
        "[*] Waiting for clicks..."
    )

    print()

    # ========================================================
    # LOOP
    # ========================================================

    interaction_timer = 0.0

    # ========================================================
    # DOCKER COMMANDS
    # ========================================================

    command_queue = None
    chat_watcher = None

    if DOCKER_COMMANDS_ENABLED:

        command_queue = asyncio.Queue()

        start_terminal_reader(
            asyncio.get_running_loop(),
            command_queue
        )

        if CHAT_LOG_PATH:
            chat_watcher = ChatLogWatcher(
                CHAT_LOG_PATH
            )

    while True:

        try:

            # =================================================
            # CURRENT CONTAINERS
            # =================================================

            containers = get_containers(
                client
            )

            current_container_count = len(
                containers
            )

            # =================================================
            # DETECT INTERACTIONS
            # =================================================

            await detect_interactions(
                containers
            )

            # =================================================
            # PROCESS REQUESTS
            # =================================================

            await process_player_requests(
                client,
                containers
            )

            # =================================================
            # DOCKER COMMANDS (terminal + chat)
            # =================================================

            if command_queue is not None:

                poll_chat_commands(
                    chat_watcher,
                    command_queue
                )

                await process_commands(
                    client,
                    command_queue
                )

                if COMMAND_STATE["force_sync"]:

                    COMMAND_STATE["force_sync"] = False

                    # redraw the panel now instead of waiting for SYNC_INTERVAL
                    interaction_timer = SYNC_INTERVAL

            # =================================================
            # DOCKER TIMER
            # =================================================

            interaction_timer += (
                INTERACTION_INTERVAL
            )

            if (
                interaction_timer
                >=
                SYNC_INTERVAL
            ):

                interaction_timer = 0.0

                # ---------------------------------------------
                # CURRENT SIGNATURE
                # ---------------------------------------------

                current_signature = (
                    get_docker_signature(
                        containers
                    )
                )

                # ---------------------------------------------
                # DETECT CHANGE
                # ---------------------------------------------

                if (
                    current_signature
                    !=
                    previous_signature
                ):

                    print()

                    print(
                        "======================================"
                    )

                    print(
                        "[!] DOCKER CHANGE DETECTED"
                    )

                    print(
                        "======================================"
                    )

                    print_containers(
                        containers
                    )

                    # -----------------------------------------
                    # COUNTERS
                    # -----------------------------------------

                    print(
                        f"[*] Previous containers: "
                        f"{previous_container_count}"
                    )

                    print(
                        f"[*] Current containers:  "
                        f"{current_container_count}"
                    )

                    # -----------------------------------------
                    # IF CONTAINERS HAVE DISAPPEARED
                    # -----------------------------------------

                    if (
                        current_container_count
                        <
                        previous_container_count
                    ):

                        print(
                            "[!] CONTAINERS ARE MISSING."
                        )

                        print(
                            "[*] ALL previous "
                            "positions will be cleared."
                        )

                    try:

                        # -------------------------------------
                        # VERY IMPORTANT
                        # -------------------------------------
                        #
                        # We use the maximum between the
                        # previous and the current number.
                        #
                        # If:
                        #
                        #   before = 10
                        #   now = 7
                        #
                        # 10 are cleared.
                        #
                        # If:
                        #
                        #   before = 7
                        #   now = 10
                        #
                        # 10 are cleared.
                        #
                        # Then only the current ones are created.
                        #
                        # -------------------------------------

                        clear_count = max(
                            previous_container_count,
                            current_container_count
                        )

                        print(
                            f"[*] Positions that will be "
                            f"cleared: {clear_count}"
                        )

                        await synchronize(
                            containers,
                            clear_count
                        )

                        # -------------------------------------
                        # UPDATE STATE
                        # -------------------------------------

                        previous_signature = (
                            current_signature
                        )

                        previous_container_count = (
                            current_container_count
                        )

                        print(
                            "[+] State synchronized."
                        )

                    except Exception as error:

                        print(
                            "[ERROR] Synchronization "
                            "failed:"
                        )

                        print(
                            error
                        )

            # =================================================
            # WAIT
            # =================================================

            await asyncio.sleep(
                INTERACTION_INTERVAL
            )

        # ====================================================
        # DOCKER ERROR
        # ====================================================

        except docker.errors.APIError as error:

            print()

            print(
                "[ERROR] Docker error:"
            )

            print(
                error
            )

            await asyncio.sleep(
                SYNC_INTERVAL
            )

        # ====================================================
        # GENERAL ERROR
        # ====================================================

        except Exception as error:

            print()

            print(
                "[ERROR] Unexpected error:"
            )

            print(
                f"{type(error).__name__}: "
                f"{error}"
            )

            await asyncio.sleep(
                INTERACTION_INTERVAL
            )


# ============================================================
# START
# ============================================================

if __name__ == "__main__":

    try:

        asyncio.run(
            main()
        )

    except KeyboardInterrupt:

        print()

        print(
            "[*] Manager stopped."
        )
