#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mapfile -t SOURCES < <(find "$ROOT/src/main/java" "$ROOT/tests/java" -name "*.java" -type f | sort)
javac --release 8 -encoding UTF-8 -d "$OUT" "${SOURCES[@]}"
java -ea -cp "$OUT" dev.forcemind.xs.tests.CoreSmokeTest
java -cp "$OUT" dev.forcemind.xs.tests.SchemaDump | python3 "$ROOT/tests/sqlite_schema_test.py"
