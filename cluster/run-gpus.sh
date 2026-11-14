#!/usr/bin/env bash
# EXPERIMENTAL. Run once, on one machine with eight A100s (see the README). A coordinator and one
# worker for each GPU of this machine, as containers of the cluster's image on one Docker network, running a job
# whose files are already in a bucket (put there by the `put` command, from wherever the plan was made).
#
#     cluster/run-gpus.sh PREFIX LOT [GPUS=all of them] [IMAGE=ghcr.io/surefold/watershed-cluster:latest]
#                         [INPUTS_PER=240] [OUTPUTS_PER=4]
#
# PREFIX is where the job's files are in the bucket: PREFIX/plan, PREFIX/paths and, to compare the answers with,
# PREFIX/whole. The answers go to PREFIX/out. Set PLAN to use a plan that is somewhere else in the bucket, for
# scenarios that share one. The bucket comes from the environment, and its keys from nowhere else:
#
#     WATERSHED_BUCKET, WATERSHED_S3_ENDPOINT, WATERSHED_S3_ACCESS_KEY, WATERSHED_S3_SECRET_KEY
#
# Needs Docker with the NVIDIA container runtime (docker run --gpus), and a driver the image's CUDA runs on.
set -euo pipefail
prefix="$1" lot="$2"
gpus="${3:-$(nvidia-smi --query-gpu=index --format=csv,noheader | wc -l)}"
image="${4:-ghcr.io/surefold/watershed-cluster:latest}" inputs_per="${5:-240}" outputs_per="${6:-4}"
: "${WATERSHED_BUCKET:?the bucket}" "${WATERSHED_S3_ENDPOINT:?where the bucket answers}"
: "${WATERSHED_S3_ACCESS_KEY:?the key}" "${WATERSHED_S3_SECRET_KEY:?the secret key}"
net="watershed-$$"
docker network create "$net" >/dev/null
names=()
trap 'docker rm -f "${names[@]}" >/dev/null 2>&1 || true; docker network rm "$net" >/dev/null 2>&1 || true' EXIT

settings=(-Dwatershed.bucket.path-style=true -Dwatershed.cluster.static.seeds.0=coordinator:25520)
# A node of the image, with its settings as system properties. The keys are passed by name, so that they are
# read from this shell's environment and are in no command line:
#     node NAME [options for docker run ...] -- SETTING=VALUE ... worker|submit
node() {
  local name="$1" docker=(); shift
  while [ "$1" != "--" ]; do docker+=("$1"); shift; done; shift
  local given=("${settings[@]}" "-Dwatershed.cluster.node.name=$name" "-Dwatershed.cluster.node.host=$name")
  while [ "$#" -gt 1 ]; do given+=("-Dwatershed.$1"); shift; done
  docker run "${docker[@]}" --network "$net" --hostname "$name" \
    -e WATERSHED_BUCKET -e WATERSHED_S3_ENDPOINT -e WATERSHED_S3_ACCESS_KEY -e WATERSHED_S3_SECRET_KEY \
    -e JAVA_TOOL_OPTIONS="${given[*]}" "$image" "$1"
}

cores=$(nproc)
for n in $(seq 0 $((gpus - 1))); do
  names+=("$net-gpu-$n")
  # Each worker has one GPU to itself, and its share of the cores for reading the plan.
  node "gpu-$n" -d --name "$net-gpu-$n" --gpus "device=$n" -e RAYON_NUM_THREADS=$(( (cores - 2) / gpus > 0 ? (cores - 2) / gpus : 1 )) -- \
    cluster.roles.0=worker worker.gpu=true worker >/dev/null
done
names+=("$net-coordinator")
status=0
node coordinator --name "$net-coordinator" -- cluster.roles.0=coordinator job.plan="${PLAN:-$prefix/plan}" \
  job.inputs="$prefix/paths" job.inputs-per="$inputs_per" job.outputs-per="$outputs_per" job.lot="$lot" \
  job.out="$prefix/out" job.workers="$gpus" job.check="$prefix/whole" submit || status=$?
# What each worker said, kept for after the containers are gone.
logs="$(mktemp -d)"
for n in $(seq 0 $((gpus - 1))); do docker logs "$net-gpu-$n" >"$logs/gpu-$n.log" 2>&1 || true; done
echo "worker logs in $logs"
exit $status
