from pathlib import Path
import importlib.util

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("probe", ROOT / "probe.py")
probe = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(probe)

def wrap(inner: str) -> str:
    return f'<hierarchy><node class="root">{inner}</node></hierarchy>'

def row(day: str, code: str, times: str, clickable="true") -> str:
    return f'''<node class="android.view.ViewGroup" clickable="{clickable}" enabled="true">
      <node text="{day}"/>
      <node text="{code}"/>
      <node text="{times}"/>
      <node text="Open to take"/>
    </node>'''

def test_single_open_row():
    out = probe.parse_xml(wrap(row("2026-08-29", "M1", "08:00–10:35")))
    assert [(x.date,x.code,x.start,x.end,x.state) for x in out] == [("2026-08-29","M1","08:00","10:35","OPEN_TO_TAKE")]
    assert not out[0].ambiguous

def test_multiple_shifts_same_day_and_m1_m2():
    out = probe.parse_xml(wrap(row("2026-08-30","M1","08:00-10:35") + row("2026-08-30","M2","10:45-13:20")))
    assert {(x.code,x.start,x.end) for x in out} == {("M1","08:00","10:35"),("M2","10:45","13:20")}

def test_s1_s2_s3_sequence_synthetic():
    out = probe.parse_xml(wrap(
        row("2026-08-31","S1","06:00-08:00") +
        row("2026-08-31","S2","08:15-10:15") +
        row("2026-08-31","S3","10:30-12:30")
    ))
    assert [x.code for x in out] == ["S1","S2","S3"]

def test_multiple_days():
    out = probe.parse_xml(wrap(row("2026-08-29","M1","08:00-10:35") + row("2026-08-30","M2","10:45-13:20")))
    assert {x.date for x in out} == {"2026-08-29","2026-08-30"}

def test_row_text_split_across_siblings_and_clickable_ancestor_lookup():
    xml = wrap('''<node class="android.view.ViewGroup" clickable="true" enabled="true" resource-id="row">
      <node text="2026-08-29"/><node><node text="M1"/></node><node content-desc="08:00–10:35"/>
      <node><node text="Open to take"/></node>
    </node>''')
    out = probe.parse_xml(xml)
    assert out[0].code == "M1"
    assert out[0].clickable_path and "row" in out[0].clickable_path
    assert not out[0].ambiguous

def test_scroll_page_deduplication_via_normalized_fingerprint():
    a = probe.parse_xml(wrap(row("2026-08-29","M1","08:00-10:35")))
    b = probe.parse_xml(wrap(row("2026-08-29","M1","08:00-10:35")))
    assert probe.normalized_fingerprint(a) == probe.normalized_fingerprint(b)

def test_no_open_state():
    assert probe.parse_xml(wrap('<node text="No shifts available"/>')) == []

def test_ambiguous_code_time_fails_closed():
    xml = wrap('''<node clickable="true"><node text="2026-08-29"/><node text="M1 M2"/>
      <node text="08:00"/><node text="09:00"/><node text="10:00"/><node text="Open to take"/></node>''')
    out = probe.parse_xml(xml)
    assert out[0].ambiguous
    assert out[0].code is None and out[0].start is None

def test_day_boundary_no_cross_row_binding():
    out = probe.parse_xml(wrap(row("2026-08-29","M1","23:00-23:59") + row("2026-08-30","M2","00:01-01:00")))
    assert {(x.date,x.code) for x in out} == {("2026-08-29","M1"),("2026-08-30","M2")}

def test_duplicate_nodes_deduplicate():
    one = row("2026-08-29","M1","08:00-10:35")
    out = probe.parse_xml(wrap(one + one))
    assert len(out) == 1

def test_nonclickable_row_is_ambiguous():
    out = probe.parse_xml(wrap(row("2026-08-29","M1","08:00-10:35", clickable="false")))
    assert out[0].ambiguous
    assert out[0].clickable_path is None
