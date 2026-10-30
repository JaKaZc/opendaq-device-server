#!/bin/sh
# Build the native side of the Device Server app for Android arm64, and copy
# the libraries into the app:
#
#   1. the openDAQ SDK with only the native streaming server module,
#   2. AndroidDeviceModule, from opendaq-android-device-module,
#   3. UniversalFunctionBlocksModule, from opendaq-universal-function-blocks,
#      when that repository is there,
#   4. libsensor_server.so, the JNI library in native/server.
#
#   tool/build_native.sh
#
# The two modules are separate repositories, expected beside this one;
# ANDROID_DEVICE_MODULE and UNIVERSAL_FUNCTION_BLOCKS name other folders. Their
# sources are built here, against this app's SDK build. The app needs the
# Android device module. The function blocks module is optional: without it,
# the phone serves its sensors without the renderer, the derived signals and
# the Dashboard.
#
# The SDK is cloned into build/native/openDAQ from OPENDAQ_SOURCE (the openDAQ
# repository on GitHub unless set; a local clone is faster), at the release in
# SDK_REF, with the patch in patches/. SDK_REF has to match the openDAQ Studio
# on the computer, because openDAQ's native protocol does not connect a client
# of one release to a server of another development state. The first run
# takes about ten minutes and needs network access for the SDK's
# dependencies; later runs only rebuild what changed.
#
# Needs the Android NDK (ANDROID_NDK, or the newest NDK of the Android SDK in
# ANDROID_HOME or its default place), CMake 3.24 or newer, Ninja and git.
# CMAKE and NINJA name the tools when they are not on the PATH.

set -e

HERE="$(cd "$(dirname "$0")/.." && pwd)"
PARENT="$(cd "$HERE/.." && pwd)"
SENSORS="${ANDROID_DEVICE_MODULE:-$PARENT/opendaq-android-device-module}"
BLOCKS="${UNIVERSAL_FUNCTION_BLOCKS:-$PARENT/opendaq-universal-function-blocks}"
WORK="$HERE/build/native"
SDK_SOURCE="${OPENDAQ_SOURCE:-https://github.com/openDAQ/openDAQ.git}"
SDK_REF="${SDK_REF:-v3.40.3}"
CMAKE="${CMAKE:-cmake}"
NINJA="${NINJA:-ninja}"
JNILIBS="$HERE/app/src/main/jniLibs/arm64-v8a"
SDK_BUILD="$WORK/sdk-android-arm64"
SDK_INSTALL="$WORK/sdk-android-arm64-install"

if [ -z "$ANDROID_NDK" ]; then
  case "$(uname -s)" in
    Darwin) ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}" ;;
    *) ANDROID_SDK="${ANDROID_HOME:-$HOME/Android/Sdk}" ;;
  esac
  ANDROID_NDK="$(ls -d "$ANDROID_SDK/ndk/"* 2> /dev/null | sort -V | tail -1)"
fi
if [ ! -f "$ANDROID_NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "No Android NDK found; install one with Android Studio's SDK Manager, or set ANDROID_NDK" >&2
  exit 1
fi
TOOLCHAIN="$ANDROID_NDK/build/cmake/android.toolchain.cmake"
LLVM="$(ls -d "$ANDROID_NDK"/toolchains/llvm/prebuilt/* | head -1)"
for tool in git "$CMAKE" "$NINJA"; do
  command -v "$tool" > /dev/null 2>&1 || { echo "$tool is missing; see Requirements in README.md" >&2; exit 1; }
done
if [ ! -f "$SENSORS/CMakeLists.txt" ]; then
  echo "No Android device module at $SENSORS: clone opendaq-android-device-module beside this repository, or set ANDROID_DEVICE_MODULE" >&2
  exit 1
fi

mkdir -p "$WORK"
# The release, with the change Android needs, applied only when it is not
# there yet.
"$HERE/tool/prepare_sdk.sh" "$WORK/openDAQ" "$SDK_SOURCE" "$SDK_REF"

COMMON="-G Ninja -DCMAKE_MAKE_PROGRAM=$NINJA -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5"
# A compiler cache: a file compiled before, in any build folder, with the
# same flags, comes from the cache instead of the compiler.
if command -v ccache > /dev/null 2>&1; then
  COMMON="$COMMON -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache"
  export CCACHE_BASEDIR="$PARENT"
fi

# Configures a build folder only when it has none yet or its options changed.
# Ninja configures again by itself when a CMakeLists.txt changes.
configure() {
  source="$1"
  folder="$2"
  log="$3"
  shift 3
  options="$source $*"
  if [ -f "$folder/build.ninja" ] && [ "$(cat "$folder/.configure-options" 2> /dev/null)" = "$options" ]; then
    return
  fi
  "$CMAKE" -S "$source" -B "$folder" "$@" > "$log"
  printf '%s' "$options" > "$folder/.configure-options"
}

echo "== openDAQ SDK $SDK_REF (native streaming server only)"
# shellcheck disable=SC2086
configure "$WORK/openDAQ" "$SDK_BUILD" "$WORK/sdk-configure.log" $COMMON \
  -DOPENDAQ_ENABLE_TESTS=OFF -DOPENDAQ_ENABLE_TEST_UTILS=OFF \
  -DOPENDAQ_ENABLE_NATIVE_STREAMING=ON -DDAQMODULES_OPENDAQ_SERVER_MODULE=ON \
  -DDAQMODULES_OPENDAQ_CLIENT_MODULE=OFF -DOPENDAQ_ENABLE_OPCUA=OFF \
  -DDAQMODULES_REF_DEVICE_MODULE=OFF -DDAQMODULES_REF_FB_MODULE=OFF
"$CMAKE" --build "$SDK_BUILD" > "$WORK/sdk-build.log"
"$CMAKE" --install "$SDK_BUILD" --prefix "$SDK_INSTALL" > "$WORK/sdk-install.log"

FIND="-DCMAKE_PREFIX_PATH=$SDK_INSTALL -DCMAKE_FIND_ROOT_PATH=$SDK_INSTALL"

echo "== Android device module"
# shellcheck disable=SC2086
configure "$SENSORS" "$WORK/android-device-arm64" "$WORK/android-device-configure.log" $COMMON $FIND
"$CMAKE" --build "$WORK/android-device-arm64"

BLOCKS_LIBRARY=""
if [ -f "$BLOCKS/CMakeLists.txt" ]; then
  echo "== function blocks module"
  # shellcheck disable=SC2086
  configure "$BLOCKS" "$WORK/function-blocks-arm64" "$WORK/function-blocks-configure.log" $COMMON $FIND
  "$CMAKE" --build "$WORK/function-blocks-arm64"
  BLOCKS_LIBRARY="$WORK/function-blocks-arm64/libuniversal_function_blocks_module.module.so"
else
  echo "== function blocks module: none at $BLOCKS, so the app is built without it"
fi

echo "== device server"
# shellcheck disable=SC2086
configure "$HERE/native/server" "$WORK/sensor-server-arm64" "$WORK/sensor-server-configure.log" $COMMON $FIND
"$CMAKE" --build "$WORK/sensor-server-arm64"

echo "== copying into $JNILIBS"
mkdir -p "$JNILIBS"
rm -f "$JNILIBS"/*.so
set -- \
  "$WORK/sensor-server-arm64/libsensor_server.so" \
  "$WORK/android-device-arm64/libandroid_device_module.module.so" \
  "$SDK_BUILD/bin/libopendaq-64-3.so" \
  "$SDK_BUILD/bin/libdaqcoreobjects-64-3.so" \
  "$SDK_BUILD/bin/libdaqcoretypes-64-3.so" \
  "$SDK_BUILD/bin/libnative_stream_srv_module-64-3.module.so" \
  "$LLVM/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [ -n "$BLOCKS_LIBRARY" ]; then
  set -- "$@" "$BLOCKS_LIBRARY"
fi
for library in "$@"; do
  "$LLVM/bin/llvm-strip" --strip-unneeded -o "$JNILIBS/$(basename "$library")" "$library"
done
ls -l "$JNILIBS"
