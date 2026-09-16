#!/usr/bin/env bash
# Build the Butterfly latencyLab release APK on minworker inside a bounded
# systemd scope, one build at a time. Usage: tool/build-trial.sh [extra flutter args]
# Finish Flutter tests before starting this build: both commands regenerate the
# same plugin registrant, with different release/test plugin sets.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
lock=/tmp/butterfly-build.lock
export PATH=/home/joebutler/.cache/flutter-3.47.4/bin:$PATH
# One Gradle at a time across every worktree on this host.
exec 9>"$lock"
if ! flock -w 1800 9; then echo "another build holds $lock" >&2; exit 75; fi
cd "$repo/app"
# The agent-workloads slice caps memory (6G high / 8G max) and swap, so a
# runaway build is killed before it can throttle the whole user session.
systemd-run --user --scope --quiet \
  --slice=agent-workloads.slice --unit="butterfly-build-$$" -p CPUWeight=50 \
  nix shell nixpkgs#jdk21 -c \
  flutter build apk --flavor latencyLab --release --target-platform android-arm64 "$@"
apk=build/app/outputs/flutter-apk/app-latencylab-release.apk
ls -l "$apk" && sha256sum "$apk"
