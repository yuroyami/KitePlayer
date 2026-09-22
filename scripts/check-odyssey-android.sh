#!/usr/bin/env bash
# Compile the actual Odyssey programs and render twelve composed districts on one physical Android device.
set -euo pipefail
cd "$(dirname "$0")/.."
serial="${1:?Usage: scripts/check-odyssey-android.sh DEVICE_SERIAL [WIDTH HEIGHT FRAMES]}"
width="${2:-320}"
height="${3:-200}"
frames="${4:-0}"
[[ "$width" =~ ^[0-9]+$ && "$height" =~ ^[0-9]+$ && "$frames" =~ ^[0-9]+$ ]] || exit 2
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$sdk" ]]; then
    sdk="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
[[ -d "$sdk" ]] || { echo "Android SDK not found" >&2; exit 1; }
build_tools="$(python3 - "$sdk" <<'PY'
import pathlib, sys
paths = [p for p in (pathlib.Path(sys.argv[1]) / 'build-tools').iterdir() if (p / 'd8').is_file()]
print(max(paths, key=lambda p: tuple(int(v) for v in p.name.split('.') if v.isdigit())))
PY
)"
android_jar="$(python3 - "$sdk" <<'PY'
import pathlib, sys
paths = list((pathlib.Path(sys.argv[1]) / 'platforms').glob('android-*/android.jar'))
print(max(paths, key=lambda p: tuple(int(v) for v in p.parent.name.split('-')[-1].split('.'))))
PY
)"
work="$(mktemp -d "${TMPDIR:-/tmp}/odyssey-android.XXXXXX")"
trap 'rm -rf "$work"' EXIT
# --rerun makes the export run every time, so no stale program or table can be pushed.
./gradlew --no-parallel --max-workers=2 :kiteplayer-audioviz:jvmTest --rerun \
    --tests '*OdysseyRecipeTest' --tests '*OdysseyComposerTest' --tests '*OdysseyRecipeFieldTest' \
    --tests '*OdysseyWorldTest' --tests '*OdysseyMotionTest' --tests '*OdysseyRenderTest' --tests '*ShaderCompileTest' --tests '*ShaderDataTest'
javac --release 8 -classpath "$android_jar" -d "$work" scripts/probes/OdysseyAndroidProbe.java
"$build_tools/d8" --output "$work" "$work/OdysseyAndroidProbe.class"
adb="$sdk/platform-tools/adb"
remote=/data/local/tmp/kiteplayer-odyssey-probe
"$adb" -s "$serial" shell mkdir -p "$remote"
"$adb" -s "$serial" push "$work/classes.dex" "$remote/probe.dex"
"$adb" -s "$serial" push kiteplayer-audioviz/build/odyssey-android-probe/*.sksl kiteplayer-audioviz/build/odyssey-android-probe/*.txt "$remote/"
"$adb" -s "$serial" shell "CLASSPATH=$remote/probe.dex app_process /system/bin OdysseyAndroidProbe $remote $width $height $frames"
for scene in $(seq -f 'district-%02g' 1 12); do
    "$adb" -s "$serial" pull "$remote/$scene.png" "kiteplayer-audioviz/build/odyssey-android-probe/$scene.png"
    for state in quiet active; do
        "$adb" -s "$serial" pull "$remote/$scene-$state.png" "kiteplayer-audioviz/build/odyssey-android-probe/$scene-$state.png"
    done
done
