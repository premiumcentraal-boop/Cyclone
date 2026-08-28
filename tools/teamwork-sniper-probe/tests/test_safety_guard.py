from pathlib import Path
import importlib.util,sys
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('safety_guard',ROOT/'safety_guard.py'); m=importlib.util.module_from_spec(spec); sys.modules[spec.name]=m; assert spec.loader; spec.loader.exec_module(m)

def test_current_probe_tree_passes():
    assert m.scan(ROOT)==[]

def test_detects_coordinate_literal(tmp_path):
    bad='input ' + 'tap ' + '100 ' + '200\n'
    (tmp_path/'bad.py').write_text(bad,encoding='utf-8')
    assert any('hardcoded click coordinates' in x for x in m.scan(tmp_path))
