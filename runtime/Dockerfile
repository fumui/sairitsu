# Linux toolchain for the runtime's host build and tests (CMake preset "linux"). CI runs in this image, pinned
# by digest, so a runner image update can't change the compiler. Rebuild and push it with the "CI image"
# workflow, then update the digest in ci.yml.
FROM ubuntu:24.04@sha256:9c7f200c07e3d4a03e7249d2c16bf66a51120fb876aa2242cf31451412543289

LABEL org.opencontainers.image.source=https://github.com/fumui/sairitsu

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        clang-20 llvm-20 lld-20 cmake ninja-build git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV PATH="/usr/lib/llvm-20/bin:${PATH}"
