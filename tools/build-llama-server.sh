#!/bin/sh
# Rebuilds the local-model engine in app/src/main/jniLibs/arm64-v8a: llama.cpp's llama-server (as libllama-server.so)
# with GPU + CPU support. Every backend is a separate library loaded at run time, so a phone without one just skips it:
#   - OpenCL (Qualcomm's Adreno-optimized backend; uses the phone's /vendor/lib64/libOpenCL.so at run time)
#   - Vulkan (Mali, Exynos, Tensor, Adreno; Android 9+)
#   - Hexagon NPU (Snapdragon 8 Gen 2 and newer: HTP v73/v75/v79/v81/v85 programs, run as an unsigned module)
#   - CPU variants from ARMv8.0 to ARMv9.2 (the fastest one the phone supports is picked)
# Needs the Android NDK (sdkmanager "ndk;27.2.12479018"), cmake, ninja and git. llama.cpp is MIT licensed.
set -e
NDK=${NDK:-$ANDROID_HOME/ndk/27.2.12479018}
REV=${REV:-d3c3e242c16b8cbf51400fad410cd69b22d64f35}
WORK=${WORK:-/tmp/llama-build}
mkdir -p "$WORK" && cd "$WORK"
for r in OpenCL-Headers OpenCL-ICD-Loader Vulkan-Headers SPIRV-Headers; do [ -d $r ] || git clone --depth 1 https://github.com/KhronosGroup/$r; done
[ -d llama.cpp ] || git clone https://github.com/ggml-org/llama.cpp
# Hexagon SDK (Community Edition, repackaged by Qualcomm's snapdragon-toolchain project) for the NPU programs.
HEX=$WORK/hexagon/6.7.0.0
if [ ! -d "$HEX" ]; then
  mkdir -p $WORK/hexagon && curl -L https://github.com/snapdragon-toolchain/hexagon-sdk/releases/download/v6.7.0.0/hexagon-sdk-v6.7.0.0-amd64-lnx.tar.xz | tar xJ -C $WORK/hexagon
fi
(cd llama.cpp && git fetch -q --depth 1 origin "$REV" && git checkout -q "$REV")
TC="-DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a"
# OpenCL loader: only linked against at build time; the phone's own driver is used at run time.
cmake -S OpenCL-ICD-Loader -B OpenCL-ICD-Loader/build -G Ninja $TC -DANDROID_PLATFORM=26 -DCMAKE_BUILD_TYPE=Release -DOPENCL_ICD_LOADER_HEADERS_DIR=$WORK/OpenCL-Headers -DBUILD_TESTING=OFF
ninja -C OpenCL-ICD-Loader/build
cmake -S SPIRV-Headers -B SPIRV-Headers/build -DCMAKE_INSTALL_PREFIX=$WORK/prefix -DSPIRV_HEADERS_ENABLE_TESTS=OFF && cmake --install SPIRV-Headers/build
SYSLIB=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android
cmake -S llama.cpp -B llama.cpp/build-android -G Ninja $TC -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DLLAMA_OPENSSL=OFF \
  -DGGML_OPENCL=ON -DGGML_OPENCL_EMBED_KERNELS=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
  -DOpenCL_INCLUDE_DIR=$WORK/OpenCL-Headers -DOpenCL_LIBRARY=$WORK/OpenCL-ICD-Loader/build/libOpenCL.so \
  -DGGML_VULKAN=ON -DVulkan_INCLUDE_DIR=$WORK/Vulkan-Headers/include -DVulkan_LIBRARY=$SYSLIB/28/libvulkan.so \
  -DVulkan_GLSLC_EXECUTABLE=$NDK/shader-tools/linux-x86_64/glslc -DSPIRV-Headers_DIR=$WORK/prefix/share/cmake/SPIRV-Headers \
  -DGGML_HEXAGON=ON -DHEXAGON_SDK_ROOT=$HEX -DHEXAGON_TOOLS_ROOT=$HEX/tools/HEXAGON_Tools/21.0.04 -DPREBUILT_LIB_DIR=android_aarch64 \
  -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_APP=OFF
ninja -C llama.cpp/build-android llama-server htp-v73 htp-v75 htp-v79 htp-v81 htp-v85
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
rm -f "$OUT"/*.so && mkdir -p "$OUT"
STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip
for f in llama.cpp/build-android/bin/*.so; do $STRIP -o "$OUT/$(basename $f)" "$f"; done
$STRIP -o "$OUT/libllama-server.so" llama.cpp/build-android/bin/llama-server
cp llama.cpp/build-android/ggml/src/ggml-hexagon/libggml-htp-v*.so "$OUT/"  # NPU (Hexagon) programs: not stripped with the ARM tools
cp $SYSLIB/libc++_shared.so "$OUT/"
echo "Wrote $OUT"
