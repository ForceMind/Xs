#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
git submodule sync -- third_party/piko
git submodule update --init --recursive -- third_party/piko
EXPECTED="a0ed9a5ae782fe66e02294e5a49d235e2f139b24"
ACTUAL="$(git -C third_party/piko rev-parse HEAD)"
if [[ "$ACTUAL" != "$EXPECTED" ]]; then
  echo "Upstream revision mismatch: $ACTUAL" >&2
  exit 1
fi
echo "Verified Piko at $ACTUAL; preserve its LICENSE and NOTICE on reuse."
