#!/usr/bin/env bash
# Builds the Paper plugin for Minecraft 26.3 (JDK 25).
# Output:  build/out/DockerCraft-Reloaded-<version>.jar
#
#   ./build/build.sh            uses ./gradlew, or gradle, or Docker
#   ./build/build.sh --docker   forces building inside Docker
#
# Optional environment variables:
#   GRADLE_IMAGE   Docker image to use (default: gradle:jdk25)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/out"
GRADLE_IMAGE="${GRADLE_IMAGE:-gradle:jdk25}"
cd "$ROOT/plugin"

build_docker() {
  command -v docker >/dev/null || { echo "Docker is not installed." >&2; exit 1; }
  echo ">> Building inside Docker ($GRADLE_IMAGE)..."
  docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e GRADLE_USER_HOME=/tmp/gradle-home \
    -v "$PWD":/project -w /project \
    "$GRADLE_IMAGE" gradle --no-daemon build
}

if [[ "${1:-}" == "--docker" ]]; then
  build_docker
elif [[ -x ./gradlew ]]; then
  echo ">> Building with ./gradlew ..."
  ./gradlew --no-daemon build
elif command -v gradle >/dev/null; then
  echo ">> Building with local gradle (9.8+)..."
  gradle --no-daemon build
elif command -v docker >/dev/null; then
  build_docker
else
  echo "You need Docker, or Gradle 9.8+ (Gradle downloads JDK 25 by itself)." >&2
  exit 1
fi

mkdir -p "$OUT"
rm -f "$OUT"/DockerCraft-Reloaded-*.jar
for jar in build/libs/DockerCraft-Reloaded-*.jar; do
  case "$jar" in *-plain.jar) continue ;; esac
  cp "$jar" "$OUT/"
done

echo
echo ">> Done. Copy this file to your server's plugins/ folder:"
ls -1 "$OUT"/DockerCraft-Reloaded-*.jar
