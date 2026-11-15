#!/usr/bin/env bash
#
# Publishes modules to Maven Local. Their Kotlin/Native code compiles one platform at a time first.
#
# The Kotlin Toolchain (0.13.0) runs each Kotlin/Native compile in a JVM of its own that may use
# 3 GB, and it sets no limit on how many run at once. One publish of every module started about 20
# of them on a GitHub macOS runner with 7 GB of memory, and the runner swapped until the job timed
# out (#975). One `./kotlin task` call for each platform runs 6 of them at most. A compile that has
# already written its klib is left to the publish, which checks whether it is up to date.
#
#   scripts/publish-local.sh                                     # every published module
#   scripts/publish-local.sh kiteplayer-core kiteplayer-network  # these modules only
#   scripts/publish-local.sh --dry-run                           # print the calls, run nothing
#
# The signing key comes from the caller, for example scripts/test-signing-key.sh.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export KOTLIN_CLI_NO_WELCOME_BANNER=1
dry_run=false
if [ "${1:-}" = "--dry-run" ]; then dry_run=true; shift; fi
# shellcheck disable=SC2046 # one module name per word
[ $# -gt 0 ] || set -- $(scripts/published-modules.sh)

kotlin() {
    if [ "$dry_run" = true ]; then echo "./kotlin $*"; else ./kotlin "$@"; fi
}

tasks="$(./kotlin show tasks)"
# Every compile<Platform>Release task of these modules names a Kotlin/Native platform, except
# Android's and the test compiles.
platforms="$(for module in "$@"; do
    sed -n "s/^task :$module:compile\([A-Za-z0-9]*\)Release .*/\1/p" <<< "$tasks"
done | sort -u)"
for platform in $platforms; do
    case "$platform" in Android|*Test) continue ;; esac
    compiles=()
    for module in "$@"; do
        task=":$module:compile${platform}Release"
        # A here-string, not a pipe: grep -q stops reading early, and pipefail reads that as an error.
        grep -q "^task $task " <<< "$tasks" || continue
        [ -f "build/tasks/_${module}_compile${platform}Release/$module.klib" ] && continue
        compiles+=("$task")
    done
    [ ${#compiles[@]} -eq 0 ] || kotlin task "${compiles[@]}"
done

flags=()
for module in "$@"; do flags+=(-m "$module"); done
kotlin publish mavenLocal --non-transitive "${flags[@]}"
