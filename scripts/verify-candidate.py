#!/usr/bin/env python3
"""Fail closed on a mismatched release signer; record only APK/public provenance."""
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk",type=Path)
    parser.add_argument("--apksigner",required=True)
    parser.add_argument("--aapt",required=True)
    parser.add_argument("--version-code",required=True,type=int)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    expected=os.environ.get("XS_SIGNING_CERT_SHA256","").replace(":","").lower()
    if not re.fullmatch(r"[0-9a-f]{64}",expected):raise ValueError()
    verified=subprocess.run([args.apksigner,"verify","--verbose","--print-certs",str(args.apk)],capture_output=True,text=True,timeout=30,check=True)
    actual=re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)",verified.stdout)
    if len(actual)!=1 or actual[0].lower()!=expected:raise ValueError()
    badging=subprocess.check_output([args.aapt,"dump","badging",str(args.apk)],text=True,timeout=30)
    identity=re.search(r"package: name='([^']+)' versionCode='([0-9]+)'",badging)
    if not identity or identity[1]!="dev.forcemind.xs" or int(identity[2])!=args.version_code or "application-debuggable" in badging:raise ValueError()
    def git(*values):return subprocess.check_output(["git",*values],text=True).strip()
    digest=hashlib.sha256()
    with args.apk.open("rb") as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b""):digest.update(chunk)
    record={"schema_version":1,"scope":"Manually requested signed candidate only; NOT production release or real-device acceptance",
        "recorded_at_utc":datetime.now(timezone.utc).isoformat(),"source_commit":git("rev-parse","HEAD"),
        "source_tree":git("rev-parse","HEAD^{tree}"),"working_tree_clean":not bool(git("status","--porcelain")),
        "apk":{"package":identity[1],"version_code":args.version_code,"debuggable":False,"bytes":args.apk.stat().st_size,"sha256":digest.hexdigest(),"certificate_sha256":expected,"signature_verified":True}}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(record,indent=2)+"\n")
    print("Candidate signature matches configured public certificate; provenance recorded")

if __name__=="__main__":
    try:main()
    except Exception:
        print("Candidate verification failed: missing input, signature mismatch or unavailable tooling",file=sys.stderr)
        sys.exit(1)
