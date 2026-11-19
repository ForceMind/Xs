#!/usr/bin/env python3
"""Auditable source/notice manifest, not a copyright clearance or license transition."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    def git(*values):return subprocess.check_output(['git',*values],cwd=root)
    files=[];notices=[]
    for raw in git('ls-files','-z').split(b'\0'):
        if not raw:continue
        path=raw.decode('utf-8');file=root/path
        if not file.is_file():continue
        data=file.read_bytes();head=data[:16384].decode('utf-8','replace')
        hints=sorted(set(re.findall(r'SPDX-License-Identifier:\s*([^\r\n*]+)',head)))
        item={'path':path,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'spdx_header_hints':hints}
        files.append(item)
        if file.name.upper().startswith(('LICENSE','NOTICE','COPYING')):notices.append(path)
    report={'schema_version':1,'source_commit':git('rev-parse','HEAD').decode().strip(),
        'source_tree':git('rev-parse','HEAD^{tree}').decode().strip(),
        'working_tree_clean':not bool(git('status','--porcelain').strip()),
        'scope':'Tracked file hashes and detected notice/header locations only. No inference of ownership, third-party rights or custom relicensing clearance.',
        'operative_license':'Root LICENSE remains authoritative; historical grants and third-party notices are preserved.',
        'notices':notices,'files':files}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n')
    print('Recorded {} tracked files and {} notice locations; no relicensing claim'.format(len(files),len(notices)))

if __name__=='__main__':main()
