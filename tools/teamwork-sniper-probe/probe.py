from __future__ import annotations

import argparse
import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, asdict
from datetime import date, datetime
from pathlib import Path
from typing import Iterable

OPEN_MARKER = "Open to take"
TIME_RE = re.compile(r"\b([01]\d|2[0-3]):[0-5]\d\b")
RANGE_RE = re.compile(r"\b([01]\d|2[0-3]):[0-5]\d\s*[-–—]\s*([01]\d|2[0-3]):[0-5]\d\b")
CODE_RE = re.compile(r"\b([MS]\d+)\b", re.IGNORECASE)
ISO_DATE_RE = re.compile(r"\b(20\d{2})-(\d{2})-(\d{2})\b")

@dataclass(frozen=True)
class Candidate:
    date: str | None
    code: str | None
    start: str | None
    end: str | None
    state: str
    clickable_path: str | None
    ambiguous: bool
    evidence: list[str]

def node_strings(node: ET.Element) -> list[str]:
    out: list[str] = []
    for key in ("text", "content-desc"):
        value = (node.attrib.get(key) or "").strip()
        if value:
            out.append(value)
    return out

def subtree_strings(node: ET.Element) -> list[str]:
    out: list[str] = []
    for child in node.iter():
        out.extend(node_strings(child))
    return out

def parent_map(root: ET.Element) -> dict[ET.Element, ET.Element]:
    return {child: parent for parent in root.iter() for child in parent}

def path_for(node: ET.Element, parents: dict[ET.Element, ET.Element]) -> str:
    parts = []
    cur: ET.Element | None = node
    while cur is not None:
        parts.append(cur.attrib.get("resource-id") or cur.attrib.get("class") or cur.tag)
        cur = parents.get(cur)
    return "/".join(reversed(parts))

def clickable_ancestor(node: ET.Element, parents: dict[ET.Element, ET.Element]) -> ET.Element | None:
    cur: ET.Element | None = node
    while cur is not None:
        if cur.attrib.get("clickable") == "true" and cur.attrib.get("enabled", "true") == "true":
            return cur
        cur = parents.get(cur)
    return None

def nearest_row_scope(marker: ET.Element, parents: dict[ET.Element, ET.Element]) -> ET.Element:
    """Return the smallest ancestor containing marker plus a plausible code/time token."""
    cur: ET.Element | None = marker
    fallback = marker
    while cur is not None:
        strings = subtree_strings(cur)
        blob = " | ".join(strings)
        if OPEN_MARKER.lower() in blob.lower():
            fallback = cur
            if CODE_RE.search(blob) and (RANGE_RE.search(blob) or len(TIME_RE.findall(blob)) >= 2):
                return cur
        cur = parents.get(cur)
    return fallback

def parse_date(strings: Iterable[str]) -> tuple[str | None, bool]:
    found = []
    for s in strings:
        for y, m, d in ISO_DATE_RE.findall(s):
            try:
                found.append(date(int(y), int(m), int(d)).isoformat())
            except ValueError:
                pass
    uniq = list(dict.fromkeys(found))
    return (uniq[0] if len(uniq) == 1 else None, len(uniq) != 1)

def parse_code(strings: Iterable[str]) -> tuple[str | None, bool]:
    found = []
    for s in strings:
        found.extend(x.upper() for x in CODE_RE.findall(s))
    uniq = list(dict.fromkeys(found))
    return (uniq[0] if len(uniq) == 1 else None, len(uniq) != 1)

def parse_times(strings: Iterable[str]) -> tuple[str | None, str | None, bool]:
    ranges = []
    for s in strings:
        ranges.extend(RANGE_RE.findall(s))
    uniq_ranges = list(dict.fromkeys(ranges))
    if len(uniq_ranges) == 1:
        return uniq_ranges[0][0], uniq_ranges[0][1], False
    flat = []
    for s in strings:
        flat.extend(TIME_RE.findall(s))
    uniq = list(dict.fromkeys(flat))
    if len(uniq) == 2:
        return uniq[0], uniq[1], False
    return None, None, True

def parse_xml(xml_text: str) -> list[Candidate]:
    root = ET.fromstring(xml_text)
    parents = parent_map(root)
    markers = [
        node for node in root.iter()
        if any(OPEN_MARKER.lower() in s.lower() for s in node_strings(node))
    ]
    results: list[Candidate] = []
    seen: set[tuple] = set()
    for marker in markers:
        scope = nearest_row_scope(marker, parents)
        strings = subtree_strings(scope)
        dt, amb_date = parse_date(strings)
        code, amb_code = parse_code(strings)
        start, end, amb_time = parse_times(strings)
        click = clickable_ancestor(marker, parents)
        candidate = Candidate(
            date=dt,
            code=code,
            start=start,
            end=end,
            state="OPEN_TO_TAKE",
            clickable_path=path_for(click, parents) if click is not None else None,
            ambiguous=amb_date or amb_code or amb_time or click is None,
            evidence=list(dict.fromkeys(strings)),
        )
        key = (candidate.date, candidate.code, candidate.start, candidate.end, candidate.state, candidate.clickable_path)
        if key not in seen:
            seen.add(key)
            results.append(candidate)
    return results

def normalized_fingerprint(candidates: Iterable[Candidate]) -> str:
    payload = [
        (c.date, c.code, c.start, c.end, c.state)
        for c in candidates
    ]
    return json.dumps(sorted(payload), separators=(",", ":"))

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("xml", type=Path)
    ap.add_argument("--fail-on-ambiguity", action="store_true")
    args = ap.parse_args()
    candidates = parse_xml(args.xml.read_text(encoding="utf-8"))
    print(json.dumps([asdict(x) for x in candidates], indent=2))
    if args.fail_on_ambiguity and any(x.ambiguous for x in candidates):
        return 2
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
