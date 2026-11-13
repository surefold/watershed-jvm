#!/usr/bin/env bash
# EXPERIMENTAL. A coordinator and some CPU workers as containers of the cluster's image on one Docker network,
# sharing a folder the engine's `split` example wrote (WATERSHED_KEEP=folder).
#
#     cluster/run-containers.sh FOLDER LOT WORKERS [IMAGE=watershed-cluster:dev] [INPUTS_PER=240] [OUTPUTS_PER=4]
#
# The workers run on the CPU: it checks the image and that nodes in separate containers find each other, without
# needing Docker to be able to reach a GPU.
set -euo pipefail
folder="$(cd "$1" && pwd)" lot="$2" workers="$3" image="${4:-watershed-cluster:dev}"
inputs_per="${5:-240}" outputs_per="${6:-4}"
net="watershed-$$"
docker network create "$net" >/dev/null
names=()
trap 'docker rm -f "${names[@]}" >/dev/null 2>&1 || true; docker network rm "$net" >/dev/null 2>&1 || true' EXIT
rm -rf "$folder/out"
user="$(id -u):$(id -g)"
cores=$(nproc)
for n in $(seq 1 "$workers"); do
  names+=("$net-worker-$n")
  docker run -d --name "$net-worker-$n" --hostname "worker-$n" --network "$net" --user "$user" -v "$folder:/data" \
    -e RAYON_NUM_THREADS=$(( (cores - 2) / workers )) "$image" \
    worker name="worker-$n" host="worker-$n" port=25520 seed=coordinator:25520 gpu=false >/dev/null
done
names+=("$net-coordinator")
docker run --name "$net-coordinator" --hostname coordinator --network "$net" --user "$user" -v "$folder:/data" "$image" \
  coordinator host=coordinator port=25520 plan=/data/plan inputs=/data/paths inputs-per="$inputs_per" \
  outputs-per="$outputs_per" lot="$lot" out=/data/out workers="$workers" check=/data/whole 2>/dev/null
