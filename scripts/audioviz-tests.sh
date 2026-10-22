#!/usr/bin/env bash
# Runs the JVM tests of kiteplayer-audioviz in one of four sets.
#
#   ./scripts/audioviz-tests.sh gate               # every test except the rendering suites
#   ./scripts/audioviz-tests.sh survey             # the rendering suites, about 25 minutes
#   ./scripts/audioviz-tests.sh benchmark          # the pipeline cost at window resolution
#   ./scripts/audioviz-tests.sh desktop-benchmark  # opens a window and measures its frame cadence
#
# The rendering suites draw every drawing for seconds of two test songs and measure the pixels.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

RENDERING_SUITES=(
    DensityTest ScreenMotionTest ChangeTest DriverInjectionTest FlashCaptureTest DriverMatrixReport
    MappingQualificationTest MoodRenderTest DrawCostTest RecipeTest ContactSheetTest PostSheetTest
    RenderPerformanceTest DesktopRenderPerformanceTest
)

filters=()
case "${1:-}" in
    gate)
        # "*" replaces JUnit's default class name pattern, which would drop the *Probe and *Tool classes.
        filters+=("--include-classes=*")
        for suite in "${RENDERING_SUITES[@]}"; do filters+=("--exclude-classes=*.$suite"); done ;;
    survey)
        for suite in "${RENDERING_SUITES[@]}"; do
            [ "$suite" = DesktopRenderPerformanceTest ] || filters+=("--include-classes=*.$suite")
        done ;;
    benchmark) filters+=("--include-classes=*.RenderPerformanceTest") ;;
    desktop-benchmark) filters+=("--include-classes=*.DesktopRenderPerformanceTest") ;;
    *) echo "usage: $0 gate|survey|benchmark|desktop-benchmark" >&2; exit 2 ;;
esac

KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin test -m kiteplayer-audioviz -p jvm "${filters[@]}"
