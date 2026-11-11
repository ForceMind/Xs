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


class CommandFailure(RuntimeError):
    def __init__(self,name,output):
        super().__init__("Low-space fixture command failed: "+name)
        self.operation=name
        self.output=output


def run(args,name,timeout=120):
    result=subprocess.run(["adb",*args],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=timeout)
    (OUT/(name+".txt")).write_text(result.stdout+result.stderr,encoding="utf-8")
    if result.returncode:raise CommandFailure(name,result.stdout+result.stderr)
    return result.stdout


def instrument(phase):
    text=run(["shell","am","instrument","-w","-e","class",TEST_CLASS,"-e","xs_phase",phase,
              *([] if phase!="low-space" else ["-e","xs_pressure_fixture","bounded-native-buffer-v1"]),COMPONENT],phase)
    if not re.search(r"OK \(1 test\)",text) or "FAILURES" in text or "INSTRUMENTATION_FAILED" in text:raise RuntimeError("Low-space instrumentation did not pass: "+phase)
    return text


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    report={"status":"FAIL","scope":"Real disposable emulator /data pressure: media admission plus ordinary-UID buffer admission below1MiB, bounded native tail allocation/release and fresh capture recovery; actual registered file/Archive preserved. Not mid-stream ENOSPC, physical flash, or user-device evidence."}
    allocation="/data/local/tmp/xs-disk-pressure-fixture-"+uuid.uuid4().hex
    allocation_attempted=False
    root_attempted=False
    try:
        if os.environ.get("GITHUB_ACTIONS")!="true" or not run(["get-serialno"],"emulator-serial").strip().startswith("emulator-") or run(["shell","getprop","ro.kernel.qemu"],"emulator-qemu").strip()!="1":raise RuntimeError("Disk pressure is restricted to this disposable GitHub CI emulator")
        if run(["shell","id","-u"],"allocator-initial-uid").strip()!="2000":raise RuntimeError("Pressure fixture must start with the ordinary shell UID")
        report.update(allocator="ordinary-shell",allocator_uid_before=2000)
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
        def allocate_file(label):
            try:run(["shell","fallocate","-l",str(allocate),allocation],label,180)
            except RuntimeError:
                # Old 32-bit toybox may reject a >2GiB fallocate length. This writes
                # real bytes only to our generated file, with the same safety margin.
                run(["shell","rm","-f",allocation],label+"-reset")
                run(["shell","dd","if=/dev/zero","of="+allocation,"bs=1048576","count="+str(allocate//(1024*1024))],label+"-dd",240)
        allocation_attempted=True
        try:allocate_file("allocate-pressure")
        except CommandFailure as failure:
            # Android 8 emulator allocation quotas can stop the shell while the
            # ordinary app still reports hundreds of MiB free. Only that proven
            # fixture error permits privileged allocation, never a relaxed assertion.
            if failure.operation!="allocate-pressure-dd" or "quota exceeded" not in failure.output.lower():raise
            sdk=run(["shell","getprop","ro.build.version.sdk"],"allocator-api").strip()
            build_type=run(["shell","getprop","ro.build.type"],"allocator-build-type").strip()
            debuggable=run(["shell","getprop","ro.debuggable"],"allocator-debuggable").strip()
            allocator_uid=run(["shell","id","-u"],"allocator-uid-before").strip()
            if sdk not in ("26","27") or build_type!="userdebug" or debuggable!="1" or allocator_uid!="2000":raise RuntimeError("Privileged allocation is restricted to the old disposable userdebug CI emulator shell-quota fixture")
            run(["shell","rm","-f",allocation],"remove-shell-quota-partial")
            root_attempted=True
            run(["root"],"allocator-root")
            run(["wait-for-device"],"allocator-root-ready")
            if run(["shell","id","-u"],"allocator-uid-root").strip()!="0":raise RuntimeError("Disposable pressure allocator did not gain root")
            report.update(allocator="isolated-root-after-shell-quota",allocator_uid_before=2000,allocator_uid_during=0)
            allocate_file("allocate-pressure-root")
        text=instrument("low-space")
        measured=re.search(r"INSTRUMENTATION_STATUS: xs_available_bytes=(\d+)",text)
        measured_uid=re.search(r"INSTRUMENTATION_STATUS: xs_low_space_app_uid=(\d+)",text)
        if not measured or not 0<int(measured[1])<40*1024*1024 or not measured_uid or int(measured_uid[1])!=int(native_uid[1]):raise RuntimeError("Native measured pressure at the ordinary application UID not proven")
        buffer={}
        for key in ("available_before","available_after","pressure_bytes","recovered_bytes","rejected","retry_accepted","file_removed"):
            values=re.findall(r"INSTRUMENTATION_STATUS: xs_buffer_"+key+r"=(\d+)(?:\r?\n|$)",text)
            if len(values)!=1:raise RuntimeError("Unambiguous native buffer pressure proof required: "+key)
            buffer[key]=int(values[0])
        if not (128*1024<=buffer["available_before"]<1024*1024 and 0<buffer["available_after"]<1024*1024
                and 0<buffer["pressure_bytes"]<=40*1024*1024 and buffer["recovered_bytes"]>2*1024*1024
                and buffer["rejected"]==buffer["retry_accepted"]==buffer["file_removed"]==1):
            raise RuntimeError("Actual native buffer rejection, bounded pressure release and recovery not proven")
        report["native_buffer_pressure"]=buffer
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
        finally:
            try:
                if root_attempted:
                    run(["unroot"],"allocator-unroot")
                    run(["wait-for-device"],"allocator-unroot-ready")
                    restored_uid=run(["shell","id","-u"],"allocator-uid-restored").strip()
                    if restored_uid!="2000":raise RuntimeError("Disposable allocator privileges were not restored to the shell UID")
                    report["allocator_uid_restored"]=2000
            except Exception:
                report.update(status="FAIL",privilege_restore_failed=True)
                raise
            finally:(OUT/"summary.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print("PASS actual emulator low-space admission; original native files remain intact")


if __name__=="__main__":main()
