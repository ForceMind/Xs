#!/usr/bin/env python3
"""Synthetic same-job debug signer upgrade, never a production-key/device upgrade claim."""
from pathlib import Path
import shutil
import subprocess


def main():
    output=Path("app-android/build/compatibility/signed-debug-upgrade")
    output.mkdir(parents=True,exist_ok=True)
    apk=Path("app-android/build/outputs/apk/debug/app-android-debug.apk")
    source=output/"version-3-debug.apk"
    target=output/"version-4-debug.apk"
    shutil.copyfile(apk,source)
    try:
        subprocess.run(["gradle","--no-daemon",":app-android:assembleDebug","-PxsVersionCode=4"],check=True,timeout=300)
        shutil.copyfile(apk,target)
    finally:
        # The main XML suite executed version3. Preserve exactly that APK for the
        # job-local XML provenance; version4 has its own explicit upgrade report.
        shutil.copyfile(source,apk)
    subprocess.run(["python3","scripts/test-process-recovery.py","--upgrade-apk",str(target)],check=True,timeout=300)


if __name__=="__main__":main()
