# TCL 120 Hz session override

Non-root, non-flashing workaround tested on the NXTPAPER 11 Plus (9469X), Android
16. Android's existing `DisplayEventReceiver` supplies physical display-mode
events; the helper requests 120 Hz again after a drop using the existing
`cmd settings` command. No polling, installed app, Butterfly changes, document
access, or modified firmware. It changes only `peak_refresh_rate` and
`min_refresh_rate`.

This **does not disable TCL's controller or prevent every 60 Hz frame**. It reacts
after a mode change. It also overrides intentional lower-rate requests: stop it
before selecting a lower rate. Expect increased battery use. Independent thermal
or display restrictions may still limit the panel; those protections are not
disabled. It attempts restoration once per distinct mode/period event, not
repeatedly against an unchanged lower mode.

This is a device-specific session tool using a **non-SDK framework API**, not a
portable Android application. The compile-only `DisplayEventReceiver.java`
signature is excluded from the jar; the device supplies the real implementation.
Model/SDK checks limit accidental use but do not guarantee compatibility with
future firmware. The 9 ms threshold distinguishes the observed 8.33 ms and
16.67 ms frame periods; it is not a wait. The five-second child-process deadline
bounds command completion, not refresh detection.

## Build and start

Build on Minworker in the Butterfly checkout, using the SDK declared in
`app/android/local.properties` and JDK 21:

```sh
nix shell 'nixpkgs#jdk21' --command bash tool/refresh120/build.sh
```

Copy the printed jar to the ADB host. Resolve the tablet's current serial and
physical display ID first; this tablet reported `4627039422300187648` during
verification. Do not substitute DisplayManager's logical display ID `0`.

```sh
adb devices -l
adb -s TABLET_SERIAL shell dumpsys SurfaceFlinger --display-id
adb -s TABLET_SERIAL shell pidof butterfly-refresh-120
```

If already running, stop the exact returned PID before replacing its jar or
truncating its log. Otherwise:

```sh
adb -s TABLET_SERIAL push /path/to/refresh120.jar /data/local/tmp/butterfly-refresh-120.jar
adb -s TABLET_SERIAL shell 'CLASSPATH=/data/local/tmp/butterfly-refresh-120.jar nohup app_process /system/bin --nice-name=butterfly-refresh-120 Refresh120 PHYSICAL_DISPLAY_ID </dev/null >/data/local/tmp/butterfly-refresh-120.log 2>&1 &'
adb -s TABLET_SERIAL shell cat /data/local/tmp/butterfly-refresh-120.log
```

Require `READY pid=...`; an exception means startup failed. A second instance
exits with `ALREADY_RUNNING` before writing settings. An incorrect display ID
can still produce READY but will not receive the intended events: verify an
actual reset and recovery, not just startup. Raw event mode IDs differ from
DisplayManager mode IDs; use the reported period and physical display readback.

The process runs on the tablet independently of Butterfly and the ADB connection.
Reboot or Android terminating it stops the override. No automatic restart or boot
mechanism is installed. Closing an ADB terminal is not a stop command.

## Verify and stop

```sh
adb -s TABLET_SERIAL shell cat /data/local/tmp/butterfly-refresh-120.log
adb -s TABLET_SERIAL shell dumpsys display
adb -s TABLET_SERIAL shell pidof butterfly-refresh-120
adb -s TABLET_SERIAL shell kill -TERM EXACT_PID_FROM_ABOVE
```

On this firmware, DisplayManager active mode 1 is 120 Hz and mode 2 is 60 Hz.
The log should show a 16.67 ms event followed by a request and an 8.33 ms event
after a TCL reset. Settings readback alone is not physical-display proof. Writes
to the two settings are not atomic: a concurrent writer can leave differing
values even while the panel remains at 120 Hz. This is a mode-change workaround,
not a guarantee that every settings write is immediately reversed.

Stopping leaves the last settings in place; TCL resumes control on its next
trigger. The jar, lock and log under `/data/local/tmp/butterfly-refresh-120.*`
are the only device files used. No private data or system packages are altered.

## Evidence and rejected routes (2026-09-16)

- With the helper stopped, a status-bar finger tap triggered TCL's delayed reset
  and the physical panel settled at 60 Hz. With it running, the same trigger
  produced a 60-to-120 Hz event sequence and physical 120 Hz readback.
- A shell-written 60 Hz request also caused recovery. A deliberately interleaved
  reverse-order pair of settings writes briefly left min=120/peak=60 while the
  panel was 120 Hz; the next ordinary TCL reset restored both to 120.
- A duplicate launch failed with `ALREADY_RUNNING`, leaving the original alive.
- Java compilation, dex packaging, shell syntax and whitespace checks passed.
  The timer audit found no helper candidates; manual review classified the
  command deadline and frame-period threshold as the explicit contracts above.
- `ContentObserver` rejected an unregistered standalone shell PID. The public
  `DisplayManager` listener registered but Android's foreground-importance filter
  suppressed refresh events for shell UID 2000. Neither route is retained.
- Handwriting feel, flicker during repeated pen transitions, battery impact,
  long-session survival and firmware-update compatibility remain unverified.
  No input-to-ink latency improvement in milliseconds is claimed.
