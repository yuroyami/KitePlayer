#!/usr/bin/env bash
# Prints the published modules, one per line. A published module enables the publication-check
# plugin in its module.yaml.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
grep -l '^  publication-check:' */module.yaml | sed 's|/module.yaml||' | sort
