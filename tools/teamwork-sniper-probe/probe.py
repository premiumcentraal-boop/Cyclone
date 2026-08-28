from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from datetime import date
from pathlib import Path
from typing import Iterable, Sequence

EVIDENCE_LEVELS = {"LIVE_CONFIRMED", "PROVISIONAL", "SYNTHETIC_ONLY"}
OPEN_MARKERS = ("open to take",)
CODE_RE = re.compile(r"(?<![A-Za-z0-9])([MS]\d+)(?![A-Za-z0-9])", re.I)
TIME_RE = re.compile(r"(?<!\d)((?:[01]\d|2[0-3]):[0-5]\d)(?!\d)")
RANGE_RE = re.compile(r"(?<!\d)((?:[01]\d|2[0-3]):[0-5]\d)\s*(?:[-–—]|\bto\b|\btot\b)\s*((?:[01]\d|2[0-3]):[0-5]\d)(?!\d)", re.I)
ISO_RE = re.compile(r"(?<!\d)(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)")
DMY_RE = re.compile(r"(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](20\d{2})(?!\d)")

MONTHS = {
    "jan": 1, "january": 1, "januari": 1,
    "feb": 2, "february": 2, "februari": 2,
    "mar": 3, "march": 3, "mrt": 3, "maart": 3,
    "apr": 4, "april": 4,
    "may": 5, "mei": 5,
    "jun": 6, "june": 6, "juni": 6,
    "jul": 7, "july": 7, "juli": 7,
    "aug": 8, "august": 8, "augustus": 8,
    "sep": 9, "sept": 9, "september": 9,
    "oct": 10, "okt": 10, "october": 10, "oktober": 10,
    "nov": 11, "november": 11,
    "dec": 12, "december": 12,
}
MONTH_ALT = "|".join(sorted(map(re.escape, MONTHS), key=len, reverse=True))
DMONTHY_RE = re.compile(rf"(?<!\d)(\d{{1,2}})\s+({MONTH_ALT})\s+(20\d{{2}})(?!\d)", re.I)
MONTHDY_RE = re.compile(rf"\b({MONTH_ALT})\s+(\d{{1,2}})(?:st|nd|rd|th)?[,]?\s+(20\d{{2}})\b", re.I)
DMONTH_RE = re.compile(rf"(?<!\d)(\d{{1,2}})\s+({MONTH_ALT})(?![A-Za-z])", re.I)
WEEKDAY_RE = re.compile(r"\b(mon(?:day)?|tue(?:sday)?|wed(?:nesday)?|thu(?:rsday)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?|ma(?:andag)?|di(?:nsdag)?|wo(?:ensdag)?|do(?:nderdag)?|vr(?:ijdag)?|za(?:terdag)?|zo(?:ndag)?)\b", re.I)
DAY_MAP = {
    "mon": "MON", "monday": "MON", "ma": "MON", "maandag": "MON",
    "tue": "TUE", "tuesday": "TUE", "di": "TUE", "dinsdag": "TUE",
    "wed": "WED", "wednesday": "WED", "wo": "WED", "woensdag": "WED",
    "thu": "THU", "thursday": "THU", "do": "THU", "donderdag": "THU",
    "fri": "FRI", "friday": "FRI", "vr": "FRI", "vrijdag": "FRI",
    "sat": "SAT", "saturday": "SAT", "za": "SAT", "zaterdag": "SAT",
    "sun": "SUN", "sunday": "SUN", "zo": "SUN", "zondag": "SUN",
}

@dataclass(frozen=True)
class Candidate:
    date: str | None
    day: str | None
    code: str | None
    start: str | None
    end: str | None
    state: str
    semanticRowIdentity: str
    claimCandidatePath: str | None
    confidence: str
    ambiguity: list[str]
    evidenceLevel: str
    evidence: list[str]

    @property
    def ambiguous(self) -> bool:
        return bool(self.ambiguity)

def node_strings(node: ET.Element) -> list[str]:
    out = []
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

def flatten(root: ET.Element) -> list[ET.Element]:
    return list(root.iter())

def path_for(node: ET.Element, parents: dict[ET.Element, ET.Element]) -> str:
    parts: list[str] = []
    cur: ET.Element | None = node
    while cur is not None:
        label = cur.attrib.get("resource-id") or cur.attrib.get("class") or cur.tag
        parts.append(label)
        cur = parents.get(cur)
    return "/".join(reversed(parts))

def clickable_ancestor(node: ET.Element, parents: dict[ET.Element, ET.Element]) -> ET.Element | None:
    cur: ET.Element | None = node
    while cur is not None:
        if cur.attrib.get("clickable") == "true" and cur.attrib.get("enabled", "true") == "true":
            return cur
        cur = parents.get(cur)
    return None

def _safe_date(y: int, m: int, d: int) -> str | None:
    try:
        return date(y, m, d).isoformat()
    except ValueError:
        return None

def extract_dates(strings: Iterable[str], *, year_anchor: int | None = None) -> list[str]:
    found: list[str] = []
    for s in strings:
        for y, m, d in ISO_RE.findall(s):
            v = _safe_date(int(y), int(m), int(d))
            if v: found.append(v)
        for d, m, y in DMY_RE.findall(s):
            v = _safe_date(int(y), int(m), int(d))
            if v: found.append(v)
        for d, mon, y in DMONTHY_RE.findall(s):
            v = _safe_date(int(y), MONTHS[mon.lower()], int(d))
            if v: found.append(v)
        for mon, d, y in MONTHDY_RE.findall(s):
            v = _safe_date(int(y), MONTHS[mon.lower()], int(d))
            if v: found.append(v)
        if year_anchor is not None:
            for d, mon in DMONTH_RE.findall(s):
                v = _safe_date(year_anchor, MONTHS[mon.lower()], int(d))
                if v: found.append(v)
    return list(dict.fromkeys(found))

def extract_days(strings: Iterable[str]) -> list[str]:
    found = []
    for s in strings:
        for token in WEEKDAY_RE.findall(s):
            mapped = DAY_MAP.get(token.lower())
            if mapped:
                found.append(mapped)
    return list(dict.fromkeys(found))

def extract_codes(strings: Iterable[str]) -> list[str]:
    found = []
    for s in strings:
        found.extend(x.upper() for x in CODE_RE.findall(s))
    return list(dict.fromkeys(found))

def extract_time_pairs(strings: Iterable[str]) -> list[tuple[str, str]]:
    ranges: list[tuple[str, str]] = []
    for s in strings:
        ranges.extend(RANGE_RE.findall(s))
    uniq_ranges = list(dict.fromkeys(ranges))
    if uniq_ranges:
        return uniq_ranges
    times: list[str] = []
    for s in strings:
        times.extend(TIME_RE.findall(s))
    uniq = list(dict.fromkeys(times))
    if len(uniq) == 2:
        return [(uniq[0], uniq[1])]
    return []

def tree_year_anchor(root: ET.Element) -> int | None:
    years = set()
    for s in subtree_strings(root):
        years.update(int(y) for y in re.findall(r"\b(20\d{2})\b", s))
    return next(iter(years)) if len(years) == 1 else None

def previous_sticky_context(marker: ET.Element, root: ET.Element, *, year_anchor: int | None) -> tuple[list[str], list[str]]:
    nodes = flatten(root)
    try:
        idx = nodes.index(marker)
    except ValueError:
        return [], []
    nearest_dates: list[str] = []
    nearest_days: list[str] = []
    for node in reversed(nodes[:idx]):
        strings = node_strings(node)
        dates = extract_dates(strings, year_anchor=year_anchor)
        days = extract_days(strings)
        if dates and not nearest_dates:
            nearest_dates = dates
        if days and not nearest_days:
            nearest_days = days
        if nearest_dates and nearest_days:
            break
    return nearest_dates, nearest_days

def candidate_scopes(marker: ET.Element, parents: dict[ET.Element, ET.Element]) -> list[ET.Element]:
    scopes = []
    cur: ET.Element | None = marker
    while cur is not None:
        strings = subtree_strings(cur)
        blob = " | ".join(strings).lower()
        if any(m in blob for m in OPEN_MARKERS) and extract_codes(strings) and extract_time_pairs(strings):
            scopes.append(cur)
        cur = parents.get(cur)
    return scopes

def semantic_identity(scope: ET.Element, strings: Sequence[str], parents: dict[ET.Element, ET.Element]) -> str:
    payload = {
        "path": path_for(scope, parents),
        "resource": scope.attrib.get("resource-id", ""),
        "class": scope.attrib.get("class", ""),
        "strings": list(dict.fromkeys(strings)),
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()[:20]

def parse_xml(xml_text: str, *, evidence_level: str = "PROVISIONAL") -> list[Candidate]:
    if evidence_level not in EVIDENCE_LEVELS:
        raise ValueError(f"unknown evidence level: {evidence_level}")
    root = ET.fromstring(xml_text)
    parents = parent_map(root)
    year_anchor = tree_year_anchor(root)
    markers = [n for n in root.iter() if any(m in s.lower() for s in node_strings(n) for m in OPEN_MARKERS)]
    out: list[Candidate] = []
    seen: set[tuple] = set()
    for marker in markers:
        scopes = candidate_scopes(marker, parents)
        ambiguity: list[str] = []
        if not scopes:
            scopes = [marker]
            ambiguity.append("NO_ROW_SCOPE")
        scope = scopes[0]
        strings = subtree_strings(scope)
        codes = extract_codes(strings)
        pairs = extract_time_pairs(strings)
        dates = extract_dates(strings, year_anchor=year_anchor)
        days = extract_days(strings)
        if not dates:
            sticky_dates, sticky_days = previous_sticky_context(marker, root, year_anchor=year_anchor)
            dates = sticky_dates
            if not days:
                days = sticky_days
        if len(codes) != 1: ambiguity.append("CODE_AMBIGUOUS")
        if len(pairs) != 1: ambiguity.append("TIME_AMBIGUOUS")
        if len(dates) != 1: ambiguity.append("DATE_AMBIGUOUS")
        if len(days) > 1: ambiguity.append("DAY_AMBIGUOUS")
        click = clickable_ancestor(marker, parents)
        if click is None:
            ambiguity.append("NO_CLICKABLE_ANCESTOR")
        if len(scopes) > 1 and (len(codes) != 1 or len(pairs) != 1):
            ambiguity.append("OVERLAPPING_SCOPE_AMBIGUOUS")
        code = codes[0] if len(codes) == 1 else None
        pair = pairs[0] if len(pairs) == 1 else (None, None)
        dt = dates[0] if len(dates) == 1 else None
        day = days[0] if len(days) == 1 else None
        candidate = Candidate(
            date=dt, day=day, code=code, start=pair[0], end=pair[1], state="OPEN_TO_TAKE",
            semanticRowIdentity=semantic_identity(scope, strings, parents),
            claimCandidatePath=path_for(click, parents) if click is not None else None,
            confidence="UNAMBIGUOUS" if not ambiguity else "AMBIGUOUS",
            ambiguity=list(dict.fromkeys(ambiguity)), evidenceLevel=evidence_level,
            evidence=list(dict.fromkeys(strings)),
        )
        key = (candidate.date, candidate.day, candidate.code, candidate.start, candidate.end, candidate.state, candidate.claimCandidatePath)
        if key not in seen:
            seen.add(key)
            out.append(candidate)
    return out

def normalized_key(c: Candidate) -> tuple:
    return (c.date, c.code, c.start, c.end, c.state)

def semantic_fingerprint(candidates: Iterable[Candidate]) -> str:
    rows = sorted(normalized_key(c) + (tuple(c.ambiguity),) for c in candidates)
    return hashlib.sha256(json.dumps(rows, separators=(",", ":")).encode()).hexdigest()

def aggregate_xml_texts(items: Sequence[tuple[str, str]], *, evidence_level: str = "PROVISIONAL") -> dict:
    shifts: dict[tuple, dict] = {}
    pages = []
    previous_keys: set[tuple] = set()
    previous_fp: str | None = None
    stable = False
    for page_index, (name, xml_text) in enumerate(items, start=1):
        parsed = parse_xml(xml_text, evidence_level=evidence_level)
        fp = semantic_fingerprint(parsed)
        keys = {normalized_key(c) for c in parsed if not c.ambiguous}
        new = keys - previous_keys
        pages.append({"page": page_index, "source": name, "new": len(new), "fingerprint": fp, "candidates": len(parsed)})
        for c in parsed:
            key = normalized_key(c)
            record = shifts.setdefault(key, {**asdict(c), "pages": []})
            if page_index not in record["pages"]:
                record["pages"].append(page_index)
            if c.ambiguous:
                record["confidence"] = "AMBIGUOUS"
                record["ambiguity"] = sorted(set(record.get("ambiguity", [])) | set(c.ambiguity))
        stable = page_index > 1 and not new and fp == previous_fp
        previous_keys |= keys
        previous_fp = fp
    return {"shifts": list(shifts.values()), "pages": len(items), "newPerPage": [p["new"] for p in pages], "pageDetails": pages, "stable": stable}

def benchmark(paths: Sequence[Path], repeat: int = 50) -> dict:
    t0 = time.perf_counter()
    texts = [(str(p), p.read_text(encoding="utf-8")) for p in paths]
    load_ms = (time.perf_counter() - t0) * 1000
    parse_times = []
    aggregate_times = []
    for _ in range(repeat):
        t = time.perf_counter()
        parsed = [parse_xml(x, evidence_level="SYNTHETIC_ONLY") for _, x in texts]
        parse_times.append((time.perf_counter() - t) * 1000)
        t = time.perf_counter()
        aggregate_xml_texts(texts, evidence_level="SYNTHETIC_ONLY")
        aggregate_times.append((time.perf_counter() - t) * 1000)
    normalize_t = time.perf_counter()
    _ = [normalized_key(c) for page in parsed for c in page]
    normalize_ms = (time.perf_counter() - normalize_t) * 1000
    dedup_t = time.perf_counter()
    _ = set(normalized_key(c) for page in parsed for c in page)
    dedup_ms = (time.perf_counter() - dedup_t) * 1000
    return {"files": len(paths), "repeat": repeat, "xmlLoadMs": round(load_ms, 3), "parseMsMean": round(sum(parse_times)/len(parse_times), 3), "aggregateMsMean": round(sum(aggregate_times)/len(aggregate_times), 3), "normalizeMs": round(normalize_ms, 3), "dedupMs": round(dedup_ms, 3)}

def main() -> int:
    ap = argparse.ArgumentParser(description="Screenshot-free Teamwork accessibility XML diagnostic parser")
    ap.add_argument("xml", nargs="+", type=Path)
    ap.add_argument("--fail-on-ambiguity", action="store_true")
    ap.add_argument("--evidence-level", choices=sorted(EVIDENCE_LEVELS), default="PROVISIONAL")
    ap.add_argument("--benchmark", action="store_true")
    args = ap.parse_args()
    if args.benchmark:
        print(json.dumps(benchmark(args.xml), indent=2))
        return 0
    items = [(str(p), p.read_text(encoding="utf-8")) for p in args.xml]
    if len(items) == 1:
        result = [asdict(c) | {"ambiguous": c.ambiguous} for c in parse_xml(items[0][1], evidence_level=args.evidence_level)]
        print(json.dumps(result, indent=2))
        if args.fail_on_ambiguity and any(x["ambiguous"] for x in result):
            return 2
        return 0
    result = aggregate_xml_texts(items, evidence_level=args.evidence_level)
    print(json.dumps(result, indent=2))
    if args.fail_on_ambiguity and any(x.get("confidence") == "AMBIGUOUS" for x in result["shifts"]):
        return 2
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
