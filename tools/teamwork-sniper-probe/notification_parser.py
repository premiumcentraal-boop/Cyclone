from __future__ import annotations
import argparse, json, re
from pathlib import Path
PACKAGE='tech.picnic.workapp'

def parse_notification_dump(text:str, package:str=PACKAGE)->list[dict]:
    blocks=re.split(r'(?=NotificationRecord\()', text)
    out=[]
    for block in blocks:
        if package not in block:
            continue
        def grab(pattern):
            m=re.search(pattern, block, re.I|re.M)
            return m.group(1).strip() if m else None
        pkg=grab(r'pkg=([^\s,]+)') or package
        channel=grab(r'(?:channelId|channel)=([^\s,}\]]+)')
        title=grab(r'(?:android\.title|title)=([^\n]+)')
        body=grab(r'(?:android\.text|text)=([^\n]+)')
        post=grab(r'(?:postTime|when)=([^\n,]+)')
        content_intent=bool(re.search(r'contentIntent\s*=\s*(?!null|None)', block, re.I))
        out.append({'package':pkg,'channel':channel,'title':title,'text':body,'postTime':post,'contentIntentPresent':content_intent})
    return out

def main()->int:
    ap=argparse.ArgumentParser(); ap.add_argument('dump',type=Path); ap.add_argument('--package',default=PACKAGE); a=ap.parse_args()
    print(json.dumps(parse_notification_dump(a.dump.read_text(encoding='utf-8',errors='replace'),a.package),indent=2)); return 0
if __name__=='__main__': raise SystemExit(main())
