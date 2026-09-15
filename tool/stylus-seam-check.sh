#!/usr/bin/env bash
#
# stylus-seam-check.sh -- repeatable "wet-to-dry seam" latency check for the
# Butterfly LatencyLab trial harness on an Android tablet.
#
# Draws one stylus stroke in a chosen latency mode, screenshots it at 40%
# and 80% through the stroke, then right after lift (+0.3s), +1.0s and
# +2.5s, and diffs the stroke band across those later captures with
# ImageMagick `compare -metric AE`. If the "wet" stroke visibly settles/
# redraws after lift (a seam), the AE counts are non-zero -> FAIL.
#
# Usage:
#   stylus-seam-check.sh MODE [STROKE_MS] [OUT_DIR]
#
#   MODE       one of: flutter_only, shared_geometry, ink_prediction_on
#   STROKE_MS  stylus swipe duration in ms (default 3000)
#   OUT_DIR    where screenshots/crops/report go (default ./seam-check-out/<mode>_<ts>)
#
# Exit codes: 0 = PASS, 1 = FAIL, 2 = guard abort (unsafe focus / setup failure)
#
# HARD SAFETY RULES (do not relax these):
#   - Input is only ever injected while `dumpsys window | grep mCurrentFocus`
#     shows the TRIAL package below. Any other focus -> abort (exit 2).
#   - This script must NEVER start, force-stop, or otherwise touch the
#     REAL_PKG below (the user's real document app). It only ever
#     addresses TRIAL_PKG/TRIAL_ACT.
#   - Every run must exercise the LatencyLab Quickstart flow to create a
#     fresh scratch document; no existing document is ever opened.
#   - Cleanup never leaves anything behind beyond that scratch document
#     (the trial app is force-stopped on every exit path).

set -euo pipefail

# ---------------------------------------------------------------------------
# Fixed device / package facts
# ---------------------------------------------------------------------------
ADB="${ADB_BIN:-/home/joebutler/.local/ptt-droid-tools/android-sdk/platform-tools/adb}"
SERIAL="${DEVICE_SERIAL:-192.168.1.205:35029}"

readonly TRIAL_PKG="dev.linwood.butterfly.latencylab"
readonly TRIAL_ACT="dev.linwood.butterfly.LatencyLabActivity"
readonly MODE_EXTRA="dev.linwood.butterfly.extra.STYLUS_LATENCY_MODE"
# NEVER referenced in any adb command below -- the user's real document app.
readonly REAL_PKG="dev.linwood.butterfly"

readonly QUICKSTART_PLAIN_X=330
readonly QUICKSTART_PLAIN_Y=1045
readonly PEN_TOOL_X=1263
readonly PEN_TOOL_Y=79

readonly SWIPE_X1=400 SWIPE_Y1=700 SWIPE_X2=1600 SWIPE_Y2=850

# crop bands (screen is 2200x1440 landscape)
readonly STROKE_X0=300 STROKE_Y0=620 STROKE_X1=1900 STROKE_Y1=920
readonly TOAST_X0=600  TOAST_Y0=1180 TOAST_X1=1600 TOAST_Y1=1320

# ---------------------------------------------------------------------------
# Args
# ---------------------------------------------------------------------------
usage() {
  echo "usage: $(basename "$0") MODE [STROKE_MS] [OUT_DIR]" >&2
  echo "  MODE in: flutter_only shared_geometry ink_prediction_on" >&2
  exit 64
}

MODE="${1:-}"
[[ -z "$MODE" ]] && usage
case "$MODE" in
  flutter_only|shared_geometry|ink_prediction_on) ;;
  *) echo "error: unknown MODE '$MODE'" >&2; usage ;;
esac

STROKE_MS="${2:-3000}"
if ! [[ "$STROKE_MS" =~ ^[0-9]+$ ]]; then
  echo "error: STROKE_MS must be a positive integer, got '$STROKE_MS'" >&2
  usage
fi

TS_TAG="$(date +%Y%m%dT%H%M%S)"
OUT_DIR="${3:-./seam-check-out/${MODE}_${TS_TAG}}"
mkdir -p "$OUT_DIR"

TIMESTAMP_LOG="$OUT_DIR/timestamps.tsv"
: > "$TIMESTAMP_LOG"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
adbs() { "$ADB" -s "$SERIAL" "$@"; }

ABORTED=0
cleanup() {
  # Always leave the device with the trial app stopped. Never touches REAL_PKG.
  adbs shell am force-stop "$TRIAL_PKG" >/dev/null 2>&1 || true
}
trap cleanup EXIT

require_focus() {
  local desc="$1" win
  win="$(adbs shell dumpsys window 2>/dev/null | tr -d '\r' | grep mCurrentFocus || true)"
  if [[ "$win" != *"$TRIAL_PKG"* ]]; then
    echo "GUARD ABORT before ${desc}: mCurrentFocus does not show ${TRIAL_PKG}" >&2
    echo "  mCurrentFocus: ${win:-<empty>}" >&2
    ABORTED=1
    exit 2
  fi
  echo "guard ok before ${desc}: ${win}" >&2
}

# capture_at TARGET_MS LABEL OUTFILE -- sleeps until TARGET_MS have elapsed
# since $START_EPOCH (real time, accounting for time already spent), then
# screenshots and logs the real timestamp actually achieved.
capture_at() {
  local target_ms="$1" label="$2" outfile="$3"
  local now elapsed remaining
  now="$(date +%s.%N)"
  elapsed="$(awk -v a="$now" -v b="$START_EPOCH" 'BEGIN{printf "%.3f", a-b}')"
  remaining="$(awk -v t="$target_ms" -v e="$elapsed" 'BEGIN{printf "%.3f", (t/1000)-e}')"
  if awk -v r="$remaining" 'BEGIN{exit !(r>0)}'; then
    sleep "$remaining"
  fi
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$outfile"
  local ts elapsed_actual
  ts="$(date +%s.%3N)"
  elapsed_actual="$(awk -v a="$ts" -v b="$START_EPOCH" 'BEGIN{printf "%.3f", a-b}')"
  printf '%s\t%s\t%s\t%s\n' "$label" "$target_ms" "$ts" "$elapsed_actual" >> "$TIMESTAMP_LOG"
}

crop() {
  local src="$1" x0="$2" y0="$3" x1="$4" y1="$5" dst="$6"
  magick "$src" -crop "$((x1-x0))x$((y1-y0))+${x0}+${y0}" +repage "$dst"
}

ae_count() {
  # Prints the AE (Absolute Error, pixel count, possibly fractional under
  # -fuzz) between two images, ignoring compare's non-zero exit status when
  # images differ.
  local a="$1" b="$2" out
  out="$(magick compare -metric AE -fuzz 5% "$a" "$b" null: 2>&1 >/dev/null || true)"
  # compare reports "N (ratio)" or "N.NN (ratio)"; keep the leading number.
  echo "$out" | grep -oE '^[0-9]+(\.[0-9]+)?' | head -1
}

ae_is_zero() {
  # Numeric zero check (not string equality) so "0.00" etc. still counts.
  local v="$1"
  [[ -n "$v" ]] && awk -v x="$v" 'BEGIN{exit !(x==0)}'
}

# ---------------------------------------------------------------------------
# 1. Setup: fresh scratch document in the requested mode
# ---------------------------------------------------------------------------
echo "== stylus-seam-check: mode=${MODE} stroke_ms=${STROKE_MS} out=${OUT_DIR} ==" >&2

adbs shell am force-stop "$TRIAL_PKG"
adbs shell am start -n "${TRIAL_PKG}/${TRIAL_ACT}" -e "$MODE_EXTRA" "$MODE" >/dev/null
sleep 4

require_focus "Quickstart tap"
adbs shell input tap "$QUICKSTART_PLAIN_X" "$QUICKSTART_PLAIN_Y"
sleep 3

require_focus "pen-tool tap"
adbs shell input tap "$PEN_TOOL_X" "$PEN_TOOL_Y"
sleep 1

require_focus "stylus stroke"

# ---------------------------------------------------------------------------
# 2. Draw one stroke, screenshot at 40%/80% through it and after lift
# ---------------------------------------------------------------------------
START_EPOCH="$(date +%s.%N)"
adbs shell input stylus swipe "$SWIPE_X1" "$SWIPE_Y1" "$SWIPE_X2" "$SWIPE_Y2" "$STROKE_MS" &
SWIPE_PID=$!

T40=$(( STROKE_MS * 40 / 100 ))
T80=$(( STROKE_MS * 80 / 100 ))
TLIFT03=$(( STROKE_MS + 300 ))
TPLUS1=$(( STROKE_MS + 1000 ))
TPLUS25=$(( STROKE_MS + 2500 ))

SHOT_40="$OUT_DIR/shot_40pct.png"
SHOT_80="$OUT_DIR/shot_80pct.png"
SHOT_LIFT="$OUT_DIR/shot_lift_p0.3s.png"
SHOT_P1="$OUT_DIR/shot_plus1.0s.png"
SHOT_P25="$OUT_DIR/shot_plus2.5s.png"

capture_at "$T40"     "40pct"        "$SHOT_40"
capture_at "$T80"     "80pct"        "$SHOT_80"
capture_at "$TLIFT03" "lift+0.3s"    "$SHOT_LIFT"
capture_at "$TPLUS1"  "lift+1.0s"    "$SHOT_P1"
capture_at "$TPLUS25" "lift+2.5s"    "$SHOT_P25"

wait "$SWIPE_PID" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 3. Crop stroke band / toast band, compute AE, build viewing artifacts
# ---------------------------------------------------------------------------
CROP_STROKE_40="$OUT_DIR/crop_stroke_40pct_wet.png"
CROP_STROKE_LIFT="$OUT_DIR/crop_stroke_lift_p0.3s.png"
CROP_STROKE_P1="$OUT_DIR/crop_stroke_plus1.0s.png"
CROP_STROKE_P25="$OUT_DIR/crop_stroke_plus2.5s_dry.png"
CROP_TOAST_40="$OUT_DIR/crop_toast_40pct.png"
CROP_TOAST_LIFT="$OUT_DIR/crop_toast_lift_p0.3s.png"
STACKED="$OUT_DIR/stacked_wet_vs_dry.png"

crop "$SHOT_40"   "$STROKE_X0" "$STROKE_Y0" "$STROKE_X1" "$STROKE_Y1" "$CROP_STROKE_40"
crop "$SHOT_LIFT" "$STROKE_X0" "$STROKE_Y0" "$STROKE_X1" "$STROKE_Y1" "$CROP_STROKE_LIFT"
crop "$SHOT_P1"   "$STROKE_X0" "$STROKE_Y0" "$STROKE_X1" "$STROKE_Y1" "$CROP_STROKE_P1"
crop "$SHOT_P25"  "$STROKE_X0" "$STROKE_Y0" "$STROKE_X1" "$STROKE_Y1" "$CROP_STROKE_P25"

crop "$SHOT_40"   "$TOAST_X0" "$TOAST_Y0" "$TOAST_X1" "$TOAST_Y1" "$CROP_TOAST_40"
crop "$SHOT_LIFT" "$TOAST_X0" "$TOAST_Y0" "$TOAST_X1" "$TOAST_Y1" "$CROP_TOAST_LIFT"

magick "$CROP_STROKE_40" "$CROP_STROKE_P25" -append "$STACKED"

AE_LIFT_VS_25="$(ae_count "$CROP_STROKE_LIFT" "$CROP_STROKE_P25")"
AE_1_VS_25="$(ae_count "$CROP_STROKE_P1" "$CROP_STROKE_P25")"
AE_LIFT_VS_25="${AE_LIFT_VS_25:-NA}"
AE_1_VS_25="${AE_1_VS_25:-NA}"

# ---------------------------------------------------------------------------
# 4. Report
# ---------------------------------------------------------------------------
PASS=0
if ae_is_zero "$AE_LIFT_VS_25" && ae_is_zero "$AE_1_VS_25"; then
  PASS=1
fi

{
  echo "=================== stylus seam-check report ===================="
  echo "mode:            $MODE"
  echo "stroke duration: ${STROKE_MS} ms"
  echo "out dir:         $OUT_DIR"
  echo "-------------------------------------------------------------------"
  printf '%-12s %-10s %-24s %s\n' "capture" "target_ms" "epoch_ts" "elapsed_s"
  while IFS=$'\t' read -r label target ts elapsed; do
    printf '%-12s %-10s %-24s %s\n' "$label" "$target" "$ts" "$elapsed"
  done < "$TIMESTAMP_LOG"
  echo "-------------------------------------------------------------------"
  echo "AE(lift+0.3s vs +2.5s): $AE_LIFT_VS_25"
  echo "AE(+1.0s     vs +2.5s): $AE_1_VS_25"
  echo "-------------------------------------------------------------------"
  echo "crops:"
  echo "  stroke 40% (wet):        $CROP_STROKE_40"
  echo "  stroke lift+0.3s:        $CROP_STROKE_LIFT"
  echo "  stroke lift+1.0s:        $CROP_STROKE_P1"
  echo "  stroke lift+2.5s (dry):  $CROP_STROKE_P25"
  echo "  toast @ 40%:             $CROP_TOAST_40"
  echo "  toast @ lift+0.3s:       $CROP_TOAST_LIFT"
  echo "  wet-vs-dry stacked:      $STACKED"
  echo "-------------------------------------------------------------------"
  if [[ "$PASS" == "1" ]]; then
    echo "RESULT: PASS"
  else
    echo "RESULT: FAIL"
  fi
  echo "===================================================================="
} | tee "$OUT_DIR/report.txt" >&2

if [[ "$PASS" == "1" ]]; then
  exit 0
else
  exit 1
fi
