# syntax=docker/dockerfile:1
#
# EXPERIMENTAL. One image for every node of a watershed cluster: the coordinator and the workers.
#
# Build it with cluster/image.sh, which first puts the JVM side's jars in cluster/target/image/lib. The native
# library is built in here, against the same Ubuntu the image runs on. It needs CUDA 13.1's kernel compiler,
# which is installed below, and an NVIDIA driver new enough for CUDA 13 (580 or later) on the machine, which the
# NVIDIA container runtime brings in. A worker started with gpu=false needs neither.

ARG CUDA=13.1.2
ARG UBUNTU=24.04

FROM ubuntu:${UBUNTU} AS native
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git build-essential \
 && rm -rf /var/lib/apt/lists/*
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY native native
RUN . "$HOME/.cargo/env" && cargo build --release --locked --features watershed-jvm/cuda

FROM eclipse-temurin:25-jre AS jre

FROM nvidia/cuda:${CUDA}-base-ubuntu${UBUNTU}
# The compiler for the kernels a plan's shapes are written as, and nothing else of the toolkit.
RUN apt-get update \
 && apt-get install -y --no-install-recommends cuda-nvrtc-13-1 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=jre /opt/java/openjdk /opt/java
COPY --from=native /src/target/release/libwatershed_jvm.so /opt/watershed/libwatershed_jvm.so
COPY cluster/target/image/lib /opt/watershed/lib
ENV PATH=/opt/java/bin:$PATH
# The arguments are a node's: `worker name=… port=… seed=…` or `coordinator port=… plan=… …`.
ENTRYPOINT ["java", "-cp", "/opt/watershed/lib/*", "--enable-native-access=ALL-UNNAMED", \
  "-Dwatershed.library=/opt/watershed/libwatershed_jvm.so", "dev.surefold.watershed.cluster.MainKt"]
