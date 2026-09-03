#!/usr/bin/env bash
# Release notes from the commits since a tag.
#
#   scripts/release-notes.sh [since]      since defaults to the newest v* tag
#
# Commit subjects in this repository are "prefix: one sentence". The script groups them by that
# prefix, drops the bookkeeping prefixes (plan, gotchas, design), and prints Markdown with one
# third-level heading per group and one bullet per subject, ready to paste under a version
# heading in CHANGELOG.md or into a release page. A subject with no prefix lands under "other".
set -euo pipefail
cd "$(dirname "$0")/.."

since="${1:-$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)}"
if [ -z "$since" ]; then
  echo "release-notes.sh: no v* tag to count from; pass a tag or commit" >&2
  exit 2
fi
if ! git rev-parse -q --verify "${since}^{commit}" >/dev/null; then
  echo "release-notes.sh: no such tag or commit: $since" >&2
  exit 2
fi

git log --reverse --format='%s' "${since}..HEAD" | awk '
  BEGIN { skip["plan"] = 1; skip["gotchas"] = 1; skip["design"] = 1 }
  {
    subject = $0
    prefix = "other"
    if (match(subject, /^[a-z][a-z0-9-]*: /)) {
      prefix = substr(subject, 1, RLENGTH - 2)
      subject = substr(subject, RLENGTH + 1)
    }
    if (prefix in skip) next
    if (!(prefix in seen)) { seen[prefix] = 1; order[++groups] = prefix }
    lines[prefix] = lines[prefix] "- " subject "\n"
  }
  END {
    for (i = 1; i <= groups; i++) {
      if (i > 1) printf "\n"
      printf "### %s\n\n%s", order[i], lines[order[i]]
    }
  }'
