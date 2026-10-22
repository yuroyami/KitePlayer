# shellcheck shell=bash
# Sets the variables that the native and iOS simulator tests read. Source it from the repository
# root before `./kotlin test`:
#
#   . scripts/test-env.sh
#
# The JVM tests need nothing: their module.yaml passes KITEPLAYER_TESTMEDIA.

root="$(pwd -P)"
if [ ! -f "$root/project.yaml" ]; then
    echo "test-env.sh: source it from the KitePlayer repository root" >&2
    return 1 2>/dev/null || exit 1
fi

# The clips that scripts/testmedia.sh generates.
export KITEPLAYER_TESTMEDIA="$root/testmedia"
# `simctl spawn` passes only SIMCTL_CHILD_ variables to a simulator test.
export SIMCTL_CHILD_KITEPLAYER_TESTMEDIA="$root/testmedia"
# The backend contract transcript of the macOS FFmpeg tests.
export S1C_TRANSCRIPT_PATH="$root/build/s1c-transcripts/macosArm64.txt"
mkdir -p "$root/build/s1c-transcripts" "$root/kiteplayer-ffmpeg/build/reports/conformance"
