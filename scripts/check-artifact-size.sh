#!/usr/bin/env bash
#
# Measures every published artifact and holds its size to a committed baseline.
#
# Nothing else in the build reports artifact sizes, so a dependency that doubles the Android
# archive would land without a word. This script publishes each published module to Maven Local
# with `./kotlin publish mavenLocal`, prints a table of module, target and bytes, and compares each
# line with the baseline file, artifact-size-baseline.txt. It measures the files that ship:
#
#   jvm         the JVM jar
#   android     the Android archive (AAR), or the one archive of a module with no platform
#               folder, such as kiteplayer-libass-android-natives
#   macosArm64  the macOS klib
#   iosArm64    the klib for iOS devices
#
# A cinterop klib is a published file of its own, and it embeds the static libraries that the
# module links. Each one gets its own line, such as macosArm64-cinterop-libass.
#
# A published module is one that scripts/published-modules.sh lists. That rule drops the samples
# and every unpublished module. A module with no such target has no line for it: kiteplayer-rt,
# for example, has no JVM half.
#
# KITEPLAYER_POM_REPOSITORY names another local Maven repository than ~/.m2/repository.
#
# The rules:
#
#   - A line more than ten percent above its baseline fails and names itself.
#   - A line with no baseline fails. So does a baseline line that the build no longer makes.
#     The baseline then always lists exactly what ships.
#   - A line more than ten percent below its baseline passes with a note. Lower the baseline.
#
# The baseline moves the way every ratchet here moves: in the same commit as the change that moved
# it, with the old and new numbers in the commit message. --update prints both before it writes.
#
#   ./scripts/check-artifact-size.sh            # build, measure, compare: must PASS
#   ./scripts/check-artifact-size.sh --update   # build, measure, rewrite the baseline
#   ./scripts/check-artifact-size.sh --falsify  # plant one baseline error at a time: each must FAIL
#
# When GITHUB_STEP_SUMMARY is set, the table also goes into the summary of the CI run.
#
# Sizes depend on what the build embeds. The libass JVM jar, Android archive and cinterop klibs
# carry the text layout chain, and a chain from the sibling checkout can differ from the release
# that CI downloads. Uncommitted work changes the numbers too. Measure a baseline from a clean copy
# of the tree when either could differ.

set -euo pipefail

MODE="${1:-check}"
case "$MODE" in check|--update|--falsify) ;; *) echo "usage: $0 [--update|--falsify]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BASELINE="artifact-size-baseline.txt"
LIMIT_PERCENT=10

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

VERSION=$(sed -n 's/^    version:[[:space:]]*//p' library.module-template.yaml | tr -d '\r')
[ -n "$VERSION" ] || { echo "library.module-template.yaml has no publishing version" >&2; exit 2; }
REPOSITORY="${KITEPLAYER_POM_REPOSITORY:-$HOME/.m2/repository}/io/github/yuroyami"

# The measured targets: the artifact folder suffix, then the table name.
TARGETS="jvm:jvm android:android macosarm64:macosArm64 iosarm64:iosArm64"

# Publishes every published module and writes "<module> <target> <bytes>" lines to $1.
measure() {
    local out="$1" module target_pair suffix target folder file name interop
    local modules
    modules=$(scripts/published-modules.sh)
    [ -n "$modules" ] || { echo "scripts/published-modules.sh lists no module" >&2; exit 2; }

    # shellcheck source=test-signing-key.sh
    . scripts/test-signing-key.sh
    : > "$out"
    local flags=()
    for module in $modules; do flags+=(-m "$module"); done
    echo "== Publishing the published modules to Maven Local"
    KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin publish mavenLocal --non-transitive "${flags[@]}"
    for module in $modules; do
        for target_pair in $TARGETS; do
            suffix="${target_pair%%:*}"
            target="${target_pair#*:}"
            folder="$REPOSITORY/$module-$suffix/$VERSION"
            [ -d "$folder" ] || continue
            for file in "$folder/$module-$suffix-$VERSION".jar "$folder/$module-$suffix-$VERSION".aar \
                "$folder/$module-$suffix-$VERSION".klib; do
                [ -f "$file" ] && echo "$module $target $(( $(wc -c < "$file") ))" >> "$out"
            done
            for file in "$folder/$module-$suffix-$VERSION"-cinterop-*.klib; do
                [ -f "$file" ] || continue
                name=$(basename "$file" .klib)
                interop="${name#"$module-$suffix-$VERSION-cinterop-"}"
                echo "$module $target-cinterop-$interop $(( $(wc -c < "$file") ))" >> "$out"
            done
        done
        # A module with no platform folder ships one archive under its own name, such as the
        # Android JNI libraries of kiteplayer-libass-android-natives.
        if ! grep -q "^$module " "$out"; then
            for file in "$REPOSITORY/$module/$VERSION/$module-$VERSION".jar "$REPOSITORY/$module/$VERSION/$module-$VERSION".aar; do
                [ -f "$file" ] && echo "$module android $(( $(wc -c < "$file") ))" >> "$out"
            done
        fi
    done
    [ -s "$out" ] || { echo "No artifact found under $REPOSITORY for version $VERSION" >&2; exit 2; }
}

# Compares <measured> with <baseline>, prints the table, and exits non-zero on any failure.
# A third argument names a file that receives the same table in Markdown.
compare() {
    awk -v limit="$LIMIT_PERCENT" -v markdown="${3:-}" '
        function verdict_line(module, target, bytes, base, change, verdict) {
            printf "%-26s %-28s %9s %9s %7s  %s\n", module, target, bytes, base, change, verdict
            if (markdown != "") {
                printf "| %s | %s | %s | %s | %s | %s |\n", module, target, bytes, base, change, verdict >> markdown
            }
        }
        FILENAME == ARGV[1] {
            if ($0 ~ /^#/ || NF == 0) next
            base[$1 " " $2] = $3
            base_order[++base_count] = $1 " " $2
            next
        }
        {
            measured[$1 " " $2] = $3
            measured_order[++measured_count] = $1 " " $2
        }
        END {
            printf "%-26s %-28s %9s %9s %7s\n", "module", "target", "bytes", "baseline", "change"
            if (markdown != "") {
                print "| Module | Target | Bytes | Baseline | Change | Result |" >> markdown
                print "|---|---|---:|---:|---:|---|" >> markdown
            }
            failures = 0
            for (i = 1; i <= measured_count; i++) {
                key = measured_order[i]
                split(key, part, " ")
                bytes = measured[key]
                if (!(key in base)) {
                    verdict_line(part[1], part[2], bytes, "-", "-", "FAIL: no baseline line")
                    failures++
                    continue
                }
                change = sprintf("%+.1f%%", (bytes - base[key]) * 100 / base[key])
                if (bytes * 100 > base[key] * (100 + limit)) {
                    verdict_line(part[1], part[2], bytes, base[key], change, "FAIL: more than " limit "% above the baseline")
                    failures++
                } else if (bytes * 100 < base[key] * (100 - limit)) {
                    verdict_line(part[1], part[2], bytes, base[key], change, "note: lower the baseline")
                } else {
                    verdict_line(part[1], part[2], bytes, base[key], change, "")
                }
            }
            for (i = 1; i <= base_count; i++) {
                key = base_order[i]
                if (key in measured) continue
                split(key, part, " ")
                verdict_line(part[1], part[2], "-", base[key], "-", "FAIL: the build no longer makes this artifact")
                failures++
            }
            exit (failures > 0 ? 1 : 0)
        }
    ' "$2" "$1"
}

write_baseline() { # <measured> <baseline>
    {
        echo "# Published artifact sizes in bytes, held by scripts/check-artifact-size.sh."
        echo "#"
        echo "# A line more than $LIMIT_PERCENT percent above this baseline fails CI. Rewrite the file with"
        echo "# ./scripts/check-artifact-size.sh --update in the commit that moved the size, and put the"
        echo "# old and new numbers in the commit message."
        echo "#"
        echo "# module target bytes"
        cat "$1"
    } > "$2"
}

measure "$scratch/measured.txt"
echo

case "$MODE" in
    check)
        [ -f "$BASELINE" ] || { echo "$BASELINE does not exist. Run $0 --update." >&2; exit 1; }
        markdown=""
        if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
            markdown="$GITHUB_STEP_SUMMARY"
            printf '### Artifact sizes\n\n' >> "$markdown"
        fi
        if compare "$scratch/measured.txt" "$BASELINE" "$markdown"; then
            echo
            echo "check-artifact-size.sh: PASS"
            exit 0
        fi
        echo
        echo "check-artifact-size.sh: FAIL. If the growth is intended, run $0 --update and commit"
        echo "$BASELINE with the change, with the old and new numbers in the commit message."
        exit 1
        ;;
    --update)
        if [ -f "$BASELINE" ]; then
            echo "== The old baseline against the new measurement"
            compare "$scratch/measured.txt" "$BASELINE" || true
            echo
        fi
        write_baseline "$scratch/measured.txt" "$BASELINE"
        echo "check-artifact-size.sh: wrote $BASELINE"
        ;;
    --falsify)
        echo "== FALSIFICATION arm: each plant must fail and name its line"
        echo
        fresh="$scratch/fresh.txt"
        write_baseline "$scratch/measured.txt" "$fresh"
        caught=0
        total=0

        # plant <description> <baseline file> <module> <target>: the table line of that module and
        # target must say FAIL.
        plant() {
            total=$((total + 1))
            if compare "$scratch/measured.txt" "$2" > "$scratch/plant.log"; then
                echo "NOT CAUGHT: $1"
            elif ! awk -v module="$3" -v target="$4" '$1 == module && $2 == target && /FAIL/ { found = 1 } END { exit !found }' \
                "$scratch/plant.log"; then
                echo "NOT NAMED:  $1"
                cat "$scratch/plant.log"
            else
                echo "caught:     $1"
                caught=$((caught + 1))
            fi
        }

        if compare "$scratch/measured.txt" "$fresh" > "$scratch/plant.log"; then
            echo "passes:     a baseline written from this measurement"
        else
            echo "FAILS:      a baseline written from this measurement"
            cat "$scratch/plant.log"
            exit 1
        fi

        # A baseline edited down by twenty percent, one line at a time.
        while read -r module target _; do
            awk -v key="$module $target" '($1 " " $2) == key { $3 = int($3 * 0.8) } { print }' "$fresh" > "$scratch/cut.txt"
            plant "$module $target, its baseline cut by 20%" "$scratch/cut.txt" "$module" "$target"
        done < "$scratch/measured.txt"

        read -r first_module first_target _ < "$scratch/measured.txt"
        awk -v key="$first_module $first_target" '($1 " " $2) != key { print }' "$fresh" > "$scratch/missing.txt"
        plant "$first_module $first_target, its baseline line deleted" "$scratch/missing.txt" "$first_module" "$first_target"

        { cat "$fresh"; echo "kiteplayer-retired jvm 1000"; } > "$scratch/stale.txt"
        plant "a baseline line for an artifact the build does not make" "$scratch/stale.txt" kiteplayer-retired jvm

        echo
        if [ "$caught" -eq "$total" ]; then
            echo "check-artifact-size.sh: FALSIFICATION PASS, $caught of $total plants caught"
            exit 0
        fi
        echo "check-artifact-size.sh: FALSIFICATION FAIL, only $caught of $total caught"
        exit 1
        ;;
esac
