#!/usr/bin/env python3
"""Host-only control-flow tests; never calls an emulator or changes a device."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("xs_process_driver", Path(__file__).parents[1] / "scripts/test-process-recovery.py")
driver = importlib.util.module_from_spec(spec)
spec.loader.exec_module(driver)
OLD_BOOT = "12345678-1234-1234-1234-123456789abc"
NEW_BOOT = "87654321-1234-1234-1234-cba987654321"


class AdbModel:
    def __init__(self, scenario="success"):
        self.scenario = scenario
        self.calls = []
        self.rebooted = False
        self.stopped = False

    def run(self, command, **kwargs):
        self.assert_command(command)
        args = command[1:]
        self.calls.append(args)
        output = ""
        if args == ["get-serialno"]:
            output = "physical-device" if self.scenario == "physical" else "emulator-5554"
        elif args == ["shell", "getprop", "ro.kernel.qemu"]:
            output = "1"
        elif args[:3] == ["shell", "am", "instrument"]:
            phase = args[args.index("xs_phase") + 1]
            output = "OK (1 test)\n" if phase == "seed" else "INSTRUMENTATION_STATUS: xs_verify_pid=1002\nOK (1 test)\n"
            if phase == "verify" and self.scenario == "verify-failure":
                output = "FAILURES!!!"
        elif args[:3] == ["shell", "am", "start"]:
            output = "Status: ok"
        elif args == ["shell", "pidof", driver.PACKAGE]:
            return subprocess.CompletedProcess(command, 1 if self.stopped else 0, "" if self.stopped else "1001", "")
        elif args[:3] == ["shell", "am", "force-stop"]:
            self.stopped = True
        elif args == ["shell", "cat", "/proc/sys/kernel/random/boot_id"]:
            output = NEW_BOOT if self.rebooted and self.scenario != "same-boot" else OLD_BOOT
        elif args == ["reboot"]:
            self.rebooted = True
        elif args == ["wait-for-device"]:
            if self.scenario in ("timeout", "diagnostic-timeout"):
                raise subprocess.TimeoutExpired(command, kwargs["timeout"], output=b"guest disconnected\n", stderr=b"transport unavailable\n")
        elif args == ["shell", "getprop", "sys.boot_completed"]:
            output = "1"
        elif args[:2] == ["-s", "emulator-5554"]:
            if self.scenario == "diagnostic-timeout":
                raise subprocess.TimeoutExpired(command, kwargs["timeout"])
            return subprocess.CompletedProcess(command, 1, "", "connection refused")
        else:
            raise AssertionError("Unexpected command " + repr(command))
        return subprocess.CompletedProcess(command, 0, output + ("\n" if output else ""), "")

    @staticmethod
    def assert_command(command):
        if not command or command[0] != "adb":
            raise AssertionError("No real subprocess is allowed")


class RecoveryDriverTests(unittest.TestCase):
    def execute(self, model):
        with tempfile.TemporaryDirectory(prefix="xs-reboot-host-") as directory:
            out = Path(directory) / "process-recovery"
            failure = None
            ticks = iter(range(0, 100000, 100))
            captured = io.StringIO()
            with patch.object(driver, "OUT", out), patch.object(driver.subprocess, "run", side_effect=model.run), \
                 patch.object(driver.time, "monotonic", side_effect=lambda: next(ticks)), patch.object(driver.time, "sleep"), \
                 patch("sys.argv", ["test-process-recovery.py", "--reboot"]), contextlib.redirect_stdout(captured):
                try:
                    driver.main()
                except (RuntimeError, subprocess.TimeoutExpired) as error:
                    failure = error
            result_dir = out.parent / "emulator-reboot"
            logs = {p.name: p.read_text() for p in result_dir.glob("*.txt")}
            return json.loads((result_dir / "summary.json").read_text()), failure, captured.getvalue(), logs

    def assert_not_verified(self, model, result):
        report, error, stdout, _ = result
        self.assertIsNotNone(error)
        self.assertEqual("FAIL", report["status"])
        self.assertFalse(report["recovery_verified"])
        self.assertNotIn("PASS", stdout)
        self.assertFalse(any("xs_phase" in args and args[args.index("xs_phase") + 1] == "verify" for args in model.calls))

    def test_actual_changed_boot_and_native_verification_are_both_required(self):
        model = AdbModel()
        report, error, stdout, logs = self.execute(model)
        self.assertIsNone(error)
        self.assertEqual("PASS", report["status"])
        self.assertTrue(report["reboot_requested"])
        self.assertTrue(report["recovery_verified"])
        self.assertNotEqual(report["previous_boot_id"], report["current_boot_id"])
        self.assertNotIn("failure-adb-state.txt", logs)
        self.assertIn("PASS", stdout)

    def test_timeout_retains_partial_output_and_diagnostics_without_retry_or_pass(self):
        model = AdbModel("timeout")
        result = self.execute(model)
        self.assert_not_verified(model, result)
        report, _, _, logs = result
        self.assertEqual("TimeoutExpired", report["failure"]["type"])
        self.assertIn("guest disconnected\ntransport unavailable", logs["reboot-device-available.txt"])
        self.assertIn("180 seconds", logs["reboot-device-available.txt"])
        self.assertIn("connection refused", logs["failure-emulator-console.txt"])
        self.assertEqual(1, model.calls.count(["reboot"]))
        self.assertFalse(any(args[:2] in (["kill-server"], ["start-server"] ) for args in model.calls))

    def test_failed_diagnostic_cannot_hide_original_reboot_timeout(self):
        model = AdbModel("diagnostic-timeout")
        result = self.execute(model)
        self.assert_not_verified(model, result)
        self.assertIn("180", result[0]["failure"]["message"])
        self.assertIn("5 seconds", result[3]["failure-adb-state.txt"])
        self.assertIn("5 seconds", result[3]["failure-emulator-console.txt"])

    def test_unchanged_kernel_cannot_reach_native_verify(self):
        model = AdbModel("same-boot")
        result = self.execute(model)
        self.assert_not_verified(model, result)
        self.assertEqual("Reboot did not complete", result[0]["failure"]["message"])

    def test_non_emulator_is_rejected_before_reboot_or_diagnostic(self):
        model = AdbModel("physical")
        result = self.execute(model)
        self.assert_not_verified(model, result)
        self.assertEqual([["get-serialno"]], model.calls)
        self.assertFalse(result[0]["reboot_requested"])

    def test_new_boot_alone_cannot_mask_native_recovery_failure(self):
        model = AdbModel("verify-failure")
        report, error, stdout, _ = self.execute(model)
        self.assertIsNotNone(error)
        self.assertEqual("FAIL", report["status"])
        self.assertFalse(report["recovery_verified"])
        self.assertIn("Recovery instrumentation did not pass", report["failure"]["message"])
        self.assertNotIn("PASS", stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
