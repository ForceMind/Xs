#!/usr/bin/env python3
"""Managed disposable CI emulator only: real /data pressure, preserving saved native fixtures.

No user phone/server is contacted. One uniquely named shell-owned allocation is always removed.
This validates the real low-space admission guard, not every possible mid-write ENOSPC case.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

OUT=Path("app-android/build/compatibility/low-space")
COMPONENT="dev.forcemind.xs.test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="dev.forcemind.xs.android.ProcessRecoveryProbe"


def run(args,name,timeout=120):
    result=subprocess.run(["adb",*args],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=timeout)
    (OUT/(name+".txt")).write_text(result.stdout+result.stderr,encoding="utf-8")
    if result.returncode:raise RuntimeError("Low-space fixture command failed: "+name)
    return result.stdout


def instrument(phase):
    text=run(["shell","am","instrument","-w","-e","class",TEST_CLASS,"-e","xs_phase",phase,COMPONENT],phase)
    if not re.search(r"OK \(1 test\)",text) or "FAILURES" in text or "INSTRUMENTATION_FAILED" in text:raise RuntimeError("Low-space instrumentation did not pass: "+phase)
    return text


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    report={"status":"FAIL","scope":"Real emulator /data space below image-admission requirement; saved fixture bytes and local search preserved. Not mid-stream ENOSPC, physical flash, or user-device evidence."}
    allocation="/data/local/tmp/xs-disk-pressure-fixture-"+uuid.uuid4().hex
    allocation_attempted=False
    try:
        if os.environ.get("GITHUB_ACTIONS")!="true" or not run(["get-serialno"],"emulator-serial").strip().startswith("emulator-") or run(["shell","getprop","ro.kernel.qemu"],"emulator-qemu").strip()!="1":raise RuntimeError("Disk pressure is restricted to this disposable GitHub CI emulator")
        seeded=instrument("seed")
        native_baseline=re.search(r"INSTRUMENTATION_STATUS: xs_seed_available_bytes=(\d+)",seeded)
        native_uid=re.search(r"INSTRUMENTATION_STATUS: xs_seed_app_uid=(\d+)",seeded)
        if not native_baseline or not native_uid or int(native_uid[1])<10000:raise RuntimeError("Ordinary application free-space measurement not proven")
        listing=run(["shell","df","-k","/data"],"space-before")
        fields=listing.strip().splitlines()[-1].split()
        if len(fields)<6 or not fields[3].isdigit():raise RuntimeError("Cannot measure emulator /data free space")
        # Legacy df and filesystem reservations can differ from the ordinary app's
        # usable bytes. Budget with the same real syscall/API used by the download guard.
        available=int(native_baseline[1]);target=28*1024*1024;allocate=available-target
        report.update(native_baseline_available_bytes=available,native_actor_uid=int(native_uid[1]),diagnostic_df_available_bytes=int(fields[3])*1024)
        if allocate<=0:raise RuntimeError("Disposable emulator already has insufficient free space")
        allocation_attempted=True
        try:run(["shell","fallocate","-l",str(allocate),allocation],"allocate-pressure",180)
        except RuntimeError:
            # Older toybox images may not provide fallocate. Write only our generated
            # file instead, retaining at least the same measured safety margin.
            run(["shell","rm","-f",allocation],"reset-generated-allocation")
            run(["shell","dd","if=/dev/zero","of="+allocation,"bs=1048576","count="+str(allocate//(1024*1024))],"allocate-pressure-dd",240)
        text=instrument("low-space")
        measured=re.search(r"INSTRUMENTATION_STATUS: xs_available_bytes=(\d+)",text)
        measured_uid=re.search(r"INSTRUMENTATION_STATUS: xs_low_space_app_uid=(\d+)",text)
        if not measured or not 0<int(measured[1])<40*1024*1024 or not measured_uid or int(measured_uid[1])!=int(native_uid[1]):raise RuntimeError("Native measured pressure at the ordinary application UID not proven")
        report.update(status="PASS",native_available_bytes=int(measured[1]),requested_allocation_bytes=allocate)
    finally:
        try:
            if allocation_attempted:
                run(["shell","rm","-f",allocation],"release-pressure")
                run(["shell","test","!","-e",allocation],"allocation-absent")
                run(["shell","df","-k","/data"],"space-after-release")
        except Exception:
            report.update(status="FAIL",cleanup_failed=True)
            raise
        finally:(OUT/"summary.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print("PASS actual emulator low-space admission; original native files remain intact")


if __name__=="__main__":main()
