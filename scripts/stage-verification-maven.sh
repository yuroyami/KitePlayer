#!/usr/bin/env bash
#
# Stages the artifacts of this checkout for the Gradle builds under verification/.
#
# It publishes the named modules to Maven Local with one `./kotlin publish mavenLocal`. Then it copies
# only this version of those modules into build/verification-maven, so no other build in Maven
# Local can leak into a check. The verification builds resolve KitePlayer from that folder only.
#
#   scripts/stage-verification-maven.sh kiteplayer-core kiteplayer-network
#   scripts/stage-verification-maven.sh --with-dependencies kiteplayer-ffmpeg
#
# --with-dependencies adds every module that the named modules depend on, read from their
# module.yaml files.
#
# KITEPLAYER_POM_REPOSITORY names another local Maven repository than ~/.m2/repository.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
closure=false
if [ "${1:-}" = "--with-dependencies" ]; then closure=true; shift; fi
[ $# -gt 0 ] || { echo "usage: $0 [--with-dependencies] MODULE..." >&2; exit 2; }
if [ "$closure" = true ]; then
    # Every `- //kiteplayer-<name>` line of a module.yaml, followed until no new module appears.
    # shellcheck disable=SC2046 # one module name per word
    set -- $(python3 - "$ROOT" "$@" <<'PY'
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
todo, seen = list(sys.argv[2:]), set()
while todo:
    module = todo.pop()
    if module in seen:
        continue
    seen.add(module)
    text = (root / module / "module.yaml").read_text()
    todo += re.findall(r"^\s*-\s*//(kiteplayer[a-z0-9-]*)", text, re.M)
print(" ".join(sorted(seen)))
PY
)
fi
version="$(sed -n 's/^    version:[[:space:]]*//p' "$ROOT/library.module-template.yaml" | tr -d '\r')"
[ -n "$version" ] && [[ "$version" != *$'\n'* ]] || { echo "Expected one publishing version in library.module-template.yaml" >&2; exit 2; }
group="${KITEPLAYER_POM_REPOSITORY:-$HOME/.m2/repository}/io/github/yuroyami"
repository="$ROOT/build/verification-maven"

# shellcheck source=test-signing-key.sh
. "$ROOT/scripts/test-signing-key.sh"
# One publish for every named module. --non-transitive skips the local dependencies that are not named.
module_flags=()
for module in "$@"; do module_flags+=(-m "$module"); done
(cd "$ROOT" && KOTLIN_CLI_NO_WELCOME_BANNER=1 ./kotlin publish mavenLocal --non-transitive "${module_flags[@]}")

# A folder belongs to the module with the longest matching name: kiteplayer-core-jvm to kiteplayer-core.
rm -rf "$repository"
all_modules="$(awk '/^modules:/ { inside = 1; next } /^[^[:space:]-]/ { inside = 0 } inside && /^  - / { print $2 }' "$ROOT/project.yaml")"
copied=0
for folder in "$group"/*/; do
    folder="$(basename "$folder")"
    owner=""
    for candidate in $all_modules; do
        if [[ "$folder" == "$candidate" || "$folder" == "$candidate"-* ]] && [[ ${#candidate} -gt ${#owner} ]]; then
            owner="$candidate"
        fi
    done
    [[ " $* " == *" $owner "* ]] || continue
    [[ -d "$group/$folder/$version" ]] || continue
    mkdir -p "$repository/io/github/yuroyami/$folder"
    cp -R "$group/$folder/$version" "$repository/io/github/yuroyami/$folder/"
    copied=$((copied + 1))
done
[[ "$copied" -ge $# ]] || { echo "Staged only $copied artifact folders for $# modules" >&2; exit 1; }
echo "Staged $copied artifact folders of $version into $repository"
