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
settings=(-Dwatershed.bucket.name=watershed -Dwatershed.bucket.region=us-east-1 -Dwatershed.bucket.endpoint=http://s3:9000
  -Dwatershed.bucket.path-style=true -Dwatershed.cluster.static.seeds.0=coordinator:25520)
# A node of the image, with its settings as system properties:
#     node NAME [options for docker run ...] -- SETTING=VALUE ... worker|submit|put
node() {
  local name="$1" docker=(); shift
  while [ "$1" != "--" ]; do docker+=("$1"); shift; done; shift
  local given=("${settings[@]}" "-Dwatershed.cluster.node.name=$name" "-Dwatershed.cluster.node.host=$name")
  while [ "$#" -gt 1 ]; do given+=("-Dwatershed.$1"); shift; done
  docker run "${docker[@]}" --network "$net" --hostname "$name" \
    -e WATERSHED_S3_ACCESS_KEY="$key" -e WATERSHED_S3_SECRET_KEY="$secret" -e JAVA_TOOL_OPTIONS="${given[*]}" "$image" "$1"
}

# The job's files go into the bucket; nothing else of this machine reaches a node. A folder the engine's `rerun`
# example wrote has a patch as well: the lots then hold what they ran, and are brought up to date with it.
files=(plan paths whole)
more=()
if [ -f "$folder/patch" ]; then
  files+=(patch whole-after)
  n=0
  for column in $(tr ',' ' ' <"$folder/columns"); do more+=("job.columns.$n=$column"); n=$((n + 1)); done
  more+=(job.hold=true job.patch=book/patch job.check-after=book/whole-after)
fi
make=true
for file in "${files[@]}"; do
  # The server takes a moment to answer; the first put is tried until it does.
  until node put --rm -v "$folder:/data:ro" -- put.from="/data/$file" put.key="book/$file" bucket.create=$make put \
    >/dev/null 2>&1; do sleep 1; done
  make=false
done

cores=$(nproc)
for n in $(seq 1 "$workers"); do
  names+=("$net-worker-$n")
  node "worker-$n" -d --name "$net-worker-$n" -e RAYON_NUM_THREADS=$(( (cores - 2) / workers )) -- \
    cluster.roles.0=worker worker.gpu=false worker >/dev/null
done
names+=("$net-coordinator")
node coordinator --name "$net-coordinator" -- cluster.roles.0=coordinator job.plan=book/plan job.inputs=book/paths \
  job.inputs-per="$inputs_per" job.outputs-per="$outputs_per" job.lot="$lot" job.out=book/out job.workers="$workers" \
  job.check=book/whole "${more[@]}" submit
