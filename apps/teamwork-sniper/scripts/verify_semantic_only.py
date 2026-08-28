#!/usr/bin/env python3
from pathlib import Path
import re,sys
root=Path(__file__).resolve().parents[1]; source=root/'app'/'src'/'main'; tokens=('screencap','takescreenshot','mediaprojection','textrecognizer','bitmapfactory','pixelcopy','gesturedescription','dispatchgesture','getboundsinscreen');fail=[]
for p in source.rglob('*'):
    if p.suffix not in {'.kt','.java'}: continue
    t=p.read_text(encoding='utf-8'); low=t.lower()
    for token in tokens:
        if token in low: fail.append(f'{p.relative_to(root)} contains {token}')
    if re.search(r'\bocr\b',t,re.I): fail.append(f'{p.relative_to(root)} contains OCR')
if fail:
    print('Semantic-only guard FAILED');print('\n'.join('- '+x for x in fail));sys.exit(1)
print('Semantic-only guard PASS')
