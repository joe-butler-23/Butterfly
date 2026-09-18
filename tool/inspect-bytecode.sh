#!/usr/bin/env bash
# Inspect a dependency class without relying on javap in the host profile.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tool/inspect-bytecode.sh <classpath> <fully-qualified-class> [javap options...]

Runs the Nix JDK21 javap against a JAR or classes directory. The classpath can
contain the normal path separator list. This command only reads the supplied
artifact; it does not run Gradle or modify generated metadata.

Example:
  tool/inspect-bytecode.sh ~/.gradle/caches/.../junit-4.13.2.jar org.junit.Assert
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if (($# < 2)); then
  usage >&2
  exit 2
fi

classpath="$1"
class_name="$2"
shift 2

if [[ ! -e "${classpath%%:*}" ]]; then
  printf "inspect-bytecode: classpath entry does not exist: %s\\n" "${classpath%%:*}" >&2
  exit 2
fi

exec nix shell nixpkgs#jdk21 --command javap -classpath "$classpath" -c -p "$@" "$class_name"
