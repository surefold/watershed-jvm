#!/usr/bin/env bash
# EXPERIMENTAL. A coordinator and some CPU workers as containers of the cluster's image on one Docker network,
# with an S3 server beside them as the bucket, running the scenarios in a folder the engine's `split` example
# wrote (WATERSHED_KEEP=folder).
#
#     cluster/run-containers.sh FOLDER LOT WORKERS [IMAGE=watershed-cluster:dev] [INPUTS_PER=240] [OUTPUTS_PER=4]
#
# The workers run on the CPU: it checks the image, that nodes in separate containers find each other, and that
# they share nothing but the bucket, without needing Docker to be able to reach a GPU.
set -euo pipefail
folder="$(cd "$1" && pwd)" lot="$2" workers="$3" image="${4:-watershed-cluster:dev}"
inputs_per="${5:-240}" outputs_per="${6:-4}"
net="watershed-$$"
docker network create "$net" >/dev/null
names=("$net-s3")
trap 'docker rm -f "${names[@]}" >/dev/null 2>&1 || true; docker network rm "$net" >/dev/null 2>&1 || true' EXIT

# The bucket: RustFS, which speaks MinIO's API, with a key made up for this run.
key="watershed" secret="local-$RANDOM-$RANDOM-$RANDOM"
docker run -d --name "$net-s3" --hostname s3 --network "$net" \
  -e RUSTFS_ACCESS_KEY="$key" -e RUSTFS_SECRET_KEY="$secret" rustfs/rustfs:1.0.1 >/dev/null
node=(--network "$net" -e WATERSHED_S3_ACCESS_KEY="$key" -e WATERSHED_S3_SECRET_KEY="$secret")
bucket=(bucket=watershed region=us-east-1 endpoint=http://s3:9000 path-style=true local=/tmp/watershed-files)

# The job's files go into the bucket; nothing else of this machine reaches a node. A folder the engine's `rerun`
# example wrote has a patch as well: the lots then hold what they ran, and are brought up to date with it.
files=(plan paths whole)
more=()
if [ -f "$folder/patch" ]; then
  files+=(patch whole-after)
  more=(columns="$(cat "$folder/columns")" hold=true patch=book/patch check-after=book/whole-after)
fi
make=true
for file in "${files[@]}"; do
  # The server takes a moment to answer; the first put is tried until it does.
  until docker run --rm "${node[@]}" -v "$folder:/data:ro" "$image" \
    put from="/data/$file" key="book/$file" make-bucket=$make "${bucket[@]}" >/dev/null 2>&1; do sleep 1; done
  make=false
done

cores=$(nproc)
for n in $(seq 1 "$workers"); do
  names+=("$net-worker-$n")
  docker run -d --name "$net-worker-$n" --hostname "worker-$n" "${node[@]}" \
    -e RAYON_NUM_THREADS=$(( (cores - 2) / workers )) "$image" \
    worker name="worker-$n" host="worker-$n" port=25520 seed=coordinator:25520 gpu=false "${bucket[@]}" >/dev/null
done
names+=("$net-coordinator")
docker run --name "$net-coordinator" --hostname coordinator "${node[@]}" "$image" \
  coordinator host=coordinator port=25520 plan=book/plan inputs=book/paths inputs-per="$inputs_per" \
  outputs-per="$outputs_per" lot="$lot" out=book/out workers="$workers" check=book/whole \
  "${bucket[@]}" "${more[@]}" 2>/dev/null
