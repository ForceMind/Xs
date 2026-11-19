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


class InstrumentationFailure(RuntimeError):
    def __init__(self, phase, output):
        super().__init__("Low-space instrumentation did not pass: " + phase)
        self.output = output


def instrument(phase, log_name=None):
    text=run(["shell","am","instrument","-w","-e","class",TEST_CLASS,"-e","xs_phase",phase,
              *([] if phase!="low-space" else ["-e","xs_pressure_fixture","bounded-native-buffer-v1"]),COMPONENT],log_name or phase)
    if not re.search(r"OK \(1 test\)",text) or "FAILURES" in text or "INSTRUMENTATION_FAILED" in text:
        raise InstrumentationFailure(phase, text)
    return text


def native_baseline(log_name):
    seeded = instrument("seed", log_name)
    available = re.search(r"INSTRUMENTATION_STATUS: xs_seed_available_bytes=(\d+)", seeded)
    uid = re.search(r"INSTRUMENTATION_STATUS: xs_seed_app_uid=(\d+)", seeded)
    if not available or not uid or int(uid[1]) < 10000:
        raise RuntimeError("Ordinary application free-space measurement not proven")
    return int(available[1]), int(uid[1])


def excess_free_bytes(failure):
    # Retry only an evidenced setup failure before the admission assertions:
    # the native fixture used all 40 MiB while usable space still exceeded its floor.
    output = failure.output
    match = re.search(r"^java\.lang\.AssertionError: Actual native filesystem floor required: "
                      r"available=(\d+), written=41943040\s*$", output, re.MULTILINE)
    if match and int(match[1]) >= 1024 * 1024 and re.search(r"Tests run: 1,\s*Failures: 1\b", output):
        return int(match[1])
    return None


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
        available, native_uid = native_baseline("seed")
        listing=run(["shell","df","-k","/data"],"space-before")
        fields=listing.strip().splitlines()[-1].split()
        if len(fields)<6 or not fields[3].isdigit():raise RuntimeError("Cannot measure emulator /data free space")
        # Legacy df and filesystem reservations can differ from the ordinary app's
        # usable bytes. Budget with the same real syscall/API used by the download guard.
        target=28*1024*1024;allocate=available-target
        report.update(native_baseline_available_bytes=available,native_actor_uid=native_uid,diagnostic_df_available_bytes=int(fields[3])*1024)
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
        try:
            text = instrument("low-space")
        except InstrumentationFailure as failure:
            excess = excess_free_bytes(failure)
            if (excess is None or root_attempted or
                    run(["shell", "getprop", "ro.build.version.sdk"], "retry-allocator-api").strip() != "36" or
                    run(["shell", "id", "-u"], "retry-allocator-uid").strip() != "2000"):
                raise
            report["pressure_setup_retry"] = {
                "first_attempt_available_bytes": excess,
                "first_attempt_requested_allocation_bytes": allocate,
            }
            run(["shell", "rm", "-f", allocation], "release-pressure-before-retry")
            run(["shell", "test", "!", "-e", allocation], "allocation-absent-before-retry")
            retry_available, retry_uid = native_baseline("seed-retry")
            allocate = retry_available - target
            if retry_uid != native_uid or allocate <= 0:
                raise RuntimeError("Invalid ordinary-UID baseline for pressure retry")
            report["pressure_setup_retry"]["native_baseline_available_bytes"] = retry_available
            allocate_file("allocate-pressure-retry")
            text = instrument("low-space", "low-space-retry")
        measured=re.search(r"INSTRUMENTATION_STATUS: xs_available_bytes=(\d+)",text)
        measured_uid=re.search(r"INSTRUMENTATION_STATUS: xs_low_space_app_uid=(\d+)",text)
        if not measured or not 0<int(measured[1])<40*1024*1024 or not measured_uid or int(measured_uid[1])!=native_uid:raise RuntimeError("Native measured pressure at the ordinary application UID not proven")
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
