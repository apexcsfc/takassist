#!/usr/bin/env bash
# Builds llama-server with Snapdragon GPU (Adreno / OpenCL) and NPU (Hexagon) support and
# drops it into the plugin next to the CPU-only server:
#
#   app/src/main/jniLibs/arm64-v8a/libllamaserver_snap.so   (the server)
#   app/src/main/jniLibs/arm64-v8a/libllama.so, libggml*.so  (its libraries)
#   app/src/main/jniLibs/arm64-v8a/libggml-htp-v*.so         (NPU code, runs on the Hexagon DSP)
#
# Needs Docker Desktop running. Uses Qualcomm's official toolchain image (Android NDK +
# Hexagon SDK + OpenCL SDK), same as llama.cpp's scripts/snapdragon/build.py.
# On an Apple Silicon Mac the image runs under x86 emulation, so expect 20-60 minutes.
#
#   ./scripts/build-llama-server-snapdragon.sh
#   LLAMA_TAG=b11748 ./scripts/build-llama-server-snapdragon.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${LLAMA_TAG:-b11748}"                     # same llama.cpp release as the CPU build
IMAGE="${SNAPDRAGON_IMAGE:-ghcr.io/snapdragon-toolchain/arm64-android:v0.7}"
WORK="$ROOT/build-snapdragon"
SRC="$WORK/llama.cpp"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker isn't installed. Get Docker Desktop: https://www.docker.com/products/docker-desktop/" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker Desktop isn't running. Open it, wait for it to say 'Running', then rerun." >&2
  exit 1
fi

mkdir -p "$WORK"
if [ ! -d "$SRC" ]; then
  echo "== Downloading llama.cpp $TAG"
  git -c advice.detachedHead=false clone --depth 1 --branch "$TAG" \
      https://github.com/ggml-org/llama.cpp "$SRC"
fi
cp "$SRC/docs/backend/snapdragon/CMakeUserPresets.json" "$SRC/CMakeUserPresets.json"

echo "== Building in $IMAGE (first run downloads the toolchain image, several GB)"
docker run --rm --platform linux/amd64 \
  -u "$(id -u):$(id -g)" \
  -v "$SRC:/workspace" -w /workspace \
  "$IMAGE" bash -c '
    set -e
    cmake --preset arm64-android-snapdragon-release -B build-snapdragon \
      -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
      -DLLAMA_BUILD_UI=OFF -DLLAMA_USE_PREBUILT_UI=OFF
    cmake --build build-snapdragon -j "$(nproc)" --target \
      llama-server ggml-opencl ggml-hexagon htp-v73 htp-v75 htp-v79 htp-v81
  '

BUILD="$SRC/build-snapdragon"
if [ ! -f "$BUILD/bin/llama-server" ]; then
  echo "Build finished but bin/llama-server is missing." >&2
  exit 1
fi

echo "== Copying into the plugin"
mkdir -p "$OUT"
# Remove libraries from a previous Snapdragon build (keeps the CPU server libllamaserver.so).
find "$OUT" -maxdepth 1 \( -name 'libllama.so' -o -name 'libggml*.so' -o -name 'libmtmd.so' \
      -o -name 'libllamaserver_snap.so' \) -delete
cp "$BUILD/bin/llama-server" "$OUT/libllamaserver_snap.so"
# Shared libraries the server links against, plus the Hexagon skeletons (htp-v*).
# Never ship an OpenCL loader: the phone's own /vendor/lib64/libOpenCL.so (Adreno driver) must be used.
find "$BUILD" -name '*.so' -not -name 'libOpenCL*.so' -not -path '*/CMakeFiles/*' \
     -not -path '*/htp-v*-prefix/*' -exec cp {} "$OUT/" \;
find "$BUILD" -path '*htp*' -name 'libggml-htp-v*.so' -not -path '*/CMakeFiles/*' -exec cp {} "$OUT/" \;
chmod 755 "$OUT"/*.so

# Strip debug info from the ARM64 libraries (about 190 MB down to about 37 MB).
# The htp-v* files are Hexagon DSP code and are left as built.
echo "== Stripping debug info"
docker run --rm --platform linux/amd64 \
  -u "$(id -u):$(id -g)" \
  -v "$OUT:/out" -w /out \
  "$IMAGE" bash -c '
    set -e
    S=$(command -v llvm-strip || find / -name llvm-strip -type f 2>/dev/null | head -1)
    [ -n "$S" ] || { echo "llvm-strip not found in the image" >&2; exit 1; }
    for f in *.so; do
      case "$f" in libggml-htp-v*) continue ;; esac
      "$S" --strip-unneeded "$f"
    done
  '

ls -la "$OUT"
echo
echo "Done. Rebuild the plugin: ./gradlew assembleCivDebug"
