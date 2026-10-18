"""Field test 143 (B12): the doctor's ✓/✗ marks crashed a Windows cp1252 console unless PYTHONUTF8=1 was set."""
import io
import sys

from cyclone_testbench import cli


def test_a_console_that_cannot_show_the_marks_prints_question_marks_instead(monkeypatch):
    raw = io.BytesIO()
    console = io.TextIOWrapper(raw, encoding="cp1252", errors="strict")
    monkeypatch.setattr(sys, "stdout", console)
    monkeypatch.setattr(sys, "stderr", console)
    cli._safe_console()
    cli._out("✓ ready ✗ missing")
    console.flush()
    assert raw.getvalue().decode("cp1252").startswith("? ready ? missing")
