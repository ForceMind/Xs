#!/usr/bin/env python3
"""Restore explicit fixture APKs after UTP cleanup, once before external system probes."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

OUT=Path("app-android/build/compatibility/process-fixture-preparation")
PACKAGE="dev.forcemind.xs"
COMPONENT=PACKAGE+".test/androidx.test.runner.AndroidJUnitRunner"


def run(args,name):
    result=subprocess.run(["adb",*args],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=120)
    (OUT/(name+".txt")).write_text(result.stdout+result.stderr,encoding="utf-8")
    if result.returncode:raise RuntimeError("Fixture preparation failed: "+name)
    return result.stdout


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    report={"status":"FAIL","scope":"Explicit same-job fixture installation after UTP removes the instrumentation APK; not an app/device acceptance result"}
    try:
        if not run(["get-serialno"],"serial").strip().startswith("emulator-") or run(["shell","getprop","ro.kernel.qemu"],"qemu").strip()!="1":raise RuntimeError("Disposable emulator required; never prepare a user phone")
        apks=[("application",Path("app-android/build/outputs/apk/debug/app-android-debug.apk")),("instrumentation",Path("app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk"))]
        records=[]
        for role,path in apks:
            if not path.is_file():raise RuntimeError("Built fixture APK unavailable: "+role)
            text=run(["install","-r","-t",str(path)],"install-"+role)
            if not re.search(r"\bSuccess\b",text):raise RuntimeError("Fixture installation was not confirmed: "+role)
            records.append({"role":role,"sha256":hashlib.sha256(path.read_bytes()).hexdigest(),"bytes":path.stat().st_size})
        installed=run(["shell","pm","list","instrumentation"],"instrumentation-components")
        expected="instrumentation:"+COMPONENT+" (target="+PACKAGE+")"
        if expected not in installed.splitlines():raise RuntimeError("Actual target instrumentation component unavailable")
        info=run(["shell","dumpsys","package",PACKAGE],"installed-application")
        version=re.search(r"versionCode=(\d+)",info)
        if not version or int(version[1])!=3:raise RuntimeError("Initial fixture must be version3; never downgrade an upgraded target")
        report.update(status="PASS",apk_records=records,actual_component=COMPONENT,installed_version_code=3)
    finally:(OUT/"summary.json").write_text(json.dumps(report,indent=2)+"\n",encoding="utf-8")
    print("PASS explicit v3 fixture/test APK installation and actual instrumentation component")


if __name__=="__main__":main()
