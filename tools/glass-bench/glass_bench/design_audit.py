"""Plan 76, stage 0: the design audit. Lists every place Glass defines its look outside the design system.

`apps/glass/src/styles/tokens.css` says it is "the only place colours, radii, spacing and type are defined", and plan
76 §5.3 asks for one icon set. This audit counts what breaks either promise:
- colour literals (#hex, rgb(), hsl()) outside tokens.css;
- font sizes, spacing (margin, padding, gap) and radii written as pixel values instead of var(--…);
- symbol glyphs typed into the interface code (⋮⋮, ▸, ▦, ✕…) instead of coming from the icon set (ui/icons.ts).
Emoji used as content (page icons, templates) are counted apart: they are data, not chrome.
"""
from __future__ import annotations

import re
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SRC = ROOT / "apps/glass/src"
TOKENS = SRC / "styles/tokens.css"
ICON_FILES = {SRC / "ui/icons.ts", SRC / "ui/logos.ts"}

COLOUR = re.compile(r"#[0-9a-fA-F]{3,8}\b|\b(?:rgb|rgba|hsl|hsla)\(")
DECL = re.compile(r"(?P<prop>[a-z-]+)\s*:\s*(?P<value>[^;{}]+);")
PX = re.compile(r"(?<![\w.-])(?P<n>\d*\.?\d+)px\b")
SPACING = {"margin", "margin-top", "margin-bottom", "margin-left", "margin-right", "margin-inline", "margin-block",
           "padding", "padding-top", "padding-bottom", "padding-left", "padding-right", "padding-inline", "padding-block",
           "gap", "row-gap", "column-gap"}
RADIUS = {"border-radius", "border-top-left-radius", "border-top-right-radius", "border-bottom-left-radius",
          "border-bottom-right-radius"}
STRING = re.compile(r"(\"(?:[^\"\\\n]|\\.)*\"|'(?:[^'\\\n]|\\.)*'|`(?:[^`\\]|\\.)*`)")
CODE_DIRS = ("workspace", "pages", "ui", "manager", "core", "maps")


def is_emoji(ch: str) -> bool:
    cp = ord(ch)
    return 0x1F000 <= cp <= 0x1FAFF or 0x2600 <= cp <= 0x27BF and unicodedata.category(ch) == "So" and cp >= 0x2700 or cp in (0x2B50, 0x2B55)


def is_glyph(ch: str) -> bool:
    """A symbol drawn as chrome: arrows, geometric shapes, box drawing, dingbats, math symbols."""
    if ch.isascii() or ch.isalnum() or ch.isspace():
        return False
    cat = unicodedata.category(ch)
    return cat in ("So", "Sm") and not is_emoji(ch)


@dataclass
class Audit:
    colours: Counter = field(default_factory=Counter)
    font_px: Counter = field(default_factory=Counter)
    spacing_px: Counter = field(default_factory=Counter)
    radius_px: Counter = field(default_factory=Counter)
    glyphs: Counter = field(default_factory=Counter)          # by file
    glyph_chars: Counter = field(default_factory=Counter)     # by character
    emoji_files: Counter = field(default_factory=Counter)

    def totals(self) -> dict[str, int]:
        return {"colours": sum(self.colours.values()), "font_px": sum(self.font_px.values()),
                "spacing_px": sum(self.spacing_px.values()), "radius_px": sum(self.radius_px.values()),
                "glyphs": sum(self.glyphs.values()), "emoji": sum(self.emoji_files.values())}


def audit_css(text: str, name: str, audit: Audit) -> None:
    body = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    audit.colours[name] += len(COLOUR.findall(body))
    for decl in DECL.finditer(body):
        prop, value = decl["prop"], decl["value"]
        if prop.startswith("--"):
            continue
        literals = [m for m in PX.finditer(value) if float(m["n"]) != 0]
        if not literals:
            continue
        if prop == "font-size":
            audit.font_px[name] += len(literals)
        elif prop in SPACING:
            audit.spacing_px[name] += len(literals)
        elif prop in RADIUS:
            audit.radius_px[name] += len(literals)


def audit_code(text: str, name: str, audit: Audit) -> None:
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
    for literal in STRING.findall(code):
        for ch in literal:
            if is_glyph(ch):
                audit.glyphs[name] += 1
                audit.glyph_chars[ch] += 1
            elif is_emoji(ch):
                audit.emoji_files[name] += 1


def run(src: Path = SRC) -> Audit:
    audit = Audit()
    for css in sorted((src / "styles").glob("*.css")):
        if css.resolve() != TOKENS.resolve():
            audit_css(css.read_text(encoding="utf-8"), css.relative_to(src).as_posix(), audit)
    for folder in CODE_DIRS:
        for ts in sorted((src / folder).rglob("*.ts")):
            if ts.resolve() in {p.resolve() for p in ICON_FILES}:
                continue
            audit_code(ts.read_text(encoding="utf-8"), ts.relative_to(src).as_posix(), audit)
    return audit


def report(audit: Audit) -> str:
    t = audit.totals()

    def top(counter: Counter, n: int = 8) -> str:
        return ", ".join(f"`{k}` {v}" for k, v in counter.most_common(n) if v) or "none"

    lines = ["# Glass design audit (plan 76, stage 0, run 0b)", "",
             "What defines the look outside the design system (`styles/tokens.css` and `ui/icons.ts`). Stage 2 removes it.", "",
             "| Kind | Count | Where most (file, count) |", "|---|---:|---|",
             f"| Colour literals outside the tokens | {t['colours']} | {top(audit.colours)} |",
             f"| Font sizes in px | {t['font_px']} | {top(audit.font_px)} |",
             f"| Spacing (margin, padding, gap) in px | {t['spacing_px']} | {top(audit.spacing_px)} |",
             f"| Radii in px | {t['radius_px']} | {top(audit.radius_px)} |",
             f"| Symbol glyphs typed into the interface | {t['glyphs']} | {top(audit.glyphs)} |",
             f"| Emoji in code (content such as page icons; kept) | {t['emoji']} | {top(audit.emoji_files)} |", "",
             "Most used glyphs: " + (", ".join(f"`{c}` U+{ord(c):04X} ×{n}" for c, n in audit.glyph_chars.most_common(15)) or "none") + ".",
             "", "Counts are of literals, not of rules: one declaration with two pixel values counts twice. Zero values are not counted.", ""]
    return "\n".join(lines)


if __name__ == "__main__":
    import sys

    text = report(run())
    if len(sys.argv) > 1:
        Path(sys.argv[1]).write_text(text, encoding="utf-8")
    print(text)
