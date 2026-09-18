# Latency-trial tools

## Wireless ADB discovery

`tool/discover-wireless-adb.sh` prints a `host:port` endpoint for the known
9469X tablet's `_adb-tls-connect._tcp` service without hardcoding its IP. It
first uses `adb mdns services`; when that command is unavailable it queries
Avahi with `avahi-browse -rtp _adb-tls-connect._tcp`. It only discovers: it
never pairs or connects a device.

```bash
tool/discover-wireless-adb.sh
# then, only when an operator decides to connect:
adb connect "$(tool/discover-wireless-adb.sh)"
```

The helper needs `adb` and, for the fallback, `avahi-browse` on `PATH` (or
`ADB_BIN` / `AVAHI_BROWSE_BIN` overrides). Declaring those packages does not
make them present in the active host profile; run `ns` before relying on a
new system declaration. The helper does not activate packages.

Run fixture coverage with `tool/test-discover-wireless-adb.sh`.
