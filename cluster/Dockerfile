# syntax=docker/dockerfile:1
#
# EXPERIMENTAL. One image for every node of a watershed cluster: the coordinator and the workers.
#
# Build it with cluster/image.sh, which first puts the JVM side's jars in cluster/target/image/lib. The native
# library is built in here, against the same Ubuntu the image runs on.
#
# By default it is built against CUDA 12.8, which runs on a machine whose NVIDIA driver is 570 or later; the
# NVIDIA container runtime brings the driver in. For CUDA 13.1 (driver 580 or later) build with
#   --build-arg CUDA=13.1.2 --build-arg NVRTC=cuda-nvrtc-13-1 --build-arg FEATURE=cuda
# A worker started with gpu=false needs no GPU and no driver.

ARG CUDA=12.8.2
ARG NVRTC=cuda-nvrtc-12-8
ARG FEATURE=cuda-12
ARG UBUNTU=24.04

FROM ubuntu:${UBUNTU} AS native
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl git build-essential \
 && rm -rf /var/lib/apt/lists/*
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY native native
ARG FEATURE
RUN . "$HOME/.cargo/env" && cargo build --release --locked --features "watershed-jvm/$FEATURE"

FROM eclipse-temurin:25-jre AS jre

FROM nvidia/cuda:${CUDA}-base-ubuntu${UBUNTU}
# The compiler for the kernels a plan's shapes are written as, and nothing else of the toolkit.
ARG NVRTC
RUN apt-get update \
 && apt-get install -y --no-install-recommends "$NVRTC" \
 && rm -rf /var/lib/apt/lists/* \
 && ls -d /usr/local/cuda-*/lib64 > /etc/ld.so.conf.d/watershed-nvrtc.conf && ldconfig
COPY --from=jre /opt/java/openjdk /opt/java
COPY --from=native /src/target/release/libwatershed_jvm.so /opt/watershed/libwatershed_jvm.so
COPY cluster/target/image/lib /opt/watershed/lib
ENV PATH=/opt/java/bin:$PATH
# The arguments are a node's: `worker name=… port=… seed=…` or `coordinator port=… plan=… …`.
# What a node logs is UTF-8, whatever the image's locale would have made it.
ENV LANG=C.UTF-8
ENTRYPOINT ["java", "-cp", "/opt/watershed/lib/*", "--enable-native-access=ALL-UNNAMED", \
  "-Dwatershed.library=/opt/watershed/libwatershed_jvm.so", "dev.surefold.watershed.cluster.AppKt"]
