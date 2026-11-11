#!/usr/bin/env python3
"""Collect public build and WebView metadata for a manually operated W0 test."""

import argparse
from datetime import datetime, timezone
import json
import re
import subprocess
import sys


PACKAGE = "dev.forcemind.xs"
SHA256 = r"[0-9a-fA-F]{64}"
APK_PATH = r"/data/app/(?:[A-Za-z0-9_~.+\-=]+/)+base\.apk"
CURRENT_WEBVIEW = (
    r"^\s*Current WebView package \(name, version\): "
    r"\(([A-Za-z0-9_.]+), ([A-Za-z0-9_.+\-]{1,80})\)\s*$"
)


def collect(adb, serial, expected_hash):
    def shell(*args):
        result = subprocess.run(
            [adb, "-s", serial, "shell", *args],
            capture_output=True, text=True, timeout=20, check=False,
        )
        if result.returncode:
            raise ValueError(f"ADB {args[0]} failed. Check the selected device and its USB authorization.")
        return result.stdout.strip()

    def property_value(name, pattern):
        value = shell("getprop", name)
        if not re.fullmatch(pattern, value):
            raise ValueError(f"Missing or unrecognized Android property: {name}")
        return value

    paths = shell("pm", "path", "--user", "current", PACKAGE).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise ValueError("Expected one installed standalone Xs APK in the current Android user.")
    path = paths[0].removeprefix("package:")
    if not re.fullmatch(APK_PATH, path) or any(part in {".", ".."} for part in path.split("/")):
        raise ValueError("Unrecognized installed APK path.")
    digest = shell("sha256sum", path).split()
    if not digest or not re.fullmatch(SHA256, digest[0]) or digest[0].lower() != expected_hash:
        raise ValueError("Installed APK SHA-256 does not match the selected test build.")

    provider = re.findall(CURRENT_WEBVIEW, shell("dumpsys", "webviewupdate"), re.MULTILINE)
    if len(provider) != 1:
        raise ValueError("Current WebView provider unavailable. Record it from Xs Build and compatibility.")
    qemu_flags = [property_value(name, r"[01]?") for name in ("ro.kernel.qemu", "ro.boot.qemu")]
    return {
        "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "Build and device metadata only. All manual W0/T14 cases are NOT RUN.",
        "package": PACKAGE,
        "installed_apk_sha256": expected_hash,
        "manufacturer": property_value("ro.product.manufacturer", r"[A-Za-z0-9 ._()+\-]{1,80}"),
        "model": property_value("ro.product.model", r"[A-Za-z0-9 ._()+\-]{1,80}"),
        "android_release": property_value("ro.build.version.release", r"[A-Za-z0-9.\-]{1,16}"),
        "android_api": int(property_value("ro.build.version.sdk", r"[1-9][0-9]{0,2}")),
        "reported_emulator": "1" in qemu_flags,
        "webview_package": provider[0][0],
        "webview_version": provider[0][1],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Device selected from adb devices (omitted from output)")
    parser.add_argument("--apk-sha256", required=True, help="Expected standalone APK digest from the build record")
    parser.add_argument("--adb", default="adb", help="ADB executable")
    args = parser.parse_args()
    if not re.fullmatch(SHA256, args.apk_sha256):
        parser.error("--apk-sha256 must contain 64 hexadecimal characters")
    try:
        report = collect(args.adb, args.serial, args.apk_sha256.lower())
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    except (OSError, subprocess.TimeoutExpired):
        print("ADB is unavailable or did not respond in time.", file=sys.stderr)
        return 1
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
