#!/usr/bin/env bash
#
# Keeps dependency resolution predictable: the same commit builds the same bytes.
#
# Each rule is a real failure mode:
#
#   1. A dynamic version in a version catalog (`1.+`, `latest.release`, a range). Two checkouts
#      of the same commit can then build different bytes.
#   2. A dependency coordinate written inline in a module file. It bypasses libs.versions.toml,
#      so the one file that lists every version no longer does. Plugin task classpaths in
#      build-plugins/*/plugin.yaml are tool downloads and stay inline, but they must be exact.
#   3. A `repositories:` list outside the files that own resolution. A module that adds its own
#      can pull the same coordinate from somewhere else.
#   4. mavenLocal as a source. It is a publish target only (`resolve: false`). To build against a
#      local build of a dependency, add mavenLocal to library.module-template.yaml for that
#      session only. This check fails while it is there, so it cannot reach a commit.
#   5. A plain-http URL. Anyone on the path can serve their own bytes.
#   6. A wrapper that does not pin its download by SHA-256: `kotlin`, `kotlin.bat`, and the Gradle
#      wrapper of the verification consumer projects.
#
#   ./scripts/check-dependency-hygiene.sh             # must PASS
#   ./scripts/check-dependency-hygiene.sh --falsify   # plants each violation in turn, must FAIL

set -euo pipefail

MODE="${1:-real}"
case "$MODE" in real|--falsify) ;; *) echo "usage: $0 [--falsify]" >&2; exit 2 ;; esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Every file that takes part in dependency resolution, without generated or untracked trees.
# All rules read this one list, so no rule covers fewer files than another.
build_files() {
    find . \
        \( -name build -o -name .git -o -name node_modules -o -name native-libs -o -name vendor \
           -o -name testmedia -o -name .claude -o -name .gradle \) -prune -o \
        \( -name module.yaml -o -name plugin.yaml -o -name project.yaml -o -name '*.module-template.yaml' \
           -o -name libs.versions.toml -o -name '*.gradle.kts' -o -name gradle-wrapper.properties \) -print
}
yaml_files() { build_files | grep -E '\.yaml$'; }

failures=0
note() { printf '  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1"; failures=$((failures + 1)); }
pass() { printf 'ok    %s\n' "$1"; }

echo "check-dependency-hygiene.sh: $ROOT"
echo

# ---- 1. No dynamic versions ----
# The catalogs, plus the inline tool coordinates of plugin.yaml files.
dynamic=$(build_files | grep 'libs.versions.toml$' | xargs grep -nE '=\s*"[^"]*(\+|latest\.)[^"]*"|=\s*"[]\[][^"]*[]\[)]"' /dev/null || true)
dynamic+=$(build_files | grep 'plugin.yaml$' | xargs grep -nE '^\s*-\s*"?[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[^0-9\s]|^\s*-\s*"?[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[^"\s]*(\+|latest\.)' /dev/null || true)
if [ -n "$dynamic" ]; then
    fail "a version is dynamic"
    printf '%s\n' "$dynamic" | while IFS= read -r line; do note "$line"; done
else
    pass "every version is exact"
fi

# ---- 2. No dependency coordinate written inline ----
# group:artifact:version as a list item of a module file or template, or inside a Gradle
# dependency call. The version must start with a digit, so `$libs.x` and `//module` cannot match.
inline=$(yaml_files | grep -v 'plugin.yaml$' | xargs grep -nE '^\s*-\s*"?[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[0-9]' /dev/null || true)
inline+=$(build_files | grep '\.gradle\.kts$' | xargs grep -nE \
    '(implementation|api|compileOnly|runtimeOnly|testImplementation|classpath)\("[a-zA-Z0-9._-]+:[a-zA-Z0-9._-]+:[0-9]' /dev/null || true)
if [ -n "$inline" ]; then
    fail "a dependency is declared inline instead of through libs.versions.toml"
    printf '%s\n' "$inline" | while IFS= read -r line; do note "$line"; done
else
    pass "every dependency goes through a version catalog"
fi

# ---- 3. Repositories belong to the named files ----
# The library template and the one published module that does not apply it. The two verification
# consumers are separate Gradle builds with their own settings.
repos=$(yaml_files | xargs grep -ln '^repositories:' /dev/null | \
    grep -vE '^\./(library\.module-template\.yaml|kiteplayer-libass-android-natives/module\.yaml)$' || true)
repos+=$(build_files | grep '\.gradle\.kts$' | xargs grep -ln '^[[:space:]]*repositories[[:space:]]*{' /dev/null | \
    grep -vE '^\./verification/((network|central)-consumer|device-tests)/settings\.gradle\.kts$' || true)
if [ -n "$repos" ]; then
    fail "a file declares repositories that it does not own"
    printf '%s\n' "$repos" | while IFS= read -r f; do note "$f"; done
else
    pass "resolution is owned by the named files"
fi

# ---- 4. mavenLocal is a publish target only ----
# Each mavenLocal entry of a `repositories:` list must say `resolve: false` before the next entry.
# Gradle files must not call mavenLocal() at all.
unguarded=$(yaml_files | xargs awk '
    function close_entry() { if (open && !guarded) print file ":" line; open = 0 }
    FNR == 1 { close_entry(); file = FILENAME }
    /^[[:space:]]*-[[:space:]]*(url:[[:space:]]*)?"?mavenLocal"?[[:space:]]*$/ { close_entry(); open = 1; guarded = 0; line = FNR; next }
    open && /^[[:space:]]*-/ { close_entry() }
    open && /^[^[:space:]#]/ { close_entry() }
    open && /^[[:space:]]+resolve:[[:space:]]*false/ { guarded = 1 }
    END { close_entry() }
' /dev/null || true)
unguarded+=$(build_files | grep '\.gradle\.kts$' | xargs grep -n 'mavenLocal()' /dev/null || true)
if [ -n "$unguarded" ]; then
    fail "mavenLocal is a dependency source; it is consulted first and wins silently"
    printf '%s\n' "$unguarded" | while IFS= read -r line; do note "$line"; done
else
    pass "mavenLocal is a publish target only"
fi

# ---- 5. Nothing resolves over plain http ----
plainhttp=$( { build_files; echo ./kotlin; echo ./kotlin.bat; } | xargs grep -nE 'http://[a-zA-Z0-9]' /dev/null | grep -v 'http://localhost' || true)
if [ -n "$plainhttp" ]; then
    fail "something resolves over plain http"
    printf '%s\n' "$plainhttp" | while IFS= read -r line; do note "$line"; done
else
    pass "every repository and download URL is https"
fi

# ---- 6. Every wrapper pins its download by checksum ----
sh_pin=$(sed -n 's/^kotlin_cli_sha256=\([0-9a-f]\{64\}\)$/\1/p' kotlin)
bat_pin=$(sed -n 's/^set kotlin_cli_sha256=\([0-9a-f]\{64\}\)\r\{0,1\}$/\1/p' kotlin.bat)
sh_version=$(sed -n 's/^kotlin_cli_version=\([0-9][A-Za-z0-9._+-]*\)$/\1/p' kotlin)
bat_version=$(sed -n 's/^set kotlin_cli_version=\([0-9][A-Za-z0-9._+-]*\)\r\{0,1\}$/\1/p' kotlin.bat)
if [ -n "$sh_pin" ] && [ "$sh_pin" = "$bat_pin" ] && [ -n "$sh_version" ] && [ "$sh_version" = "$bat_version" ]; then
    pass "kotlin and kotlin.bat pin the same CLI $sh_version by sha256"
else
    fail "kotlin and kotlin.bat do not pin the same CLI version and sha256"
fi
for props in $(build_files | grep "gradle-wrapper.properties"); do
    if grep -q '^distributionSha256Sum=[0-9a-f]\{64\}$' "$props"; then
        pass "$props pins its distribution by sha256"
    else
        fail "$props does not pin distributionSha256Sum"
    fi
done

echo
if [ "$MODE" = "--falsify" ]; then
    echo "== FALSIFICATION arm: each rule is planted in turn and must be caught"
    echo
    scratch=$(mktemp -d)
    trap 'rm -rf "$scratch"' EXIT

    # Every file a plant touches, backed up once and restored after each plant, so each plant
    # tests exactly one violation.
    CATALOG="libs.versions.toml"
    TEMPLATE="library.module-template.yaml"
    MODULE="kiteplayer-core/module.yaml"
    PLUGIN="build-plugins/dokka/plugin.yaml"
    WRAPPER="verification/gradle/wrapper/gradle-wrapper.properties"
    TOUCHED="$CATALOG $TEMPLATE $MODULE $PLUGIN $WRAPPER kotlin"
    for f in $TOUCHED; do cp -p "$f" "$scratch/$(echo "$f" | tr / _)"; done
    restore() { for f in $TOUCHED; do cp -p "$scratch/$(echo "$f" | tr / _)" "$f"; done; }

    caught=0
    total=0
    plant() {
        # plant <description> <command that introduces one violation>
        total=$((total + 1))
        eval "$2"
        if "$0" > /dev/null 2>&1; then
            echo "NOT CAUGHT: $1"
        else
            echo "caught:     $1"
            caught=$((caught + 1))
        fi
        restore
    }

    plant "a dynamic version in the catalog" \
        "printf '\nfalsify-dynamic = \"1.+\"\n' >> '$CATALOG'"
    plant "a dynamic tool version in a plugin.yaml" \
        "sed 's/dokka-cli:[^[:space:]]*/dokka-cli:latest.release/' '$PLUGIN' > '$scratch/p' && cat '$scratch/p' > '$PLUGIN'"
    plant "a dependency coordinate written inline in a module" \
        "printf '\ntest-dependencies:\n  - com.example:falsify:1.0.0\n' >> '$MODULE'"
    plant "a module declaring its own repositories" \
        "printf '\nrepositories:\n  - https://repo.example.com/maven\n' >> '$MODULE'"
    plant "mavenLocal as a dependency source" \
        "sed 's/resolve: false/resolve: true/' '$TEMPLATE' > '$scratch/t' && cat '$scratch/t' > '$TEMPLATE'"
    plant "a plain-http URL in a module" \
        "printf '\n# falsify http://insecure.example.com\n' >> '$MODULE'"
    plant "a kotlin wrapper that stopped pinning its CLI" \
        "grep -v '^kotlin_cli_sha256=' kotlin > '$scratch/k' && cat '$scratch/k' > kotlin"
    plant "a Gradle wrapper that stopped pinning its distribution" \
        "grep -v '^distributionSha256Sum=' '$WRAPPER' > '$scratch/w' && cp '$scratch/w' '$WRAPPER'"

    restore
    echo
    if [ "$caught" -eq "$total" ]; then
        echo "check-dependency-hygiene.sh: FALSIFICATION PASS, $caught of $total planted violations caught"
        exit 0
    fi
    echo "check-dependency-hygiene.sh: FALSIFICATION FAIL, only $caught of $total caught"
    exit 1
fi

if [ "$failures" -eq 0 ]; then
    echo "check-dependency-hygiene.sh: PASS"
    exit 0
fi
echo "check-dependency-hygiene.sh: FAIL, $failures rule(s) broken"
exit 1
