"""Plan 61 inventory and owner-only deletion boundaries. No device or secret fixtures."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from test_portable_settings_guard import used_files, sources, constant

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / 'apps/mobile/app/src/main'
MANIFEST = APP / 'java/com/cyclone/mobile/runtime/safe/SafeManifest.kt'


def inventory():
    rows = re.findall(r'\b(prefs|devicePrefs|deviceFiles|database|files|noBackup|alias)\("([^"]+)", Kind\.(SAFE_SEALED|SAFE|NEVER), "([^"]+)"\)', MANIFEST.read_text(encoding='utf-8'))
    assert len(rows) >= 60
    assert len(rows) == len({(kind, name) for kind, name, _, _ in rows})
    assert all(reason.strip() for _, _, _, reason in rows)
    return rows


def test_all_preferences_are_in_safe_manifest():
    classified = {name for kind, name, _, _ in inventory() if kind in {'prefs', 'devicePrefs'}}
    assert not (set(used_files()) - classified)


def test_all_sqlite_names_are_classified():
    names = set()
    for path, body in sources().items():
        names.update(re.findall(r'"([^"\r\n/]+\.(?:db|sqlite))"', body))
        for call in (r'SQLiteOpenHelper\(\s*[^,]+,\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_.]*))',
                     r'(?:Room\.)?databaseBuilder\(\s*[^,]+,\s*[^,]+,\s*(?:"([^"]+)"|([A-Za-z_][A-Za-z0-9_.]*))'):
            for literal, ident in re.findall(call, body):
                name = literal or constant(body, ident)
                assert name is not None, (path, ident)
                names.add(name)
    classified = {name for kind, name, _, _ in inventory() if kind == 'database'}
    assert len(names) >= 5
    assert not (names - classified)


def test_all_keystore_alias_constants_are_classified():
    aliases = set()
    for body in sources().values():
        aliases.update(re.findall(r'(?:const\s+)?val\s+(?:KEY_)?ALIAS\s*=\s*"([^"]+)"', body))
    classified = {name: value for kind, name, value, _ in inventory() if kind == 'alias'}
    assert len(aliases) >= 9
    assert not (aliases - classified.keys())
    assert set(classified.values()) <= {'SAFE_SEALED', 'NEVER'}
    for key in ('cyclone-device-key-v1', 'cyclone_gateway_v33_phone_identity', 'cyclone.profile.bootstrap.v1'):
        assert classified[key] == 'NEVER'


def test_literal_files_dir_roots_are_classified():
    classified = {name.split('/')[0] for kind, name, _, _ in inventory() if kind == 'files'}
    device_files = {name for kind, name, _, _ in inventory() if kind == 'deviceFiles'}
    for path, body in sources().items():
        for literal, ident in re.findall(r'(?:applicationContext\.)?filesDir\s*,\s*(?:"([^"$]+)(?:\$[^"]*)?"|([A-Z_][A-Za-z0-9_.]*))', body):
            if '.' in ident:
                owner, name = ident.rsplit('.', 1)
                value = next((constant(b, name) for p, b in sources().items() if p.stem == owner), None)
            else:
                value = literal or constant(body, ident)
            assert value is not None, (path, ident)
            # A constant can already include a path; the enclosing top-level store owns it.
            assert value.split('/')[0] in classified or value in device_files, (path, value)
        for argument in re.findall(r'filesDir\s*,\s*([a-z][a-zA-Z0-9_.]*)\s*\)', body):
            # This adapter accepts paths only from PortableFiles, or manifest-validated restore paths.
            assert path.name in {'ProfileCarry.kt', 'SafeStores.kt'}, (path, argument)


def test_delete_has_no_agent_or_task_route():
    allowed = {'ui/settings/DeleteAllDataScreen.kt', 'runtime/safe/SafeDelete.kt'}
    for path, body in sources().items():
        relative = path.relative_to(APP / 'java/com/cyclone/mobile').as_posix()
        if re.search(r'\bSafeDelete\b', body):
            assert relative in allowed, path
    for folder in ('gateway', 'mind', 'agent', 'task', 'infrastructure'):
        for path in (APP / 'java/com/cyclone/mobile' / folder).rglob('*.kt'):
            body = path.read_text(encoding='utf-8')
            assert 'DeleteAllDataScreen' not in body and 'cyclone-safe-delete/v1' not in body, path


def test_backup_excludes_sealed_stores():
    android = '{http://schemas.android.com/apk/res/android}'
    app = ET.parse(APP / 'AndroidManifest.xml').getroot().find('application')
    assert app.get(android+'hasFragileUserData') == 'true'
    sealed = {name+'.xml' for kind, name, state, _ in inventory() if kind == 'prefs' and state == 'SAFE_SEALED'}
    for file in ('cyclone_backup_rules.xml', 'cyclone_data_extraction_rules.xml'):
        root = ET.parse(APP / 'res/xml' / file).getroot()
        sections = [root] if file.startswith('cyclone_backup') else list(root)
        for section in sections:
            excluded = {row.get('path') for row in section.findall('exclude') if row.get('domain') == 'sharedpref'}
            assert not (sealed - excluded), (file, sealed - excluded)


def test_sealed_preferences_have_explicit_adapter():
    body = (APP / 'java/com/cyclone/mobile/runtime/safe/SafeSealedStores.kt').read_text(encoding='utf-8')
    for kind, name, state, _ in inventory():
        if kind == 'prefs' and state == 'SAFE_SEALED':
            assert '"'+name+'"' in body, name


def test_no_shared_uid_and_no_safe_permission_or_exported_component():
    android = '{http://schemas.android.com/apk/res/android}'
    root = ET.parse(APP / 'AndroidManifest.xml').getroot()
    assert root.get(android+'sharedUserId') is None
    for entry in root.findall('uses-permission'):
        assert 'safe' not in entry.get(android+'name').lower()
    for entry in root.find('application'):
        if '.safe.' in entry.get(android+'name', '') or 'DeleteAllData' in entry.get(android+'name', ''):
            assert entry.get(android+'exported') == 'false'
