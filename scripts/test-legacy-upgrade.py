#!/usr/bin/env python3
"""Disposable CI emulator: actual old-main APK/schema2 -> current APK/schema6.

Both APKs are built from their real source trees with the same job-local debug key.
The old target is populated through its native API by a reflection-only test probe.
Nothing is uninstalled, downgraded, or cleared before successful verification. This
is not a production-key, physical-device, web-session, or old-media migration test.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

OLD_COMMIT = "f88003c8ec1ee193b75de7267db6ae7654ee77c4"
PACKAGE = "dev.forcemind.xs"
COMPONENT = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "dev.forcemind.xs.android.LegacyUpgradeProbe"
APP_APK = Path("app-android/build/outputs/apk/debug/app-android-debug.apk")
TEST_APK = Path("app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk")
OUT = Path("app-android/build/compatibility/legacy-upgrade")


class CommandFailure(RuntimeError):
    def __init__(self, operation, returncode):
        super().__init__("Legacy upgrade command failed: " + operation)
        self.operation = operation
        self.returncode = returncode


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class LegacyUpgradeDriver:
    def __init__(self, root=None, environment=None, executor=None):
        self.root = (Path(root) if root is not None else Path(__file__).resolve().parents[1]).resolve()
        self.environment = os.environ if environment is None else environment
        self.executor = subprocess.run if executor is None else executor
        self.output = self.root / OUT
        self.stage = "environment_guard"

    def command(self, arguments, name, cwd=None, timeout=120, allowed=(0,)):
        result = self.executor([str(x) for x in arguments], cwd=str(cwd or self.root),
                               capture_output=True, text=True, encoding="utf-8",
                               errors="replace", timeout=timeout)
        (self.output / (name + ".txt")).write_text(result.stdout + result.stderr, encoding="utf-8")
        (self.output / (name + ".result.json")).write_text(json.dumps({
            "argv": [str(x) for x in arguments], "cwd": str(cwd or self.root),
            "exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr,
        }, indent=2) + "\n", encoding="utf-8")
        if result.returncode not in allowed:
            raise CommandFailure(name, result.returncode)
        return result

    def adb(self, arguments, name, **kwargs):
        return self.command(["adb", *arguments], name, **kwargs)

    def git(self, arguments, name, cwd=None):
        return self.command(["git", *arguments], name, cwd=cwd).stdout.strip()

    def installed(self, label, expected_version):
        info = self.adb(["shell", "dumpsys", "package", PACKAGE], label + "-package").stdout
        version = re.search(r"\bversionCode=(\d+)\b", info)
        listing = self.adb(["shell", "pm", "list", "packages", "-U", "--user", "0", PACKAGE],
                           label + "-uid").stdout
        uids = re.findall(r"^package:" + re.escape(PACKAGE) + r"\s+uid:(\d+)\s*$", listing, re.MULTILINE)
        if not version or int(version[1]) != expected_version or len(uids) != 1 or int(uids[0]) < 10000:
            raise RuntimeError("Installed package version/ordinary UID not proven: " + label)
        return {"package": PACKAGE, "version_code": int(version[1]), "uid": int(uids[0])}

    def apk_proof(self, path, role, package, expected_version=None):
        sdk = self.environment.get("ANDROID_HOME") or self.environment.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise RuntimeError("Pinned Android build tools unavailable")
        tools = Path(sdk) / "build-tools/35.0.0"
        signature = self.command([tools / "apksigner", "verify", "--print-certs", path],
                                 role + "-signature", timeout=30).stdout
        certs = set(x.lower() for x in re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", signature))
        badging = self.command([tools / "aapt", "dump", "badging", path], role + "-badging", timeout=30).stdout
        identity = re.search(r"package: name='([^']+)' versionCode='([^']*)'", badging)
        if len(certs) != 1 or not identity or identity[1] != package:
            raise RuntimeError("APK package/signature proof unavailable: " + role)
        raw_version = identity[2]
        if re.fullmatch(r"\d+", raw_version):
            version = int(raw_version)
        elif raw_version == "" and role == "current-instrumentation" and package == PACKAGE + ".test" and expected_version is None:
            # AGP's generated instrumentation manifest need not declare a versionCode.
            # Preserve actual aapt absence rather than inventing an application version.
            version = None
        else:
            raise RuntimeError("APK version proof unavailable: " + role)
        if expected_version is not None and version != expected_version:
            raise RuntimeError("Unexpected APK version: " + role)
        return {"role": role, "package": package, "version_code": version,
                "bytes": path.stat().st_size, "sha256": sha256(path),
                "certificate_sha256": next(iter(certs)), "signature_verification": "passed"}

    def instrument(self, phase, expected=None):
        arguments = ["shell", "am", "instrument", "-w", "-e", "class", TEST_CLASS,
                     "-e", "xs_legacy_phase", phase]
        if expected:
            for key, value in expected.items():
                arguments.extend(["-e", "xs_legacy_" + key, str(value)])
        text = self.adb([*arguments, COMPONENT], phase, timeout=120).stdout
        if not re.search(r"OK \(1 test\)", text) or "FAILURES" in text or "INSTRUMENTATION_FAILED" in text:
            raise RuntimeError("Legacy upgrade instrumentation did not pass: " + phase)
        if phase == "cleanup":
            return None
        values = {}
        for key in ("schema", "uid", "pid", "db_path", "scope"):
            # AndroidJUnitRunner can append the first status to its exact test-class
            # progress prefix without a newline. Do not accept arbitrary log prefixes.
            matches = re.findall(r"^(?:" + re.escape(TEST_CLASS) + r":[ \t]*)?INSTRUMENTATION_STATUS: xs_legacy_" + key + r"=([^\r\n]+)\r?$", text, re.MULTILINE)
            if len(matches) != 1:
                raise RuntimeError("Native status not unambiguously proven: " + phase + "/" + key)
            values[key] = matches[0]
        if not all(re.fullmatch(r"\d+", values[key]) for key in ("schema", "uid", "pid")):
            raise RuntimeError("Native numeric status invalid: " + phase)
        values.update((key, int(values[key])) for key in ("schema", "uid", "pid"))
        if values["uid"] < 10000 or values["pid"] <= 0:
            raise RuntimeError("Native probe must use an ordinary target process")
        if not re.fullmatch(r"local:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", values["scope"]):
            raise RuntimeError("Native default fixture scope invalid")
        if not re.fullmatch(r"/data/(?:user/0|data)/" + re.escape(PACKAGE) + r"/databases/xs-local-history\.db", values["db_path"]):
            raise RuntimeError("Native fixed private database path invalid")
        return values

    def execute(self):
        self.output.mkdir(parents=True, exist_ok=True)
        report = {"status": "FAIL", "scope": "Actual fixed old-main source APK/schema2 to current source APK/schema6, "
                  "using one job-local debug signer on a disposable CI emulator. Synthetic native records only; "
                  "not production-key, physical-device or live-X acceptance.", "phases": [],
                  "old_independent_favorites": "NOT APPLICABLE: absent in this old main",
                  "old_media_library": "NOT APPLICABLE: absent in this old main",
                  "external_probe_invocations_counted_in_main_xml": False}
        temporary = None
        worktree = None
        worktree_attempted = False
        original_failure = None
        try:
            if self.environment.get("GITHUB_ACTIONS") != "true":
                raise RuntimeError("Legacy upgrade is restricted to disposable GitHub CI")
            serial = self.adb(["get-serialno"], "emulator-serial").stdout.strip()
            qemu = self.adb(["shell", "getprop", "ro.kernel.qemu"], "emulator-qemu").stdout.strip()
            uid = self.adb(["shell", "id", "-u"], "initial-shell-uid").stdout.strip()
            if not re.fullmatch(r"emulator-\d+", serial) or qemu != "1" or uid != "2000":
                raise RuntimeError("Fresh ordinary-shell disposable emulator not proven")
            packages = self.adb(["shell", "pm", "list", "packages", "--user", "0", PACKAGE], "fresh-package-list").stdout
            package_lines = [x.strip() for x in packages.splitlines() if x.strip()]
            if any(not re.fullmatch(r"package:[A-Za-z][A-Za-z0-9_.]*", x) for x in package_lines) or "package:" + PACKAGE in package_lines:
                raise RuntimeError("Fresh emulator target must be absent before legacy installation")
            report.update(emulator_serial=serial, initial_shell_uid=2000, initial_target_absent=True)
            self.stage = "source_and_apk_verification"
            head = self.git(["rev-parse", "HEAD"], "current-source-commit")
            tree = self.git(["rev-parse", "HEAD^{tree}"], "current-source-tree")
            clean = self.git(["status", "--porcelain=v1", "--untracked-files=normal"], "current-source-status")
            resolved = self.git(["rev-parse", OLD_COMMIT + "^{commit}"], "old-source-resolution")
            if not re.fullmatch(r"[a-f0-9]{40}", head) or not re.fullmatch(r"[a-f0-9]{40}", tree) or clean or resolved != OLD_COMMIT:
                raise RuntimeError("Exact clean current/old source identity unavailable")
            report["current_source"] = {"head_commit": head, "head_tree": tree, "working_tree_clean": True}
            current = self.root / APP_APK
            test = self.root / TEST_APK
            current_proof = self.apk_proof(current, "current-application", PACKAGE, 3)
            test_proof = self.apk_proof(test, "current-instrumentation", PACKAGE + ".test")
            if current_proof["certificate_sha256"] != test_proof["certificate_sha256"]:
                raise RuntimeError("Target/test APK must share the same job-local debug signer")
            original_current_hash, original_test_hash = current_proof["sha256"], test_proof["sha256"]
            self.stage = "old_source_build"
            temp_parent = self.environment.get("RUNNER_TEMP")
            temporary = Path(tempfile.mkdtemp(prefix="xs-legacy-upgrade-", dir=temp_parent if temp_parent else None))
            worktree = temporary / "old-source"
            worktree_attempted = True
            self.command(["git", "worktree", "add", "--detach", worktree, OLD_COMMIT], "old-worktree-add")
            old_head = self.git(["rev-parse", "HEAD"], "old-source-commit", cwd=worktree)
            old_tree = self.git(["rev-parse", "HEAD^{tree}"], "old-source-tree", cwd=worktree)
            old_clean = self.git(["status", "--porcelain=v1", "--untracked-files=normal"], "old-source-status", cwd=worktree)
            if old_head != OLD_COMMIT or not re.fullmatch(r"[a-f0-9]{40}", old_tree) or old_clean:
                raise RuntimeError("Detached old worktree did not match the fixed source")
            report["old_source"] = {"head_commit": old_head, "head_tree": old_tree, "working_tree_clean_before_build": True}
            self.command(["gradle", "--no-daemon", "-p", worktree, ":app-android:assembleDebug"], "old-application-build", timeout=600)
            old = worktree / APP_APK
            old_proof = self.apk_proof(old, "old-application", PACKAGE, 1)
            if old_proof["certificate_sha256"] != current_proof["certificate_sha256"]:
                raise RuntimeError("Cross-source upgrade must retain the same job-local signer")
            for source, filename in ((old, "old-main-v1.apk"), (current, "current-main-v3.apk"), (test, "current-instrumentation.apk")):
                shutil.copyfile(source, self.output / filename)
            if sha256(current) != original_current_hash or sha256(test) != original_test_hash:
                raise RuntimeError("Legacy build modified the current main/test APK outputs")
            report["apk_artifacts"] = [old_proof, current_proof, test_proof]
            report["current_build_outputs_preserved"] = True
            self.stage = "old_install_and_seed"
            for apk, label in ((old, "install-old-application"), (test, "install-current-instrumentation")):
                installed = self.adb(["install", "-r", "-t", str(apk)], label).stdout
                if not re.search(r"\bSuccess\b", installed):
                    raise RuntimeError("Fixture APK installation not confirmed: " + label)
            old_installed = self.installed("installed-old", 1)
            components = self.adb(["shell", "pm", "list", "instrumentation"], "instrumentation-components").stdout
            if "instrumentation:" + COMPONENT + " (target=" + PACKAGE + ")" not in components.splitlines():
                raise RuntimeError("Actual old-target instrumentation component unavailable")
            seed = self.instrument("seed")
            if seed["schema"] != 2 or seed["uid"] != old_installed["uid"]:
                raise RuntimeError("Actual old schema2/ordinary target UID not proven")
            report.update(installed_old=old_installed, seed=seed)
            report["phases"].append("old_source_apk_schema2_native_seed_pass")
            self.stage = "old_live_process_stop"
            # am instrument -w can return before target teardown has finished.
            # End that process explicitly before starting an independent old-app
            # foreground process; an am start Status:ok alone proves no live PID.
            self.adb(["shell", "am", "force-stop", PACKAGE], "old-seed-force-stop")
            seed_absent = self.adb(["shell", "pidof", PACKAGE], "old-seed-pid-after-stop", allowed=(1,))
            if seed_absent.stdout.strip() or seed_absent.stderr.strip():
                raise RuntimeError("Old seed process absence not proven")
            report["phases"].append("old_seed_process_stopped_and_absent")
            launched = self.adb(["shell", "am", "start", "-W", "-n", PACKAGE + "/dev.forcemind.xs.browser.BrowserActivity"], "old-native-intro").stdout
            if not re.search(r"\bStatus:\s*ok\b", launched) or "Error" in launched:
                raise RuntimeError("Old native introduction foreground launch not confirmed")
            foreground = self.adb(["shell", "pidof", PACKAGE], "old-foreground-pid").stdout.strip()
            if not re.fullmatch(r"\d+", foreground) or int(foreground) <= 0 or int(foreground) == seed["pid"]:
                raise RuntimeError("Old foreground target process not proven")
            report["old_foreground_pid"] = int(foreground)
            self.adb(["shell", "am", "force-stop", PACKAGE], "old-force-stop")
            absent = self.adb(["shell", "pidof", PACKAGE], "old-pid-after-stop", allowed=(1,))
            if absent.stdout.strip() or absent.stderr.strip():
                raise RuntimeError("Old process absence after force-stop not proven")
            report["phases"].append("old_live_process_stopped_and_absent")
            self.stage = "normal_in_place_upgrade"
            upgraded = self.adb(["install", "-r", "-t", str(current)], "upgrade-current-application").stdout
            if not re.search(r"\bSuccess\b", upgraded):
                raise RuntimeError("Normal in-place current APK installation not confirmed")
            new_installed = self.installed("installed-current", 3)
            if new_installed["uid"] != old_installed["uid"]:
                raise RuntimeError("In-place upgrade changed the target UID")
            report["installed_current"] = new_installed
            self.stage = "native_schema6_verification"
            verified = self.instrument("verify", {"stopped_pid": foreground, "expected_uid": seed["uid"],
                "expected_path": seed["db_path"], "expected_scope": seed["scope"]})
            if verified["schema"] != 6 or verified["uid"] != seed["uid"] or verified["pid"] in (seed["pid"], int(foreground)) or verified["db_path"] != seed["db_path"] or verified["scope"] != seed["scope"]:
                raise RuntimeError("Cross-source/schema process, UID, fixed path or scope preservation not proven")
            report["verify"] = verified
            report["phases"].append("current_source_apk_schema6_native_verification_pass")
            self.stage = "owned_fixture_cleanup"
            self.instrument("cleanup")
            if sha256(current) != original_current_hash or sha256(test) != original_test_hash:
                raise RuntimeError("Current APK outputs changed during the legacy fixture")
            report["phases"].append("only_owned_synthetic_fixtures_cleaned")
            report.update(status="PASS", source_schema_version=2, target_schema_version=6,
                          external_native_probe_invocations=3)
            self.stage = "complete"
        except Exception as failure:
            original_failure = failure
            report.update(status="FAIL", failure_stage=self.stage, failure_type=type(failure).__name__,
                          failure_message=str(failure))
            if self.stage == "old_live_process_stop":
                # Read-only bounded observations on the already guarded CI guest.
                # Keep the original failure even if diagnostic collection fails.
                for label, arguments in (("failure-logcat", ["shell", "logcat", "-d", "-t", "300"]),
                                          ("failure-activities", ["shell", "dumpsys", "activity", "activities"])):
                    try:
                        self.adb(arguments, label, timeout=10)
                    except Exception as diagnostic_failure:
                        report.setdefault("diagnostic_errors", {})[label] = type(diagnostic_failure).__name__
        finally:
            try:
                if worktree_attempted:
                    self.command(["git", "worktree", "remove", "--force", worktree], "old-worktree-remove")
                if temporary is not None:
                    shutil.rmtree(temporary)
            except Exception as failure:
                report.update(status="FAIL", worktree_cleanup_failed=True,
                              worktree_cleanup_failure_type=type(failure).__name__)
                if original_failure is None:
                    original_failure = failure
                    report.update(failure_stage="old_worktree_cleanup", failure_type=type(failure).__name__,
                                  failure_message=str(failure))
            (self.output / "summary.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        if original_failure is not None:
            raise original_failure
        print("PASS actual old-main schema2 to current schema6 APK upgrade; synthetic CI debug signer only")
        return report


def main():
    LegacyUpgradeDriver().execute()


if __name__ == "__main__":
    main()
