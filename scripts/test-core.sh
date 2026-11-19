#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mapfile -t SOURCES < <(find "$ROOT/src/main/java" "$ROOT/tests/java" -name "*.java" -type f | sort)
javac --release 8 -encoding UTF-8 -d "$OUT" "${SOURCES[@]}" "$ROOT/src/android/java/dev/forcemind/xs/android/XsLibraryWrites.java" "$ROOT/src/android/java/dev/forcemind/xs/android/XsBackupRequest.java"
java -ea -cp "$OUT" dev.forcemind.xs.tests.CoreSmokeTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.ObservationGateTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.SharedPostParserTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.BrowserUploadPolicyTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.CaptureSessionTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.TimelineCaptureSessionTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.MediaDownloadPolicyTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.OwnActionSessionTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.Mp4StructureTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.LibraryBackupCodecTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.LibraryMergePlanTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.LibraryWritesTest
java -ea -cp "$OUT" dev.forcemind.xs.android.BackupRequestTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.SnapshotPolicyTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.TimelinePromotionDataTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.TimelineBufferPolicyTest
java -ea -cp "$OUT" dev.forcemind.xs.tests.EncryptedLibraryBackupTest
java -cp "$OUT" dev.forcemind.xs.tests.SchemaDump | python3 "$ROOT/tests/sqlite_schema_test.py"
java -cp "$OUT" dev.forcemind.xs.tests.TimelineSchemaDump | python3 "$ROOT/tests/timeline_schema_test.py"
python3 "$ROOT/tests/fts4_compatibility_test.py" --classpath "$OUT"

node "$ROOT/tests/observer_test.js"

node "$ROOT/tests/cleanup_test.js"

node "$ROOT/tests/observer_stress_test.js"

# Safety/control-flow checks for the disposable-emulator pressure allocator.
python3 "$ROOT/scripts/test-low-space-driver.py"
python3 "$ROOT/tests/test_process_recovery_driver.py"
