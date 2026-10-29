#!/usr/bin/env bash
# Builds the virtual-display probe into tools/probe/out/vd/vd.jar and, with --push, copies it to the phone.
# Needs the Android SDK (platform 35, build-tools 36) and the JDK from CLAUDE.md.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
jdk="${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11}"
# d8.bat reads JAVA_HOME as a Windows path.
export JAVA_HOME="$(command -v cygpath >/dev/null && cygpath -w "$jdk" || echo "$jdk")"
out="$here/../out/vd"
rm -rf "$out" && mkdir -p "$out/classes"

"$jdk/bin/javac" --release 11 -cp "$sdk/platforms/android-35/android.jar" -d "$out/classes" "$here/VirtualDisplayTool.java"
"$sdk/build-tools/36.0.0/d8.bat" --min-api 34 --lib "$sdk/platforms/android-35/android.jar" \
  --output "$out/vd.jar" $(find "$out/classes" -name '*.class')
echo "built $out/vd.jar"

if [[ "${1:-}" == "--push" ]]; then
  adb push "$out/vd.jar" /data/local/tmp/vd.jar
fi
