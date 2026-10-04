# build/

Tool for building the plugin (Paper 26.3, JDK 25):

| System | Command |
|---|---|
| Linux / macOS | `./build/build.sh` |
| Windows | `build\build.bat` |

The final `.jar` ends up in `build/out/`. Add `--docker` (Linux) or `docker` (Windows)
to build inside a container with nothing installed except Docker.
