#!/bin/sh
# Rebuilds app/src/main/jniLibs/arm64-v8a/libllama-server.so: llama.cpp's OpenAI-compatible server for Android (CPU).
# Needs the Android NDK (sdkmanager "ndk;27.2.12479018"), cmake and ninja. llama.cpp is MIT licensed.
set -e
NDK=${NDK:-$ANDROID_HOME/ndk/27.2.12479018}
REV=${REV:-d3c3e242c16b8cbf51400fad410cd69b22d64f35}
SRC=${SRC:-/tmp/llama.cpp}
[ -d "$SRC" ] || git clone https://github.com/ggml-org/llama.cpp "$SRC"
cd "$SRC" && git fetch -q --depth 1 origin "$REV" && git checkout -q "$REV"
cmake -S . -B build-android -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF -DLLAMA_OPENSSL=OFF -DGGML_OPENMP=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_APP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16
ninja -C build-android llama-server
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a/libllama-server.so"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$OUT" build-android/bin/llama-server
echo "Wrote $OUT"
