#!/usr/bin/env bash
# Print one TLS wireless-debug endpoint without pairing or connecting.
set -euo pipefail

device_match="9469X"
adb="${ADB_BIN:-adb}"
avahi="${AVAHI_BROWSE_BIN:-avahi-browse}"

usage() {
  cat <<'USAGE'
usage: tool/discover-wireless-adb.sh [--match NAME]

Print the first NAME-matching Android TLS wireless-debug endpoint as host:port.
The default NAME is 9469X. This command is read-only: it only runs `adb mdns
services` or, when that is unavailable, `avahi-browse -rtp
_adb-tls-connect._tcp`; it never runs adb pair or adb connect.
USAGE
}

while (($#)); do
  case "$1" in
    --match)
      [[ $# -ge 2 ]] || { echo "--match requires a value" >&2; exit 64; }
      device_match="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 64
      ;;
  esac
done

match_lower=$(printf '%s' "$device_match" | tr '[:upper:]' '[:lower:]')
parse_adb_mdns() {
  awk -v needle="$match_lower" '
    index(tolower($0), needle) && /_adb-tls-connect[.]_tcp/ {
      for (field = 1; field <= NF; field++) {
        if ($field ~ /:[0-9]+$/) {
          print $field
          exit
        }
      }
    }
  '
}

if command -v "$adb" >/dev/null 2>&1; then
  if mdns_output=$("$adb" mdns services 2>&1); then
    endpoint=$(printf '%s\n' "$mdns_output" | parse_adb_mdns)
    if [[ -n "$endpoint" ]]; then
      printf '%s\n' "$endpoint"
      exit 0
    fi
    echo "no ${device_match} _adb-tls-connect._tcp service found by adb mDNS" >&2
    exit 69
  fi
  echo "adb mDNS is unavailable; falling back to Avahi" >&2
else
  echo "adb is unavailable; falling back to Avahi" >&2
fi

command -v "$avahi" >/dev/null 2>&1 || {
  echo "avahi-browse is required when adb mDNS is unavailable" >&2
  exit 69
}
endpoint=$("$avahi" -rtp _adb-tls-connect._tcp 2>/dev/null | awk -F ';' -v needle="$match_lower" '
  $1 == "=" && !found && index(tolower($0), needle) {
    host = $8
    port = $9
    if (host ~ /:/) {
      printf "[%s]:%s\n", host, port
    } else {
      printf "%s:%s\n", host, port
    }
    found = 1
  }
')
if [[ -z "$endpoint" ]]; then
  echo "no ${device_match} _adb-tls-connect._tcp service found by Avahi" >&2
  exit 69
fi
printf '%s\n' "$endpoint"
