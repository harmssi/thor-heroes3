#!/bin/bash
# Build the Thor Edition APK: sync sources to a Linux host with Docker, build in the
# vcmi-android-build image (docker/BuildAndroid-aarch64.dockerfile), fetch dist/ThorHeroes.apk.
# Signing key + properties live only on the build host ($REMOTE/keys). Optional: --install
set -euo pipefail
# local settings (build host, adb serial) stay outside the repo
[ -f "$HOME/.config/thorheroes/env" ] && . "$HOME/.config/thorheroes/env"
HOST=${THOR_BUILD_HOST:?set THOR_BUILD_HOST=user@linux-host-with-docker}
REMOTE=${THOR_BUILD_DIR:-/mnt/cache/thor-homm3}
REPO="$(cd "$(dirname "$0")/.." && pwd)"

# no -t: changed files get a fresh mtime on the host, so ninja never skips them (host .o may be newer)
rsync -rlp --checksum --delete --exclude .git --exclude /out --exclude /conan-generated --exclude /.cache \
    --exclude /dist --exclude /CI/android/thorSigning.properties --exclude /CI/android/thorheroes.jks \
    "$REPO/" "$HOST:$REMOTE/src/"

ssh "$HOST" "cp $REMOTE/keys/thorSigning.properties $REMOTE/keys/thorheroes.jks $REMOTE/src/CI/android/ && \
  docker run --rm -v $REMOTE/src:/vcmi vcmi-android-build sh -c '
    cd /vcmi && mkdir -p \$GRADLE_USER_HOME &&
    echo android.bundle.enableUncompressedNativeLibs=true > \$GRADLE_USER_HOME/gradle.properties &&
    { [ -f conan-generated/conan_toolchain.cmake ] || conan install . --output-folder=conan-generated --build=never \
        --profile=dependencies/conan_profiles/android-64 --profile=dependencies/conan_profiles/base/android-system \
        -c tools.android:ndk_path=\$ANDROID_HOME/ndk/\$NDK_VERSION; } &&
    cmake --preset android-thor-release && cmake --build --preset android-thor-release' > $REMOTE/build.log 2>&1; \
  rc=\$?; tail -30 $REMOTE/build.log; exit \$rc"

mkdir -p "$REPO/dist"
APK=$(ssh "$HOST" "ls -t $REMOTE/src/out/build/android-thor-release/android-build/vcmi-app/build/outputs/apk/release/*.apk | head -1")
scp "$HOST:$APK" "$REPO/dist/ThorHeroes.apk"
echo "APK: $REPO/dist/ThorHeroes.apk"
[ "${1:-}" = "--install" ] && adb install -r "$REPO/dist/ThorHeroes.apk"
exit 0
