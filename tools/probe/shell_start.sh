#!/usr/bin/env bash
# Starts background mode's shell server (app/.../shell/ShellServer.kt) on the phone over adb, from the
# installed APK, and leaves it running after adb disconnects. It attaches to Phone Agent and exits with it
# (also when the app is reinstalled). For development; users will start it through Shizuku or the app.
set -euo pipefail
export MSYS_NO_PATHCONV=1
apk="$(adb shell pm path dev.phoneagent.app | head -1 | sed 's/^package://' | tr -d '\r')"
[[ -n "$apk" ]] || { echo "Phone Agent is not installed" >&2; exit 1; }
adb shell "CLASSPATH=$apk nohup app_process / dev.phoneagent.app.shell.ShellServer > /data/local/tmp/phone-agent-shell.log 2>&1 &"
sleep 2
adb shell cat /data/local/tmp/phone-agent-shell.log
