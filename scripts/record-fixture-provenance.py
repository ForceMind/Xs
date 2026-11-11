#!/usr/bin/env python3
"""Record job-local synthetic fixture artifacts; never treat availability as a test pass."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from datetime import datetime, timezone


APKS = (
    ("app-android", "application", "app-android/build/outputs/apk/debug/app-android-debug.apk"),
    ("app-android", "instrumentation", "app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk"),
    ("dev-harness", "application", "dev-harness/build/outputs/apk/debug/dev-harness-debug.apk"),
    ("dev-harness", "instrumentation", "dev-harness/build/outputs/apk/androidTest/debug/dev-harness-debug-androidTest.apk"),
    ("share-receiver-fixture", "independent-uid-receiver", "share-receiver-fixture/build/outputs/apk/debug/share-receiver-fixture-debug.apk"),
)


def command(args, cwd, timeout=30):
    try:
        result = subprocess.run(args, cwd=cwd, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        return {"exit_code": result.returncode, "stdout": result.stdout.strip(),
                "stderr": result.stderr.strip()}
    except (OSError, subprocess.TimeoutExpired) as failure:
        return {"exit_code": None, "stdout": "", "stderr": str(failure)}


def git_value(root, *args):
    result = command(["git", *args], root)
    return result["stdout"] if result["exit_code"] == 0 else None


def signer_path(explicit):
    if explicit:
        return str(Path(explicit).resolve())
    candidates = []
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(name)
        if sdk:
            candidates.extend(Path(sdk).glob("build-tools/*/apksigner"))
    if candidates:
        # Numeric ordering avoids choosing 9.x ahead of 35.x.
        return str(max(candidates, key=lambda path: tuple(
            int(part) for part in re.findall(r"\d+", path.parent.name))))
    return shutil.which("apksigner")


def record_apk(root, module, role, relative_path, signer):
    path = root / relative_path
    record = {"module": module, "role": role, "path": relative_path,
              "availability": "unavailable", "bytes": None, "sha256": None,
              "signature_verification": {"status": "unavailable", "exit_code": None,
                                         "stdout": "", "stderr": "APK not built in this job"}}
    if not path.is_file():
        return record
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    record.update(availability="present", bytes=path.stat().st_size, sha256=digest.hexdigest())
    if not signer:
        record["signature_verification"]["stderr"] = "apksigner unavailable"
    else:
        verified = command([signer, "verify", "--print-certs", str(path)], root)
        verified["status"] = ("passed" if verified["exit_code"] == 0 else
                              "unavailable" if verified["exit_code"] is None else "failed")
        record["signature_verification"] = verified
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path,
                        default=Path("app-android/build/compatibility/fixture-artifacts.json"))
    parser.add_argument("--apksigner", help="Optional public certificate verification executable")
    args = parser.parse_args()
    root = args.root.resolve()
    status = git_value(root, "status", "--porcelain=v1", "--untracked-files=normal")
    signer = signer_path(args.apksigner)
    artifacts = [record_apk(root, *apk, signer) for apk in APKS]
    report = {
        "schema_version": 1,
        "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
        "evidence_scope": "Job-local APK availability and public signing certificates only; "
                          "test execution/outcomes come from this job's instrumentation reports. "
                          "This is not real-X, physical-device or recipient-app acceptance.",
        "source": {"head_commit": git_value(root, "rev-parse", "HEAD"),
                   "head_tree": git_value(root, "rev-parse", "HEAD^{tree}"),
                   "working_tree_clean": None if status is None else status == "",
                   "working_tree_status": None if status is None else status.splitlines()},
        "apksigner": signer,
        "artifacts": artifacts,
    }
    output = args.output if args.output.is_absolute() else root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    present = sum(item["availability"] == "present" for item in artifacts)
    failed = sum(item["signature_verification"]["status"] == "failed" for item in artifacts)
    print("Recorded {} of {} job-local APKs; {} signature verification failures: {}".format(
        present, len(artifacts), failed, output))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
