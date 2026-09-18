#!/usr/bin/env bash
# Run a Flutter or Gradle workload that can rewrite generated project metadata.
# All supported workloads share one host-wide lock and a bounded user scope.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
flutter="${BUTTERFLY_FLUTTER_BIN:-/home/joebutler/.cache/flutter-3.47.4/bin/flutter}"
lock="${BUTTERFLY_WORKLOAD_LOCK:-/tmp/butterfly-generated-metadata.lock}"
lock_timeout="${BUTTERFLY_WORKLOAD_LOCK_TIMEOUT:-1800}"
test_concurrency="${BUTTERFLY_TEST_CONCURRENCY:-2}"

usage() {
  cat <<USAGE
usage: tool/butterfly-workload.sh [--dry-run] {analyze|test|apk|gradle} [arguments...]

Run every Butterfly Flutter analysis, test, APK, or Gradle command that can
rewrite generated metadata through this command. It serializes those commands
host-wide and runs them in agent-workloads.slice with explicit resource caps.
USAGE
}

dry_run=false
if [[ "${1:-}" == "--dry-run" ]]; then
  dry_run=true
  shift
fi
workload="${1:-}"
[[ -n "$workload" ]] || { usage >&2; exit 64; }
shift

case "$workload" in
  analyze)
    workdir="$repo/app"
    command=("$flutter" analyze "$@")
    ;;
  test)
    workdir="$repo/app"
    command=("$flutter" test --concurrency "$test_concurrency" "$@")
    ;;
  apk)
    workdir="$repo/app"
    command=("$flutter" build apk --flavor latencyLab --release --target-platform android-arm64 "$@")
    ;;
  gradle)
    workdir="$repo/app/android"
    command=(./gradlew "$@")
    ;;
  *)
    usage >&2
    exit 64
    ;;
esac

scope=(
  systemd-run --user --scope --quiet
  --slice=agent-workloads.slice --unit="butterfly-${workload}-$$"
  -p CPUWeight=50 -p MemoryHigh=6G -p MemoryMax=8G -p MemorySwapMax=2G
  -p TasksMax=128
  --setenv="GRADLE_OPTS=-Xmx2g -XX:MaxMetaspaceSize=1g -XX:ReservedCodeCacheSize=256m"
)
run=(nix shell nixpkgs#jdk21 -c "${command[@]}")

if "$dry_run"; then
  printf "lock=%q\nworkdir=%q\nscope=" "$lock" "$workdir"
  printf "%q " "${scope[@]}"
  printf "\ncommand="
  printf "%q " "${run[@]}"
  printf "\n"
  exit 0
fi

exec 9>"$lock"
if ! flock -w "$lock_timeout" 9; then
  echo "another Butterfly metadata workload holds $lock" >&2
  exit 75
fi
cd "$workdir"
"${scope[@]}" "${run[@]}"

if [[ "$workload" == apk ]]; then
  apk=build/app/outputs/flutter-apk/app-latencylab-release.apk
  ls -l "$apk"
  sha256sum "$apk"
fi
