#!/usr/bin/env python3
"""Validate the fail-closed Xs host compatibility allowlist (stdlib only)."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
data = json.loads((ROOT / "compat/host-allowlist.json").read_text(encoding="utf-8"))
assert data["schemaVersion"] == 1, "unsupported schema"
assert isinstance(data["hosts"], list), "hosts must be a list"
seen = set()
for host in data["hosts"]:
    assert set(host) == {"packageName", "versionCode", "signingSha256", "apkSha256", "verifiedOn", "evidence"}, "unexpected/missing host fields"
    assert re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+", host["packageName"])
    assert type(host["versionCode"]) is int and host["versionCode"] > 0
    for key in ("signingSha256", "apkSha256"):
        assert re.fullmatch(r"[0-9a-f]{64}", host[key]), f"invalid {key}"
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", host["verifiedOn"])
    assert isinstance(host["evidence"], str) and host["evidence"].startswith("https://")
    identity = (host["packageName"], host["versionCode"], host["signingSha256"])
    assert identity not in seen, "duplicate host identity"
    seen.add(identity)
print(f"Validated {len(seen)} authorized host build(s); empty list means no client integration permitted.")
