#!/usr/bin/env bash
# EXPERIMENTAL. A coordinator and some workers on this machine, as separate processes, running the scenarios in
# a folder that the engine's `split` example wrote (WATERSHED_KEEP=folder): plan, paths and whole.
#
#     [KILL_AFTER=seconds] cluster/run-local.sh FOLDER LOT GPU_WORKERS [CPU_WORKERS] [INPUTS_PER=240] [OUTPUTS_PER=4]
#
# Build first: WATERSHED_CUDA=1 sbt cluster/compile. Workers here share one GPU, so this shows that the answers
# are right and what the cluster costs, not that it is faster.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
folder="$1" lot="$2" gpus="$3" cpus="${4:-0}" inputs_per="${5:-240}" outputs_per="${6:-4}"
sbt="${SBT:-sbt}"
classpath="$(cd "$here" && "$sbt" -batch -error "export cluster/Runtime/fullClasspath" | tail -1)"
run=(java -cp "$classpath" --enable-native-access=ALL-UNNAMED "-Dwatershed.library.path=$here/target/release"
  dev.surefold.watershed.cluster.MainKt)
# A run of ports nothing is listening on.
port=$(python3 -c 'import socket,random
while True:
    p=random.randrange(20000,60000,256)
    try:
        for q in list(range(p,p+17))+list(range(p+100,p+117)): socket.socket().bind(("127.0.0.1",q))
    except OSError: continue
    print(p); break')
logs="$(mktemp -d)"
pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT

# A folder the engine's `rerun` example wrote has more: which outputs are the answers, and a patch to bring the
# run up to date with after it. The workers then hold their lots.
more=()
if [ -f "$folder/patch" ]; then
  more=(columns="$folder/columns" hold=true patch="$folder/patch" check-after="$folder/whole-after")
fi
rm -rf "$folder/out"
"${run[@]}" coordinator port=$port plan="$folder/plan" inputs="$folder/paths" inputs-per=$inputs_per \
  outputs-per=$outputs_per lot="$lot" out="$folder/out" workers=$((gpus + cpus)) check="$folder/whole" \
  "${more[@]}" 2>"$logs/coordinator.log" &
coordinator=$!
cores=$(nproc)
# The GPU workers share the card: four fifths of what is free now, split between them.
if [ "$gpus" -gt 0 ]; then
  free=$(nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits | head -1)
  bytes=$((free * 1048576 / 5 * 4 / gpus))
fi
for n in $(seq 1 "$gpus"); do
  "${run[@]}" worker name="gpu-$n" port=$((port + n)) seed="127.0.0.1:$port" gpu=true gpu-bytes=$bytes \
    >"$logs/gpu-$n.log" 2>&1 &
  pids+=($!)
done
for n in $(seq 1 "$cpus"); do
  # Left two cores short, so that a busy worker still answers the cluster.
  RAYON_NUM_THREADS=$(( (cores - 2) / (cpus > 0 ? cpus : 1) )) \
    "${run[@]}" worker name="cpu-$n" port=$((port + 100 + n)) seed="127.0.0.1:$port" gpu=false >"$logs/cpu-$n.log" 2>&1 &
  pids+=($!)
done
# Set KILL_AFTER to a number of seconds to kill the first worker outright that long after starting, as a crash.
if [ -n "${KILL_AFTER:-}" ]; then
  (sleep "$KILL_AFTER"; kill -9 "${pids[0]}" 2>/dev/null && echo "killed the first worker after ${KILL_AFTER}s") &
fi
if wait "$coordinator"; then status=0; else status=$?; fi
echo "logs in $logs"
exit $status
