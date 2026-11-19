#!/usr/bin/env python3
"""Execute Java-generated MATCH terms on the available SDK SQLite CLI, recording its syntax.

The independent Android FtsCompatibilityIntegrationTest is required even when no host CLI exists.
Use --output to retain exact binary hash/version/compile_options and the old/new query results.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument("--classpath",required=True)
parser.add_argument("--sqlite")
parser.add_argument("--output",type=Path)
args=parser.parse_args()
candidates=[args.sqlite]
for key in ("ANDROID_HOME","ANDROID_SDK_ROOT"):
    if os.environ.get(key):candidates.append(str(Path(os.environ[key])/"platform-tools/sqlite3"))
candidates.append(shutil.which("sqlite3"))
command=next((p for p in candidates if p and Path(p).is_file() and os.access(p,os.X_OK)),None)
if not command:
    print("NOT RUN host FTS4 CLI: unavailable; native Android FtsCompatibilityIntegrationTest remains required")
    raise SystemExit(0)
export=subprocess.run(["java","-Xmx512m","-cp",args.classpath,"dev.forcemind.xs.tests.FtsCompatibilityDump"],check=True,text=True,capture_output=True)
terms={}
for line in export.stdout.splitlines():
    name,_,encoded=line.partition(":");terms.setdefault(name,[]).append(base64.b64decode(encoded).decode())
assert set(terms)=={"current","history","versions","literalOperator"}
def sql_quote(s):return "'"+s.replace("'","''")+"'"
statements=["CREATE VIRTUAL TABLE docs USING fts4(body)","INSERT INTO docs VALUES ('native resource fixture')",
            "SELECT 'oldExplicitAnd|'||count(*) FROM docs WHERE docs MATCH 'native* AND resource*'",
            "SELECT 'implicitAnd|'||count(*) FROM docs WHERE docs MATCH 'native* resource*'"]
for name,values in terms.items():
    for index,value in enumerate(values):
        statements.append("SELECT "+sql_quote(name+str(index)+"|")+"||count(*) FROM docs WHERE docs MATCH "+sql_quote(value))
output=subprocess.run([command,":memory:",";".join(statements)+";"],check=True,text=True,capture_output=True).stdout
counts={name:int(value) for name,value in (line.split("|",1) for line in output.splitlines())}
assert counts["implicitAnd"]==1
assert all(counts[name+str(i)]==(0 if name=="literalOperator" else 1) for name,values in terms.items() for i in range(len(values)))
version=subprocess.run([command,"--version"],check=True,text=True,capture_output=True).stdout.strip()
options=subprocess.run([command,":memory:","PRAGMA compile_options;"],check=True,text=True,capture_output=True).stdout.splitlines()
result={"result":"PASS","binary":str(Path(command).resolve()),"binarySha256":hashlib.sha256(Path(command).read_bytes()).hexdigest(),
        "sqliteVersion":version,"compileOptions":options,"oldExplicitAndFailed":counts["oldExplicitAnd"]==0,
        "note":"An empty compile_options result means this CLI does not expose those diagnostics; query behavior is measured directly.",
        "javaGeneratedTerms":terms,"counts":counts}
rendered=json.dumps(result,indent=2)
if args.output:args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(rendered+"\n")
print(rendered)
