#!/usr/bin/env bash
set -euo pipefail
# Download Piko separately; do not modify upstream sources in the Xs repository.
UPSTREAM="https://github.com/crimera/piko.git"
RELEASE="a0ed9a5ae782fe66e02294e5a49d235e2f139b24"
DIR="${1:-third_party/piko}"
if [[ -e "$DIR" ]]; then echo "Destination already exists: $DIR" >&2; exit 1; fi
git clone --no-checkout "$UPSTREAM" "$DIR"
git -C "$DIR" checkout --detach "$RELEASE"
echo "Checked out Piko at $(git -C "$DIR" rev-parse HEAD)"
echo "Keep upstream LICENSE and NOTICE intact when copying/deriving code."
