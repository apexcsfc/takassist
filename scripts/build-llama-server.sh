#!/usr/bin/env bash
# Cross-compiles a fully static ARM64 llama-server and drops it into the plugin as
#   app/src/main/jniLibs/arm64-v8a/libllamaserver.so
#
# Needs: git, cmake, ninja, python3 with `pip install ziglang`. No Android NDK.
# A static musl binary doesn't depend on Android's libc, so it runs on Android as is.
#
#   ./scripts/build-llama-server.sh            # latest llama.cpp release tag
#   LLAMA_TAG=b11748 ./scripts/build-llama-server.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-$ROOT/build-llama}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libllamaserver.so"

ZIG="$(python3 -c 'import ziglang,os;print(os.path.join(os.path.dirname(ziglang.__file__),"zig"))')"

# ARMv8.2 + dotprod + int8 matmul + fp16: Snapdragon 8 Gen 1 and newer.
# No SVE (Qualcomm Oryon cores don't implement it).
CPU_FLAGS="-target aarch64-linux-musl -mcpu=generic+v8_2a+dotprod+i8mm+fullfp16"

mkdir -p "$WORK/zig"
printf '#!/bin/sh\nexec "%s" cc %s "$@"\n'  "$ZIG" "$CPU_FLAGS" > "$WORK/zig/zcc"
printf '#!/bin/sh\nexec "%s" c++ %s "$@"\n' "$ZIG" "$CPU_FLAGS" > "$WORK/zig/zcxx"
printf '#!/bin/sh\nexec "%s" ar "$@"\n'     "$ZIG" > "$WORK/zig/zar"
printf '#!/bin/sh\nexec "%s" ranlib "$@"\n' "$ZIG" > "$WORK/zig/zranlib"
chmod +x "$WORK"/zig/*

if [ ! -d "$WORK/llama.cpp" ]; then
  TAG="${LLAMA_TAG:-$(git ls-remote --tags https://github.com/ggml-org/llama.cpp \
        | grep -oE 'refs/tags/b[0-9]+$' | sed 's#refs/tags/##' | sort -t b -k2 -n | tail -1)}"
  echo "Cloning llama.cpp $TAG"
  git -c advice.detachedHead=false clone --depth 1 --branch "$TAG" \
      https://github.com/ggml-org/llama.cpp "$WORK/llama.cpp"
fi

# -z stack-size: musl's default thread stack is only 128 KB, and llama-server's
# chat-template engine recurses on its HTTP threads. musl takes the default
# thread stack size from PT_GNU_STACK, so give it 8 MB.
cmake -S "$WORK/llama.cpp" -B "$WORK/build" -G Ninja \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER="$WORK/zig/zcc" -DCMAKE_CXX_COMPILER="$WORK/zig/zcxx" \
  -DCMAKE_AR="$WORK/zig/zar" -DCMAKE_RANLIB="$WORK/zig/zranlib" \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_EXE_LINKER_FLAGS="-static -Wl,-z,stack-size=8388608 -s" \
  -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_CCACHE=OFF \
  -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_APP=OFF \
  -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF

ninja -C "$WORK/build" llama-server

mkdir -p "$(dirname "$OUT")"
cp "$WORK/build/bin/llama-server" "$OUT"
chmod 755 "$OUT"
ls -la "$OUT"
file "$OUT" 2>/dev/null || true
