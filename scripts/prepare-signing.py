#!/usr/bin/env python3
"""Materialize a configured CI keystore in private runner temp; never print secrets."""
import base64
import os
from pathlib import Path
import re
import sys

def main():
    required=("XS_SIGNING_STORE_BASE64","XS_SIGNING_STORE_PASSWORD","XS_SIGNING_KEY_ALIAS",
              "XS_SIGNING_KEY_PASSWORD","XS_SIGNING_CERT_SHA256","RUNNER_TEMP","GITHUB_ENV")
    if any(not os.environ.get(name) for name in required):
        raise ValueError("Signing configuration is incomplete")
    fingerprint=os.environ["XS_SIGNING_CERT_SHA256"].replace(":","").lower()
    if not re.fullmatch(r"[0-9a-f]{64}",fingerprint):raise ValueError("Invalid public certificate fingerprint")
    encoded=os.environ["XS_SIGNING_STORE_BASE64"]
    if len(encoded)>2*1024*1024:raise ValueError("Keystore exceeds limit")
    data=base64.b64decode(encoded,validate=True)
    if not data or len(data)>1024*1024:raise ValueError("Invalid keystore size")
    folder=Path(os.environ["RUNNER_TEMP"])/"xs-private-signing"
    folder.mkdir(mode=0o700,exist_ok=True);os.chmod(folder,0o700)
    target=folder/"candidate.keystore"
    descriptor=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,"wb") as stream:stream.write(data)
    with open(os.environ["GITHUB_ENV"],"a",encoding="utf-8") as env:
        env.write("XS_SIGNING_STORE_FILE="+str(target)+"\n")
    print("Configured private signing input; no key material is included in artifacts")

if __name__=="__main__":
    try:main()
    except Exception:
        print("Signing setup failed: check configured secrets and public fingerprint",file=sys.stderr)
        sys.exit(1)
