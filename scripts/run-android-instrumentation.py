#!/usr/bin/env python3
"""Preserve original Gradle outcome and pull synthetic UI diagnostics before AVD teardown."""
import json
from pathlib import Path
import subprocess


def main():
    output=Path("dev-harness/build/compatibility")
    output.mkdir(parents=True,exist_ok=True)
    result=None
    try:
        result=subprocess.run(["gradle","--no-daemon","--no-parallel","--continue",":dev-harness:connectedDebugAndroidTest",":app-android:connectedDebugAndroidTest"],timeout=25*60)
        return result.returncode
    finally:
        # reactivecircus executes script lines separately and destroys the emulator when
        # a line fails. Collect here, rather than relying on a later step or shell trap.
        try:
            # UTP also removes test/target APKs (and their externalFilesDir) before
            # returning. The emulator-only fixture exports its fixed generated files
            # to shell-owned tmp while each failed test's window still exists.
            pulled=subprocess.run(["adb","pull","/data/local/tmp/xs-governance-ui",str(output/"governance-ui")],capture_output=True,text=True,timeout=30)
            (output/"ui-diagnostics-pull.txt").write_text(pulled.stdout+pulled.stderr,encoding="utf-8")
            (output/"ui-diagnostics-status.json").write_text(json.dumps({"scope":"Optional synthetic failure screenshots/AX only; availability is not a test outcome","available":pulled.returncode==0,"gradle_exit_code":None if result is None else result.returncode},indent=2)+"\n",encoding="utf-8")
        except (OSError,subprocess.TimeoutExpired) as unavailable:
            try:
                (output/"ui-diagnostics-status.json").write_text(json.dumps({"scope":"Optional synthetic diagnostics unavailable; original test outcome is preserved","available":False,"diagnostic_failure_type":type(unavailable).__name__,"gradle_exit_code":None if result is None else result.returncode},indent=2)+"\n",encoding="utf-8")
            except OSError:
                pass  # Optional diagnostic I/O cannot replace the actual test outcome.


if __name__=="__main__":raise SystemExit(main())
