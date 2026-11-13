#!/usr/bin/env bash
# EXPERIMENTAL. Builds the image every node of a watershed cluster runs: cluster/image.sh [TAG]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
tag="${1:-watershed-cluster:dev}"
sbt="${SBT:-sbt}"
cd "$here"
"$sbt" -batch -error cluster/compile
lib=cluster/target/image/lib
rm -rf "$lib" && mkdir -p "$lib"
n=0
# What the node needs to run: this build's classes as jars, and the libraries. The Kotlin compiler's own jars
# are on the build's classpath and not wanted here.
for entry in $("$sbt" -batch -error "export cluster/Runtime/fullClasspath" | tail -1 | tr ':' ' '); do
  case "$entry" in
    */classes) n=$((n + 1)); jar --create --file "$lib/watershed-$n.jar" -C "$entry" . ;;
    *kotlin-scripting*|*kotlin-script-runtime*) ;;
    *.jar) cp "$entry" "$lib/" ;;
  esac
done
docker build -f cluster/Dockerfile -t "$tag" .
echo "built $tag"
