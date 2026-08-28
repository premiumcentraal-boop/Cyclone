from pathlib import Path
import importlib.util, sys
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('probe',ROOT/'probe.py'); probe=importlib.util.module_from_spec(spec); sys.modules[spec.name]=probe; assert spec.loader; spec.loader.exec_module(probe)
F=ROOT/'fixtures'

def load(name): return (F/name).read_text(encoding='utf-8')

def test_single_open_row():
    out=probe.parse_xml(load('synthetic_calendar_open_rows.xml'),evidence_level='SYNTHETIC_ONLY')
    assert [(x.date,x.code,x.start,x.end) for x in out]==[('2026-08-29','M1','08:00','10:35'),('2026-08-30','M2','10:45','13:20')]
    assert all(not x.ambiguous for x in out)

def test_compose_split_content_desc_and_two_times():
    x=probe.parse_xml(load('synthetic_compose_split_row.xml'),evidence_level='SYNTHETIC_ONLY')[0]
    assert (x.date,x.day,x.code,x.start,x.end)==('2026-08-29','SAT','M1','08:00','10:35')
    assert not x.ambiguous

def test_clickable_parent_and_grandparent():
    for n in ('synthetic_clickable_parent.xml','synthetic_clickable_grandparent.xml'):
        x=probe.parse_xml(load(n),evidence_level='SYNTHETIC_ONLY')[0]
        assert x.claimCandidatePath and 'synthetic/row' in x.claimCandidatePath
        assert not x.ambiguous

def test_sticky_day_header_applies_to_multiple_rows():
    out=probe.parse_xml(load('synthetic_sticky_day_header.xml'),evidence_level='SYNTHETIC_ONLY')
    assert [(x.date,x.day,x.code) for x in out]==[('2026-08-29','SAT','M1'),('2026-08-29','SAT','M2')]
    assert all(not x.ambiguous for x in out)

def test_duplicate_semantics_deduped():
    out=probe.parse_xml(load('synthetic_duplicate_semantics.xml'),evidence_level='SYNTHETIC_ONLY')
    assert len(out)==1 and not out[0].ambiguous

def test_ambiguous_two_codes_fails_closed():
    x=probe.parse_xml(load('synthetic_ambiguous_two_codes.xml'),evidence_level='SYNTHETIC_ONLY')[0]
    assert x.ambiguous and x.code is None and 'CODE_AMBIGUOUS' in x.ambiguity

def test_ambiguous_two_times_fails_closed():
    x=probe.parse_xml(load('synthetic_ambiguous_two_times.xml'),evidence_level='SYNTHETIC_ONLY')[0]
    assert x.ambiguous and x.start is None and 'TIME_AMBIGUOUS' in x.ambiguity

def test_no_date_fails_closed():
    x=probe.parse_xml(load('synthetic_no_date.xml'),evidence_level='SYNTHETIC_ONLY')[0]
    assert x.ambiguous and x.date is None and 'DATE_AMBIGUOUS' in x.ambiguity

def test_multi_dump_aggregate_new_per_page():
    items=[('p1',load('synthetic_scroll_page_1.xml')),('p2',load('synthetic_scroll_page_2.xml')),('p2-repeat',load('synthetic_scroll_page_2.xml'))]
    out=probe.aggregate_xml_texts(items,evidence_level='SYNTHETIC_ONLY')
    assert out['pages']==3
    assert out['newPerPage']==[2,1,0]
    assert out['stable'] is True
    assert len(out['shifts'])==3

def test_evidence_level_rejected_if_unknown():
    try: probe.parse_xml(load('synthetic_clickable_parent.xml'),evidence_level='REALISH')
    except ValueError: pass
    else: raise AssertionError('unknown evidence level accepted')

def test_day_boundary_and_multiple_days():
    out=probe.aggregate_xml_texts([('p1',load('synthetic_scroll_page_1.xml')),('p2',load('synthetic_scroll_page_2.xml'))],evidence_level='SYNTHETIC_ONLY')
    assert {x['date'] for x in out['shifts']}=={'2026-08-29','2026-08-30'}

def test_time_variants():
    for text in ('08:00–10:35','08:00 - 10:35','08:00 10:35'):
        xml=f'<hierarchy><node clickable="true"><node text="2026-08-29"/><node text="M1"/><node text="{text}"/><node text="Open to take"/></node></hierarchy>'
        x=probe.parse_xml(xml)[0]; assert (x.start,x.end)==('08:00','10:35') and not x.ambiguous

def test_dutch_english_date_formats():
    samples={'29 augustus 2026':'2026-08-29','29 Aug 2026':'2026-08-29','August 29, 2026':'2026-08-29','29/08/2026':'2026-08-29','2026-08-29':'2026-08-29'}
    for raw,expected in samples.items():
        assert probe.extract_dates([raw])==[expected]
