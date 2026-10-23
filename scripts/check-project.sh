#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

test -f README.md
test -f README.en.md
grep -Fq 'README.en.md' README.md
grep -Fq 'README.md' README.en.md
test -f LICENSE

if [[ -e .gitmodules ]]; then
  echo "Xs is an independent codebase: source submodules are disallowed." >&2
  exit 1
fi
if git ls-files --stage | awk '$1 == "160000" { found = 1 } END { exit !found }'; then
  echo "Xs is an independent codebase: Git-linked source trees are disallowed." >&2
  exit 1
fi

test -f src/main/java/dev/forcemind/xs/core/ArchiveSchema.java
test -f src/android/java/dev/forcemind/xs/android/XsHistoryActivity.java
echo "PASS bilingual documentation and independent source tree"
