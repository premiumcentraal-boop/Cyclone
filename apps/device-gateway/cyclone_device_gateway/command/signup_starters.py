"""Packaged signup examples: schemas and guidance only, never a customer's account data."""
from __future__ import annotations

import json
from importlib.resources import files
from typing import TYPE_CHECKING, Any

from ..desktop_runtime.v5_contract import validate_signup_map
from .pages import MAX_PAGES
from .tables import MAX_TABLES

if TYPE_CHECKING:
    from .center import CommandCenter

INSTAGRAM_ID = 'instagram-phone-v1'


def instagram_map() -> dict[str, Any]:
    value = json.loads(files(__package__).joinpath('resources/instagram_signup.json').read_text(encoding='utf-8'))
    validate_signup_map(value)
    return value


def _text(kind: str, text: str) -> dict[str, Any]:
    return {'type': kind, 'text': [{'t': text}]}


def instagram_guide(table_id: str, schema: dict[str, Any]) -> list[dict[str, Any]]:
    blocks = [
        _text('p', 'Create your own Instagram account with this ready-to-use starter. Add a row with your details, choose whose account it is and one connected phone, then set Ready. In Accounts, open this starter table and press Create accounts to review and confirm the rows.'),
        {'type': 'table', 'tableId': table_id, 'viewId': None},
        _text('h2', 'Before you start'),
        _text('bullet', 'Install Instagram on the selected phone. Set up your private vault in Command Center > Vault; account passwords are generated there and sent sealed to the phone. Passwords and verification messages never belong in table cells.'),
        _text('bullet', 'For SMS autofill, enable code reading in Cyclone Settings > Permissions > Codes and confirm this phone number. Cyclone first tries to retrieve the code from this phone. Email, CAPTCHA, selfie or ID checks may still need you.'),
        _text('h2', 'Already signed in'),
        _text('p', 'Open Profile, tap the username at the top, Add Instagram account, then Create new account. This route can begin with Username instead of Mobile number. Cyclone uses the same row details and adapts to the actual screen; it must not sign out, erase data or overwrite your existing account.'),
        _text('h2', 'Birthday'),
        _text('p', 'Use YYYY-MM-DD in the table. The observed Android picker has month, day and year inputs. Set year first, then abbreviated month and day, press SET and verify the resulting date before Next. January uses Jan; the full month word did not reliably commit in the experiment.'),
        _text('h2', 'Verification and optional pages'),
        _text('p', 'Fresh SMS retrieval uses the current run window. If needed request a fresh message; autofill can advance automatically, so read the resulting screen before another tap. If it returns to terms, continue the already-approved signup. Decline optional cookies and skip contacts, photos and suggested follows. If an optional permission blocks progress, re-observe and recover without replaying signup. Finish only after verifying the new signed-in profile and handle.'),
        _text('h2', 'Recorded signup pages'),
    ]
    for page in schema['pages']:
        inputs = ', '.join(f['label'] + ' (' + f['kind'] + ')' for f in page['fields']) or 'No input fields'
        blocks.append(_text('bullet', f"{page['index']}. {page['title']} — {inputs}; continue {page['continue']}; verification {page['check'] or 'none'}."))
    blocks.extend([
        _text('h2', 'About this starter'),
        _text('p', 'Based on a completed phone-number signup on Instagram ' + schema['appVersion'] + '. It contains field definitions and recovery guidance, with no sample personal details and no queued accounts. Instagram can change its pages; this is a starting workflow, not a claim that this phone has already mapped or created an account. The email signup alternative and the later username-first route were not exercised.'),
        _text('p', 'After success keep that row Created. Add a new row for another account; never queue a completed row again. You can edit or remove this starter, and an update will not recreate deleted starter tables or replace your edits.'),
    ])
    return blocks


def install_instagram(center: 'CommandCenter') -> None:
    schema = instagram_map()
    with center._lock:
        if center._db.execute('SELECT 1 FROM signup_starter WHERE id = ?', (INSTAGRAM_ID,)).fetchone():
            return
        center._db.execute('SAVEPOINT install_signup_starter')
        try:
            live = {t['id'] for t in center.tables.list()}
            existing = next((r for r in center._db.execute('SELECT table_id FROM signup_table WHERE package = ?',
                                                         (schema['package'],)) if r['table_id'] in live), None)
            guide = next((p for p in center.pages.tree() if p['title'] == 'Instagram account creation'), None)
            if ((existing is None and center._db.execute('SELECT COUNT(*) FROM cc_table').fetchone()[0] >= MAX_TABLES)
                    or (guide is None and center._db.execute('SELECT COUNT(*) FROM page').fetchone()[0] >= MAX_PAGES)):
                center._db.execute('RELEASE install_signup_starter')
                return  # A full owner workspace must still start; install after room becomes available.
            table_id = existing['table_id'] if existing else center.signup._new_table(schema)['id']
            if guide is None:
                guide = center.pages.create({'title': 'Instagram account creation', 'icon': '🪪',
                                             'blocks': instagram_guide(table_id, schema)})
            center._db.execute('INSERT INTO signup_starter(id, table_id, page_id, map) VALUES (?,?,?,?)',
                               (INSTAGRAM_ID, table_id, guide['id'], json.dumps(schema, ensure_ascii=False)))
            center._audit('engine', 'signup.starter.install', INSTAGRAM_ID, {'table': table_id, 'page': guide['id']})
            center._db.execute('RELEASE install_signup_starter')
        except Exception:
            center._db.execute('ROLLBACK TO install_signup_starter')
            center._db.execute('RELEASE install_signup_starter')
            raise
