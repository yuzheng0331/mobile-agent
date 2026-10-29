#!/usr/bin/env bash
# One round of the virtual-display performance test (docs/virtual-display-experiments.md, items 11-12):
# resets the main-screen app's frame stats, optionally runs whole-screen OCR on the virtual display in a
# loop, waits while the user scrolls on the main screen, then prints jank and CPU numbers.
# Usage: load_round.sh <main-app-package> <seconds> [virtual-display-id-for-OCR]
set -euo pipefail
export MSYS_NO_PATHCONV=1
app="$1"; seconds="$2"; ocr_display="${3:-}"

adb shell dumpsys gfxinfo "$app" reset >/dev/null
adb logcat -c
end=$((SECONDS + seconds))
if [[ -n "$ocr_display" ]]; then
  while (( SECONDS < end )); do
    adb shell am broadcast -n dev.phoneagent.app/.DisplayProbeReceiver --ei display "$ocr_display" --es do ocr >/dev/null
    sleep 2
  done
else
  sleep "$seconds"
fi
adb shell top -b -n 1 -m 6 | sed -n '1,13p'
adb shell dumpsys gfxinfo "$app" | grep -E "Total frames rendered|Janky frames|90th percentile|95th percentile|99th percentile" | head -5
echo "whole-screen OCR runs: $(adb logcat -d -s PhoneAgent | grep -c "OCR 整屏" || true)"
