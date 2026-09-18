#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
tool="$repo/tool/discover-wireless-adb.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
bin="$tmp/bin"
mkdir -p "$bin"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}
write_adb() {
  cat > "$bin/adb" <<'SCRIPT'
#!/usr/bin/env bash
if [[ "$*" != "mdns services" ]]; then
  echo "unexpected adb args: $*" >&2
  exit 97
fi
cat "$ADB_MDNS_FIXTURE"
exit "${ADB_MDNS_EXIT:-0}"
SCRIPT
  chmod +x "$bin/adb"
}
write_avahi() {
  cat > "$bin/avahi-browse" <<'SCRIPT'
#!/usr/bin/env bash
if [[ "$*" != "-rtp _adb-tls-connect._tcp" ]]; then
  echo "unexpected avahi args: $*" >&2
  exit 98
fi
cat "$AVAHI_FIXTURE"
SCRIPT
  chmod +x "$bin/avahi-browse"
}

write_adb
write_avahi
printf '%s\n' '9469X _adb-tls-connect._tcp 192.0.2.44:37123' > "$tmp/adb-mdns"
printf '%s\n' '=;wlan0;IPv4;wrong;_adb-tls-connect._tcp;local;wrong.local;192.0.2.10;37123;' > "$tmp/avahi-unused"
endpoint=$(ADB_BIN="$bin/adb" AVAHI_BROWSE_BIN="$bin/avahi-browse" ADB_MDNS_FIXTURE="$tmp/adb-mdns" AVAHI_FIXTURE="$tmp/avahi-unused" "$tool")
[[ "$endpoint" == "192.0.2.44:37123" ]] || fail "adb mDNS endpoint was $endpoint"

printf '%s\n' 'adb: unknown command mdns' > "$tmp/adb-unsupported"
printf '%s\n' '=;wlan0;IPv4;TCL 9469X;_adb-tls-connect._tcp;local;9469x.local;192.0.2.99;43210;' > "$tmp/avahi"
endpoint=$(ADB_BIN="$bin/adb" AVAHI_BROWSE_BIN="$bin/avahi-browse" ADB_MDNS_FIXTURE="$tmp/adb-unsupported" ADB_MDNS_EXIT=1 AVAHI_FIXTURE="$tmp/avahi" "$tool" 2>"$tmp/fallback.stderr")
[[ "$endpoint" == "192.0.2.99:43210" ]] || fail "Avahi endpoint was $endpoint"
grep -Fq 'falling back to Avahi' "$tmp/fallback.stderr" || fail 'fallback was not reported'

printf '%s\n' '9469X _adb-tls-pairing._tcp 192.0.2.44:37123' > "$tmp/adb-wrong-service"
if ADB_BIN="$bin/adb" AVAHI_BROWSE_BIN="$bin/avahi-browse" ADB_MDNS_FIXTURE="$tmp/adb-wrong-service" AVAHI_FIXTURE="$tmp/avahi-unused" "$tool" >"$tmp/no-match.out" 2>"$tmp/no-match.err"; then
  fail 'non-connect mDNS service unexpectedly succeeded'
fi
grep -Fq '_adb-tls-connect._tcp' "$tmp/no-match.err" || fail 'missing-service diagnostic was not specific'

echo 'wireless ADB discovery fixtures: PASS'
