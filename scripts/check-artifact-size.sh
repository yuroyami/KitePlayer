#!/usr/bin/env bash
#
# Measures every published artifact and holds its size to a committed baseline.
#
# Nothing else in the build reports artifact sizes, so a dependency that doubles the Android
# archive would land without a word. This script builds the published artifacts of each publishing
# module, prints a table of module, target and bytes, and compares each line with the baseline
# file, artifact-size-baseline.txt.
#
#   jvm         the JVM jar                 (task jvmJar)
#   android     the Android archive         (task bundleAndroidMainAar)
#   macosArm64  the macOS klib              (task macosArm64Klib)
#   iosArm64    the klib for iOS devices    (task iosArm64Klib)
#
# A cinterop klib is a published file of its own, and it embeds the static libraries that the
# module links. Each one gets its own line, such as macosArm64-cinterop-libass.
#
# A publishing module is one that settings.gradle.kts includes and whose build file applies the
# Maven publish plugin. That rule drops the samples and every unpublished module. A module with no
# such target has no line for it: kiteplayer-rt, for example, has no JVM half.
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
VERSION=$(sed -n 's/^VERSION=//p' gradle.properties)
[ -n "$VERSION" ] || { echo "gradle.properties has no VERSION line" >&2; exit 2; }

scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

# The targets, as "name:task". The file each task writes comes from artifact_file below.
TARGETS="jvm:jvmJar android:bundleAndroidMainAar macosArm64:macosArm64Klib iosArm64:iosArm64Klib"

artifact_file() { # <module> <target>
    case "$2" in
        jvm) echo "$1/build/libs/$1-jvm-$VERSION.jar" ;;
        android) echo "$1/build/outputs/aar/$1.aar" ;;
        macosArm64) echo "$1/build/libs/$1-macosArm64Main-$VERSION.klib" ;;
        iosArm64) echo "$1/build/libs/$1-iosArm64Main-$VERSION.klib" ;;
        *-cinterop-*) echo "$1/build/libs/$1-${2%%-cinterop-*}Cinterop-${2#*-cinterop-}Main-$VERSION.klib" ;;
    esac
}

publishing_modules() {
    sed -n 's/^include(":\([^"]*\)").*/\1/p' settings.gradle.kts | while IFS= read -r module; do
        if grep -Eq '^[[:space:]]*(alias\(libs\.plugins\.vanniktech\.publish\)|id\("com\.vanniktech\.maven\.publish"\))' \
            "$module/build.gradle.kts" 2>/dev/null; then
            echo "$module"
        fi
    done
}

# Builds every artifact and writes "<module> <target> <bytes>" lines to $1.
measure() {
    local out="$1" module target_task target task file apple interop tasks=""
    local modules
    modules=$(publishing_modules)
    [ -n "$modules" ] || { echo "no publishing module found in settings.gradle.kts" >&2; exit 2; }

    # Gradle is the authority on which module has which target, so ask it rather than reading
    # the build files.
    echo "== Listing the tasks of every module"
    ./gradlew --console=plain -q tasks --all > "$scratch/tasks.txt"

    : > "$scratch/planned.txt"
    for module in $modules; do
        for target_task in $TARGETS; do
            target="${target_task%%:*}"
            task="${target_task#*:}"
            if grep -Eq "^$module:$task( |\$)" "$scratch/tasks.txt"; then
                echo "$module $target" >> "$scratch/planned.txt"
                tasks="$tasks :$module:$task"
            fi
        done
        for apple in macosArm64 iosArm64; do
            for interop in $(grep -E "^$module:${apple}Cinterop-[A-Za-z0-9_]+Klib( |\$)" "$scratch/tasks.txt" |
                sed -E "s/^$module:${apple}Cinterop-([A-Za-z0-9_]+)Klib.*/\1/"); do
                echo "$module $apple-cinterop-$interop" >> "$scratch/planned.txt"
                tasks="$tasks :$module:${apple}Cinterop-${interop}Klib"
            done
        done
    done

    echo "== Building $(wc -l < "$scratch/planned.txt" | tr -d ' ') artifacts"
    # shellcheck disable=SC2086 # one word per task
    ./gradlew --console=plain $tasks

    : > "$out"
    while read -r module target; do
        file=$(artifact_file "$module" "$target")
        if [ ! -f "$file" ]; then
            echo "The build ran the $target task of $module, but $file does not exist." >&2
            echo "The artifact name changed; update artifact_file in $0." >&2
            exit 2
        fi
        echo "$module $target $(( $(wc -c < "$file") ))" >> "$out"
    done < "$scratch/planned.txt"
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
