#!/usr/bin/env python3
"""Regression tests for Xs compatibility metadata; no third-party APK needed."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/verify-host-allowlist.py"
ORIGINAL = ROOT / "compat/host-allowlist.json"

class HostAllowlistTests(unittest.TestCase):
    def run_case(self, hosts):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "scripts").mkdir()
            (root / "compat").mkdir()
            (root / "scripts/verify-host-allowlist.py").write_text(SCRIPT.read_text())
            (root / "compat/host-allowlist.json").write_text(json.dumps({"schemaVersion": 1, "hosts": hosts}))
            return subprocess.run([sys.executable, str(root / "scripts/verify-host-allowlist.py")], capture_output=True, text=True)

    def sample(self):
        return dict(packageName="com.twitter.android", versionCode=123, signingSha256="a"*64, apkSha256="b"*64, verifiedOn="2026-10-27", evidence="https://example.org/verification")

    def test_default_empty_is_valid_and_fail_closed(self):
        self.assertEqual(self.run_case([]).returncode, 0)
        self.assertEqual(json.loads(ORIGINAL.read_text())["hosts"], [])

    def test_valid_metadata(self):
        self.assertEqual(self.run_case([self.sample()]).returncode, 0)

    def test_duplicate_identity_rejected(self):
        item = self.sample()
        self.assertNotEqual(self.run_case([item, item]).returncode, 0)

    def test_invalid_signature_rejected(self):
        item = self.sample()
        item["signingSha256"] = "not-a-digest"
        self.assertNotEqual(self.run_case([item]).returncode, 0)

    def test_unexpected_field_rejected(self):
        item = self.sample()
        item["accessToken"] = "secret"
        self.assertNotEqual(self.run_case([item]).returncode, 0)

    def test_invalid_version_rejected(self):
        item = self.sample()
        item["versionCode"] = True
        self.assertNotEqual(self.run_case([item]).returncode, 0)

if __name__ == "__main__":
    unittest.main()
