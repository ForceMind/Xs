#!/usr/bin/env python3
"""Host-only command/ordering failures; this never invokes adb, git, or Gradle."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("xs_legacy_driver", ROOT / "scripts/test-legacy-upgrade.py")
DRIVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRIVER)

CERT = "a" * 64
OTHER_CERT = "b" * 64
SCOPE = "local:12345678-1234-1234-1234-123456789abc"
DB_PATH = "/data/user/0/dev.forcemind.xs/databases/xs-local-history.db"


class Fixture:
    def __init__(self, root, scenario=""):
        self.root = root
        self.scenario = scenario
        self.commands = []
        self.version = None
        self.stopped = False
        self.launched = False

    def __call__(self, arguments, cwd, **kwargs):
        self.commands.append(arguments)
        command = arguments[0]
        code, output, error = 0, "", ""
        if command == "git":
            args = arguments[1:]
            old = Path(cwd).name == "old-source"
            if args[:2] == ["worktree", "add"]:
                path = Path(args[3]); path.mkdir()
                if self.scenario in ("worktree-add-failure", "worktree-both-fail"):
                    code = 7
            elif args[:2] == ["worktree", "remove"]:
                if self.scenario in ("worktree-cleanup-failure", "worktree-both-fail"):
                    code = 8
                else:
                    shutil.rmtree(Path(args[3]))
            elif args[0] == "status":
                output = " M owned.java\n" if self.scenario == "dirty-current" and not old else ""
            elif args[0] == "rev-parse":
                output = (("c" * 40) if "tree" in args[1] else DRIVER.OLD_COMMIT if old or args[1].startswith(DRIVER.OLD_COMMIT) else "d" * 40) + "\n"
            else:
                raise AssertionError(arguments)
        elif command == "gradle":
            path = Path(arguments[arguments.index("-p") + 1]) / DRIVER.APP_APK
            path.parent.mkdir(parents=True)
            path.write_bytes(b"real-old-apk-fixture-bytes")
        elif command.endswith("apksigner"):
            path = Path(arguments[-1])
            is_old = "old-source" in path.parts
            is_test = path == self.root / DRIVER.TEST_APK
            certificate = OTHER_CERT if ((self.scenario == "old-signature-mismatch" and is_old) or
                                          (self.scenario == "test-signature-mismatch" and is_test)) else CERT
            output = "Signer #1 certificate SHA-256 digest: " + certificate + "\n"
        elif command.endswith("aapt"):
            path = Path(arguments[-1]); old = "old-source" in path.parts
            test = path == self.root / DRIVER.TEST_APK
            version = 1 if old else "" if test else 3
            if self.scenario == "wrong-current-apk-version" and not old and not test:
                version = 4
            if self.scenario == "blank-current-apk-version" and not old and not test:
                version = ""
            if self.scenario == "malformed-old-apk-version" and old:
                version = "unknown"
            if self.scenario == "malformed-test-apk-version" and test:
                version = "unknown"
            package = DRIVER.PACKAGE + (".test" if test else "")
            output = "package: name='" + package + "' versionCode='" + str(version) + "'\n"
        elif command == "adb":
            args = arguments[1:]
            if args == ["get-serialno"]:
                output = "phone-serial\n" if self.scenario == "real-device" else "emulator-5554\n"
            elif args == ["shell", "getprop", "ro.kernel.qemu"]:
                output = "0\n" if self.scenario == "non-qemu" else "1\n"
            elif args == ["shell", "id", "-u"]:
                output = "0\n" if self.scenario == "root-shell" else "2000\n"
            elif args[:4] == ["shell", "pm", "list", "packages"]:
                if "-U" not in args:
                    output = "package:" + DRIVER.PACKAGE + "\n" if self.scenario == "installed-target" else ""
                else:
                    uid = 10083 if self.version == 3 and self.scenario == "installed-uid-change" else 10082
                    output = "package:" + DRIVER.PACKAGE + " uid:" + str(uid) + "\n"
            elif args[0] == "install":
                path = Path(args[-1])
                if path != self.root / DRIVER.TEST_APK:
                    self.version = 1 if "old-source" in path.parts else 3
                output = "Success\n"
            elif args[:3] == ["shell", "dumpsys", "package"]:
                output = "Package [dev.forcemind.xs]:\n  versionCode=" + str(self.version) + " minSdk=26\n"
            elif args == ["shell", "pm", "list", "instrumentation"]:
                output = "instrumentation:" + DRIVER.COMPONENT + " (target=" + DRIVER.PACKAGE + ")\n"
            elif args[:3] == ["shell", "am", "instrument"]:
                phase = args[args.index("xs_legacy_phase") + 1]
                if self.scenario == phase + "-failure":
                    output = "FAILURES!!!\nINSTRUMENTATION_FAILED\n"
                elif phase == "cleanup":
                    output = "OK (1 test)\n"
                else:
                    values = {"schema": 2 if phase == "seed" else 6, "uid": 10082,
                              "pid": 1001 if phase == "seed" else 1003,
                              "db_path": DB_PATH, "scope": SCOPE}
                    if self.scenario == "wrong-seed-schema" and phase == "seed": values["schema"] = 5
                    if self.scenario == "wrong-verify-schema" and phase == "verify": values["schema"] = 2
                    if self.scenario == "native-uid-change" and phase == "verify": values["uid"] = 10083
                    if self.scenario == "native-pid-reused" and phase == "verify": values["pid"] = 1002
                    if self.scenario == "native-path-change" and phase == "verify": values["db_path"] = DB_PATH.replace("/user/0", "/data")
                    if self.scenario == "native-scope-change" and phase == "verify": values["scope"] = SCOPE.replace("12345678", "12345679")
                    lines = ["INSTRUMENTATION_STATUS: xs_legacy_" + k + "=" + str(v) for k, v in values.items()]
                    runner = re.fullmatch(r"runner-prefix:(seed|verify):(schema|uid|pid|db_path|scope):(raw|space|crlf)", self.scenario)
                    if runner and phase == runner[1]:
                        first = "INSTRUMENTATION_STATUS: xs_legacy_" + runner[2] + "=" + str(values[runner[2]])
                        lines.remove(first)
                        lines.insert(0, DRIVER.TEST_CLASS + ":" + (" " if runner[3] == "space" else "") + first)
                    duplicate = re.fullmatch(r"duplicate-status:(seed|verify):(plain|prefixed):(plain|prefixed)", self.scenario)
                    if duplicate and phase == duplicate[1]:
                        first = "INSTRUMENTATION_STATUS: xs_legacy_uid=" + str(values["uid"])
                        lines.remove(first)
                        lines.insert(0, (DRIVER.TEST_CLASS + ":" if duplicate[2] == "prefixed" else "") + first)
                        lines.append((DRIVER.TEST_CLASS + ": " if duplicate[3] == "prefixed" else "") + first)
                    foreign = re.fullmatch(r"foreign-prefix:(seed|verify):(wrong-class|embedded|log)", self.scenario)
                    if foreign and phase == foreign[1]:
                        first = "INSTRUMENTATION_STATUS: xs_legacy_pid=" + str(values["pid"])
                        lines.remove(first)
                        prefix = {"wrong-class": "dev.forcemind.xs.android.OtherProbe:", "embedded": "ordinary log " + DRIVER.TEST_CLASS + ":", "log": "ordinary log: "}[foreign[2]]
                        lines.insert(0, prefix + first)
                    newline = "\r\n" if runner and runner[3] == "crlf" else "\n"
                    output = newline.join(lines) + newline + "OK (1 test)" + newline
            elif args[:3] == ["shell", "am", "start"]:
                self.stopped = False
                self.launched = True
                output = "Status: ok\n"
            elif args[:3] == ["shell", "am", "force-stop"]:
                self.stopped = True
            elif args == ["shell", "pidof", DRIVER.PACKAGE]:
                if self.stopped:
                    if (self.scenario == "stop-not-absent" and self.launched) or self.scenario == "seed-stop-not-absent": output = "1002\n"
                    else: code = 1
                elif self.scenario == "foreground-missing": code = 1
                else: output = "1001\n" if self.scenario == "foreground-is-seed" else "1002\n"
            elif args[:2] == ["shell", "logcat"] or args == ["shell", "dumpsys", "activity", "activities"]:
                output = "bounded fixture diagnostic\n"
            else:
                raise AssertionError(arguments)
        else:
            raise AssertionError(arguments)
        return subprocess.CompletedProcess(arguments, code, output, error)

    def phases(self):
        return [x[x.index("xs_legacy_phase") + 1] for x in self.commands if "xs_legacy_phase" in x]


class LegacyUpgradeDriverTests(unittest.TestCase):
    def exercise(self, scenario="", environment=None, expected_failure=True):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); (root / DRIVER.APP_APK).parent.mkdir(parents=True)
            (root / DRIVER.APP_APK).write_bytes(b"unchanged-current-app")
            (root / DRIVER.TEST_APK).parent.mkdir(parents=True)
            (root / DRIVER.TEST_APK).write_bytes(b"unchanged-current-test")
            fixture = Fixture(root, scenario)
            env = {"GITHUB_ACTIONS": "true", "ANDROID_HOME": "/synthetic-sdk", "RUNNER_TEMP": str(root)}
            if environment is not None: env.update(environment)
            driver = DRIVER.LegacyUpgradeDriver(root=root, environment=env, executor=fixture)
            failure = None
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    driver.execute()
            except Exception as problem:
                failure = problem
            self.assertEqual(expected_failure, failure is not None, str(failure))
            report = json.loads((root / DRIVER.OUT / "summary.json").read_text())
            self.assertEqual("FAIL" if expected_failure else "PASS", report["status"])
            self.assertEqual(b"unchanged-current-app", (root / DRIVER.APP_APK).read_bytes())
            self.assertEqual(b"unchanged-current-test", (root / DRIVER.TEST_APK).read_bytes())
            for command in fixture.commands:
                self.assertNotIn("uninstall", command)
                self.assertNotIn("clear", command)
                if command[:2] == ["adb", "install"]:
                    self.assertNotIn("-d", command)  # no downgrade; logcat -d is read-only
            return fixture, report, failure

    def test_success_is_actual_ordered_upgrade_and_owned_cleanup(self):
        fixture, report, _ = self.exercise(expected_failure=False)
        self.assertEqual(["seed", "verify", "cleanup"], fixture.phases())
        installs = [x for x in fixture.commands if x[:2] == ["adb", "install"]]
        self.assertEqual(3, len(installs))
        self.assertTrue(all(x[2:4] == ["-r", "-t"] for x in installs))
        self.assertEqual([2, 6], [report["seed"]["schema"], report["verify"]["schema"]])
        self.assertEqual(10082, report["installed_current"]["uid"])
        self.assertTrue(report["current_build_outputs_preserved"])
        self.assertIsNone(report["apk_artifacts"][2]["version_code"])
        self.assertEqual(3, report["external_native_probe_invocations"])

    def test_global_guards_reject_before_mutation(self):
        for scenario, environment in (("", {"GITHUB_ACTIONS": "false"}), ("real-device", {}), ("non-qemu", {}), ("root-shell", {}), ("installed-target", {})):
            with self.subTest(scenario=scenario, environment=environment):
                fixture, _, _ = self.exercise(scenario, environment)
                self.assertFalse(any(x[:3] == ["git", "worktree", "add"] or x[:2] == ["adb", "install"] for x in fixture.commands))
                self.assertEqual([], fixture.phases())

    def test_exact_android_runner_prefix_supports_every_first_field_in_both_phases(self):
        # CI76 API26 actually puts pid first; API27/30/36 put db_path first.
        # Bundle order is not a contract, so all five fields and both phases matter.
        for phase in ("seed", "verify"):
            for key in ("pid", "db_path", "schema", "uid", "scope"):
                for framing in ("raw", "space", "crlf"):
                    with self.subTest(phase=phase, first=key, framing=framing):
                        fixture, report, _ = self.exercise("runner-prefix:" + phase + ":" + key + ":" + framing, expected_failure=False)
                        self.assertEqual(["seed", "verify", "cleanup"], fixture.phases())
                        self.assertEqual([2, 6], [report["seed"]["schema"], report["verify"]["schema"]])

    def test_duplicate_plain_or_exact_prefixed_status_remains_rejected(self):
        for phase in ("seed", "verify"):
            for first in ("plain", "prefixed"):
                for second in ("plain", "prefixed"):
                    with self.subTest(phase=phase, first=first, second=second):
                        fixture, _, failure = self.exercise("duplicate-status:" + phase + ":" + first + ":" + second)
                        self.assertIn("Native status not unambiguously proven: " + phase + "/uid", str(failure))
                        self.assertNotIn("cleanup", fixture.phases())

    def test_arbitrary_or_wrong_class_status_prefix_is_not_accepted(self):
        for phase in ("seed", "verify"):
            for prefix in ("wrong-class", "embedded", "log"):
                with self.subTest(phase=phase, prefix=prefix):
                    fixture, _, failure = self.exercise("foreign-prefix:" + phase + ":" + prefix)
                    self.assertIn("Native status not unambiguously proven: " + phase + "/pid", str(failure))
                    self.assertNotIn("cleanup", fixture.phases())

    def test_signature_version_and_source_mismatch_prevent_install(self):
        for scenario in ("old-signature-mismatch", "test-signature-mismatch", "wrong-current-apk-version", "blank-current-apk-version", "malformed-old-apk-version", "malformed-test-apk-version", "dirty-current"):
            with self.subTest(scenario=scenario):
                fixture, _, _ = self.exercise(scenario)
                self.assertFalse(any(x[:2] == ["adb", "install"] for x in fixture.commands))

    def test_seed_must_run_old_schema_before_upgrade(self):
        for scenario in ("seed-failure", "wrong-seed-schema"):
            with self.subTest(scenario=scenario):
                fixture, _, _ = self.exercise(scenario)
                self.assertEqual(["seed"], fixture.phases())
                self.assertEqual(1, fixture.version)

    def test_force_stop_absence_is_verified(self):
        fixture, _, _ = self.exercise("stop-not-absent")
        self.assertEqual(["seed"], fixture.phases())
        self.assertEqual(1, fixture.version)

    def test_seed_is_stopped_before_independent_old_foreground_launch(self):
        fixture, report, _ = self.exercise(expected_failure=False)
        stops = [i for i, x in enumerate(fixture.commands) if x[:4] == ["adb", "shell", "am", "force-stop"]]
        launch = next(i for i, x in enumerate(fixture.commands) if x[:4] == ["adb", "shell", "am", "start"])
        self.assertEqual(2, len(stops));self.assertLess(stops[0], launch);self.assertLess(launch, stops[1])
        self.assertIn("old_seed_process_stopped_and_absent", report["phases"])

    def test_seed_absence_is_required_before_launch(self):
        fixture, _, _ = self.exercise("seed-stop-not-absent")
        self.assertFalse(any(x[:4] == ["adb", "shell", "am", "start"] for x in fixture.commands))

    def test_missing_or_reused_foreground_pid_keeps_failure_and_diagnostics(self):
        for scenario in ("foreground-missing", "foreground-is-seed"):
            with self.subTest(scenario=scenario):
                fixture, report, _ = self.exercise(scenario)
                self.assertEqual("old_live_process_stop", report["failure_stage"])
                self.assertEqual(["seed"], fixture.phases())
                self.assertTrue(any(x[:3] == ["adb", "shell", "logcat"] for x in fixture.commands))

    def test_installed_uid_change_rejects_before_native_verify(self):
        fixture, _, _ = self.exercise("installed-uid-change")
        self.assertEqual(["seed"], fixture.phases())

    def test_native_verify_must_keep_schema_uid_path_scope_and_new_pid(self):
        for scenario in ("wrong-verify-schema", "native-uid-change", "native-pid-reused", "native-path-change", "native-scope-change", "verify-failure"):
            with self.subTest(scenario=scenario):
                fixture, report, _ = self.exercise(scenario)
                self.assertEqual(["seed", "verify"], fixture.phases())
                self.assertEqual("native_schema6_verification", report["failure_stage"])

    def test_native_cleanup_failure_cannot_report_pass(self):
        fixture, report, _ = self.exercise("cleanup-failure")
        self.assertEqual(["seed", "verify", "cleanup"], fixture.phases())
        self.assertEqual("owned_fixture_cleanup", report["failure_stage"])

    def test_worktree_cleanup_failure_cannot_report_pass(self):
        _, report, failure = self.exercise("worktree-cleanup-failure")
        self.assertTrue(report["worktree_cleanup_failed"])
        self.assertEqual("old_worktree_cleanup", report["failure_stage"])
        self.assertEqual("old-worktree-remove", failure.operation)

    def test_worktree_cleanup_failure_preserves_original_failure(self):
        fixture, report, failure = self.exercise("worktree-both-fail")
        self.assertTrue(report["worktree_cleanup_failed"])
        self.assertEqual("old_source_build", report["failure_stage"])
        self.assertEqual("old-worktree-add", failure.operation)
        self.assertEqual([], fixture.phases())


if __name__ == "__main__":
    unittest.main(verbosity=2)
