from __future__ import annotations
import argparse, re
from pathlib import Path
FORBIDDEN=(r'\bscreencap\b',r'\bMediaProjection\b',r'\btakeScreenshot\b',r'\bpytesseract\b',r'\beasyocr\b',r'\bPIL\b',r'\bopencv\b',r'\bcv2\b')
COORD=re.compile(r'\b(?:input\s+tap|tap|click)\s+\d{2,4}\s+\d{2,4}\b',re.I)
EXEC_SUFFIXES={'.py','.ps1','.sh','.bat','.cmd','.kt','.java'}

def scan(root:Path)->list[str]:
    violations=[]
    for p in root.rglob('*'):
        if not p.is_file() or p.suffix.lower() not in EXEC_SUFFIXES: continue
        text=p.read_text(encoding='utf-8',errors='ignore')
        for i,line in enumerate(text.splitlines(),1):
            if any(re.search(pat,line,re.I) for pat in FORBIDDEN): violations.append(f'{p}:{i}: forbidden mechanism')
            if COORD.search(line): violations.append(f'{p}:{i}: hardcoded click coordinates')
    return violations

def main()->int:
    ap=argparse.ArgumentParser(); ap.add_argument('root',nargs='?',type=Path,default=Path(__file__).resolve().parent); a=ap.parse_args(); v=scan(a.root)
    if v:
        print('\n'.join(v)); return 2
    print('PASS: executable Teamwork probe paths contain no forbidden capture/OCR/image mechanisms or hardcoded click coordinates.'); return 0
if __name__=='__main__': raise SystemExit(main())
