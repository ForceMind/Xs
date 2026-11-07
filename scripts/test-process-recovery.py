#!/usr/bin/env python3
"""Cloud emulator only: explicitly stop a live fixture process, then verify cold recovery.

No cookies, account data, URL references or private files are read by this host driver.
The companion instrumentation stores only generated, synthetic native fixtures.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

PACKAGE = "dev.forcemind.xs"
TEST_CLASS = "dev.forcemind.xs.android.ProcessRecoveryProbe"
OUT = Path("app-android/build/compatibility/process-recovery")


def run(arguments, name, timeout=90):
    result = subprocess.run(["adb", *arguments], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    (OUT / (name + ".txt")).write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError("Fixture command failed: " + name)
    return result.stdout


def instrumentation(phase, stopped_pid=None):
    output = run(["shell", "am", "instrument", "-w", "-e", "class", TEST_CLASS,
                  "-e", "xs_phase", phase,
                  *([] if stopped_pid is None else ["-e", "xs_stopped_pid", str(stopped_pid)]),
                  PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"], phase)
    if not re.search(r"OK \(1 test\)", output) or "FAILURES" in output or "INSTRUMENTATION_FAILED" in output:
        raise RuntimeError("Recovery instrumentation did not pass: " + phase)
    return output


def main():
    global OUT
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upgrade-apk",type=Path)
    args=parser.parse_args()
    if args.upgrade_apk:OUT=OUT.parent/"signed-debug-upgrade"
    OUT.mkdir(parents=True, exist_ok=True)
    report = {"status": "FAIL", "scope": "Synthetic native fixtures; real emulator OS force-stop and new target process, not reboot/physical power loss/vendor background/live X", "phases": []}
    try:
        instrumentation("seed")
        report["phases"].append("seed_pass")
        # Native first-use screen only: the seed persisted browser consent OFF.
        output = run(["shell", "am", "start", "-W", "-n", PACKAGE + "/dev.forcemind.xs.browser.BrowserActivity"], "foreground")
        if "Error" in output:
            raise RuntimeError("Fixture foreground launch failed")
        before = run(["shell", "pidof", PACKAGE], "pid-before").strip()
        if not re.fullmatch(r"\d+", before):
            raise RuntimeError("Live foreground process required before force-stop")
        report["foreground_pid"] = int(before)
        run(["shell", "am", "force-stop", PACKAGE], "force-stop")
        # pidof's nonzero exit means absent; do not turn that expected result into a
        # subprocess failure, and do not assume that a successful am command killed it.
        absent = subprocess.run(["adb", "shell", "pidof", PACKAGE], capture_output=True, text=True, timeout=15)
        (OUT / "pid-after-stop.txt").write_text(absent.stdout + absent.stderr, encoding="utf-8")
        if absent.returncode!=1 or absent.stdout.strip() or absent.stderr.strip():
            raise RuntimeError("Cannot prove target process is absent after force-stop")
        report["phases"].append("live_process_confirmed_stopped")
        if args.upgrade_apk:
            source=Path("app-android/build/outputs/apk/debug/app-android-debug.apk")
            aapt=Path(os.environ["ANDROID_HOME"])/"build-tools/35.0.0/aapt"
            signer=aapt.with_name("apksigner")
            proofs=[]
            for label,apk in (("source",source),("target",args.upgrade_apk)):
                signature=subprocess.run([str(signer),"verify","--print-certs",str(apk)],capture_output=True,text=True,check=True,timeout=30).stdout
                certificate=re.search(r"Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})",signature)
                badging=subprocess.run([str(aapt),"dump","badging",str(apk)],capture_output=True,text=True,check=True,timeout=30).stdout
                package=re.search(r"package: name='([^']+)' versionCode='(\d+)'",badging)
                if not certificate or not package or package[1]!=PACKAGE:raise RuntimeError("Upgrade provenance unavailable")
                proofs.append({"label":label,"sha256":hashlib.sha256(apk.read_bytes()).hexdigest(),"certificate_sha256":certificate[1].lower(),"version_code":int(package[2]),"debug_fixture":True})
            if proofs[0]["certificate_sha256"]!=proofs[1]["certificate_sha256"] or proofs[1]["version_code"]<=proofs[0]["version_code"]:raise RuntimeError("Upgrade must retain signature and increase version")
            installed=run(["shell","dumpsys","package",PACKAGE],"installed-before-upgrade")
            version=re.search(r"versionCode=(\d+)",installed)
            if not version or int(version[1])!=proofs[0]["version_code"]:raise RuntimeError("Installed source version not proven")
            output=run(["install","-r",str(args.upgrade_apk)],"upgrade-install",120)
            if not re.search(r"\bSuccess\b",output):raise RuntimeError("Upgrade install did not succeed")
            installed=run(["shell","dumpsys","package",PACKAGE],"installed-after-upgrade")
            version=re.search(r"versionCode=(\d+)",installed)
            if not version or int(version[1])!=proofs[1]["version_code"]:raise RuntimeError("Installed target version not proven")
            report["upgrade_artifacts"]=proofs
        verified=instrumentation("verify",int(before))
        pid=re.search(r"INSTRUMENTATION_STATUS: xs_verify_pid=(\d+)",verified)
        if not pid or int(pid[1])==int(before):raise RuntimeError("Cold verification process not proven")
        report["verify_pid"]=int(pid[1])
        report["phases"].append("separate_process_startup_recovery_pass")
        report["status"] = "PASS"
    finally:
        (OUT / "summary.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS live-process force-stop and cold native recovery; no physical-device claim")


if __name__ == "__main__":
    main()
