"""Android regex guard: every Kotlin regex literal must compile on Android, not only on the desktop JVM.

alpha.138 shipped "Cyclone could not run this mission: com.cyclone.mobile.mind.one.OneMindPrompt": ToolSteps built
Regex("\\{([a-z]...)}") at class load. The desktop JVM (and so every unit test) accepts a lone '}', but Android's
regex is ICU, which rejects it with U_REGEX_RULE_SYNTAX; the class failed to load and every Flash and OneMind
mission failed. Outside a character class, '{' must open a {n}, {n,} or {n,m} quantifier and '}' must close one;
a literal brace is escaped.
"""
from pathlib import Path
import ctypes
import ctypes.util
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "apps/mobile/app/src/main/java"

# A regex literal: Regex("..."), Pattern.compile("..."), "...".toRegex(), with "+"-joined parts on following lines.
OPENERS = re.compile(r'(?:\bRegex\(|Pattern\.compile\()\s*(?=")|(?=("(?:[^"\\\n]|\\.)*")\s*\.toRegex\()')
RAW = re.compile(r'"""(.*?)"""', re.S)
PART = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
JOIN = re.compile(r'\s*\+(?:\s|//[^\n]*)*(?=")')
BRACED_ESCAPE = "pPNx"  # \p{L}, \P{L}, \N{name}, \x{hhhh}
QUANTIFIER = re.compile(r"\{\d+(?:,\d*)?\}")
KOTLIN_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "\\": "\\", '"': '"', "'": "'", "$": "$"}


def kotlin_value(body: str) -> str:
    """The string a Kotlin literal holds; a template becomes a plain placeholder."""
    out, i = [], 0
    while i < len(body):
        c = body[i]
        if c == "\\" and i + 1 < len(body):
            out.append(KOTLIN_ESCAPES.get(body[i + 1], body[i + 1]))
            i += 2
        elif c == "$" and body[i + 1:i + 2] == "{":
            depth, i = 1, i + 2
            while i < len(body) and depth:
                depth += {"{": 1, "}": -1}.get(body[i], 0)
                i += 1
            out.append("x")
        elif c == "$" and re.match(r"[A-Za-z_]", body[i + 1:i + 2]):
            i += re.match(r"\$\w+", body[i:]).end()
            out.append("x")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def literals():
    for path in BASE.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        for opener in OPENERS.finditer(text):
            at, parts = opener.end(), []
            while (part := RAW.match(text, at) or PART.match(text, at)):
                # A raw string holds its text as written; only templates are filled in.
                parts.append(re.sub(r"\$\{[^}]*\}|\$[A-Za-z_]\w*", "x", part.group(1)) if part.re is RAW
                             else kotlin_value(part.group(1)))
                at = part.end()
                join = JOIN.match(text, at)
                if not join:
                    break
                at = join.end()
            if parts:
                line = text.count("\n", 0, opener.start()) + 1
                yield f"{path.relative_to(BASE).as_posix()}:{line}", "".join(parts)


def stray_brace(pattern: str) -> int | None:
    """The index of a brace ICU rejects, or None."""
    i, depth = 0, 0  # depth: nesting of [...] sets (ICU allows nested sets)
    while i < len(pattern):
        c = pattern[i]
        if c == "\\":
            if pattern[i + 1:i + 2] in BRACED_ESCAPE and pattern[i + 2:i + 3] == "{":
                close = pattern.find("}", i + 3)
                if close < 0:
                    return i
                i = close + 1
            else:
                i += 2
            continue
        if c == "[":
            depth += 1
            i += 1
            if pattern[i:i + 1] == "^":
                i += 1
            if pattern[i:i + 1] == "]":
                i += 1  # a leading ']' is literal
            continue
        if depth:
            if c == "]":
                depth -= 1
            i += 1
            continue
        if c == "{":
            quantifier = QUANTIFIER.match(pattern, i)
            if not quantifier:
                return i
            i = quantifier.end()
            continue
        if c == "}":
            return i
        i += 1
    return None


def icu_compiler():
    """Android's own regex engine (ICU's uregex_open), when libicu is on this machine; else None.

    ICU suffixes every symbol with its major version (uregex_open_74), so the suffix is probed.
    """
    for name in (ctypes.util.find_library("icui18n"), "libicui18n.so"):
        if not name:
            continue
        try:
            lib = ctypes.CDLL(name)
        except OSError:
            continue
        for version in range(50, 100):
            opener = getattr(lib, f"uregex_open_{version}", None)
            if opener is None:
                continue
            closer = getattr(lib, f"uregex_close_{version}")
            opener.restype = ctypes.c_void_p

            def compiles(pattern: str) -> int:
                data = pattern.encode("utf-16-le")
                buffer = ctypes.create_string_buffer(data, len(data) + 2)
                error = ctypes.c_int(0)
                handle = opener(buffer, len(data) // 2, 0, None, ctypes.byref(error))
                if handle:
                    closer(ctypes.c_void_p(handle))
                return error.value  # > 0 is an ICU error (U_REGEX_RULE_SYNTAX = 66304, …)

            return compiles
    return None


class AndroidRegexGuard(unittest.TestCase):
    def test_guard_knows_what_icu_rejects(self):
        self.assertIsNotNone(stray_brace(r"\{([a-z][a-z0-9_]{0,40})}"))
        self.assertIsNotNone(stray_brace(r"\$\{(\w+)}"))
        self.assertIsNotNone(stray_brace(r"a{x}"))
        self.assertIsNone(stray_brace(r"\{([a-z][a-z0-9_]{0,40})\}"))
        self.assertIsNone(stray_brace(r"[^,}\s]+a{2,}b{1,3}"))
        self.assertIsNone(stray_brace(r"[\p{L}\p{N}]+[[a-z]&&[^}]]"))
        self.assertIsNone(stray_brace(r"\p{Mn}+\b\x{41}"))

    def test_kotlin_values(self):
        self.assertEqual(kotlin_value(r"\\{(\\w+)\\}"), r"\{(\w+)\}")
        self.assertEqual(kotlin_value(r"^${prefix}open\\b$name"), r"^xopen\bx")

    def test_every_regex_literal_compiles_on_android(self):
        found = list(literals())
        self.assertGreater(len(found), 500)
        bad = [f"{where}: {value!r}" for where, value in found if stray_brace(value) is not None]
        self.assertEqual(bad, [], "escape literal braces as \\\\{ and \\\\} (Android's ICU regex rejects them)")


    def test_every_regex_literal_compiles_with_androids_icu(self):
        """Beyond braces: compile each literal with libicu itself, exactly as Android does (look-behind limits,
        property names, set syntax, inline flags). Skipped only where libicu is missing."""
        compiles = icu_compiler()
        if compiles is None:
            self.skipTest("libicu is not on this machine; the structural brace check above still runs")
        self.assertNotEqual(compiles(r"\{([a-z]+)}"), 0, "the engine must reject what Android rejects")
        self.assertEqual(compiles(r"\{([a-z]+)\}"), 0)
        bad = [f"{where}: {value!r} (ICU error {code})" for where, value in literals() if (code := compiles(value)) > 0]
        self.assertEqual(bad, [], "Android's ICU regex rejects these patterns")


if __name__ == "__main__":
    unittest.main()
