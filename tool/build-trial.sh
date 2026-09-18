#!/usr/bin/env bash
# Compatibility entry point for the latencyLab release APK. All Flutter and
# Gradle workloads use the shared metadata lock in butterfly-workload.sh.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
exec "$repo/tool/butterfly-workload.sh" apk "$@"
