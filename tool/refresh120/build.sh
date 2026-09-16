#!/usr/bin/env bash
set -euo pipefail
source_dir=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$source_dir/../.." && pwd)
sdk=$(sed -n 's/^sdk.dir=//p' "$repo/app/android/local.properties")
test -f "$sdk/platforms/android-36/android.jar"
build_dir=$(mktemp -d /tmp/butterfly-refresh-build.XXXXXX)
mkdir "$build_dir/classes" "$build_dir/dex" "$build_dir/stubs"
javac --release 17 -cp "$sdk/platforms/android-36/android.jar" \
    -d "$build_dir/stubs" "$source_dir/DisplayEventReceiver.java"
javac --release 17 -cp "$sdk/platforms/android-36/android.jar:$build_dir/stubs" \
    -d "$build_dir/classes" "$source_dir/Refresh120.java"
jar --create --file "$build_dir/input.jar" -C "$build_dir/classes" .
"$sdk/build-tools/36.0.0/d8" --min-api 36 \
    --lib "$sdk/platforms/android-36/android.jar" \
    --output "$build_dir/dex" "$build_dir/input.jar"
jar --create --file "$build_dir/refresh120.jar" -C "$build_dir/dex" classes.dex
printf '%s\n' "$build_dir/refresh120.jar"
