"""Shipping examples must work without a phone map and must preserve owner data on upgrades."""
import json
import re
from pathlib import Path

import pytest

from cyclone_device_gateway.command.center import CommandCenter
from cyclone_device_gateway.command.signup_starters import instagram_map
from test_command_signup import RunContract, Clock, cells_of


@pytest.fixture
def starter(tmp_path):
    contract = RunContract()
    devices = [{'deviceId': 'pixel8-abc', 'name': 'Pixel 8', 'paired': True, 'state': 'ready'}]
    center = CommandCenter(tmp_path / 'cc.db', contract, lambda: devices, clock=Clock())
    yield center, contract
    center.stop()


def test_fresh_workspace_has_one_empty_runnable_table_and_guide(starter):
    center, contract = starter
    center.signup.install_starters()
    item = center.signup.starters()['starters'][0]
    table = center.tables.get(item['tableId'])
    assert len(center.tables.list()) == 1
    assert center.tables.all_rows(table['id']) == []
    names = [p['name'] for p in table['properties']]
    assert {'Mobile number', 'Date of birth', 'Full name', 'Username', 'Phone'} <= set(names)
    assert 'Password' not in names and 'Confirmation code' not in names
    assert len(item['map']['pages']) == 12
    assert center.signup.prepare({'tableId': table['id']})['rows'] == []
    assert center.list_accounts() == []
    assert center.list_tasks() == [] and contract.started == []
    text = json.dumps(center.pages.get(item['pageId']))
    assert 'YYYY-MM-DD' in text and 'Add Instagram account' in text
    assert not re.search(r'\+\d{6,}|\b\d{4}-\d{2}-\d{2}\b|[\w.+-]+@[\w.-]+\.[a-z]{2,}', text + json.dumps(item))


def test_starter_routes_ready_row_to_chosen_phone_without_mapping(starter):
    center, contract = starter
    contract.maps = []
    center.signup.install_starters()
    item = center.signup.starters()['starters'][0]
    table = center.tables.get(item['tableId'])
    row = center.tables.create_row(table['id'], {'cells': cells_of(table, Account='My account', Status='Ready',
         Username='myexample', **{'Full name': 'My Name', 'Date of birth': {'start': '1990-05-17'},
                                  'Mobile number': '+15555550123', 'Phone': ['pixel8-abc'], 'Whose account': 'Mine'})})
    prepared = center.signup.prepare({'tableId': table['id']})['rows'][0]
    assert prepared['deviceId'] == 'pixel8-abc' and prepared['username'] == 'myexample'
    result = center.signup.create({'tableId': table['id'], 'rows': [{'rowId': row['id'],
                                  'accountId': prepared['accountId'], 'vaultItemId': None}]})
    args = center.signup.run_args(result['started'][0]['taskId'])
    assert args['package'] == 'com.instagram.android'
    assert args['values'] == {'phone': '+15555550123', 'birthday': '1990-05-17', 'full_name': 'My Name', 'username': 'myexample'}
    assert contract.maps == []


def test_missing_phone_is_a_row_error_and_creates_no_account(starter):
    center, _ = starter
    center.signup.install_starters()
    table = center.tables.get(center.signup.starters()['starters'][0]['tableId'])
    center.tables.create_row(table['id'], {'cells': cells_of(table, Account='Empty phone', Status='Ready')})
    result = center.signup.prepare({'tableId': table['id']})
    assert 'Choose one phone' in result['rows'][0]['error']
    assert center.list_accounts() == []


def test_reinstall_preserves_edits_and_respects_deleted_table(starter):
    center, _ = starter
    center.signup.install_starters()
    item = center.signup.starters()['starters'][0]
    table = center.tables.get(item['tableId'])
    center.tables.update(table['id'], {'title': 'My edited signup table'})
    row = center.tables.create_row(table['id'], {'cells': cells_of(table, Account='Keep this row', Status='Created')})
    center.signup.install_starters()
    assert center.tables.get(table['id'])['title'] == 'My edited signup table'
    assert center.tables.get_row(table['id'], row['id'])['cells'] == row['cells']
    center.tables.archive(table['id'])
    center.tables.delete(table['id'])
    center.signup.install_starters()
    assert center.signup.starters()['starters'] == [] and center.tables.list() == []


def test_existing_instagram_table_is_adopted_without_replacing_its_map(starter):
    center, _ = starter
    center.signup.maps('pixel8-abc')
    table = center.signup.make_table({'deviceId': 'pixel8-abc', 'package': 'com.instagram.android'})
    center.signup.install_starters()
    assert center.signup.starters()['starters'][0]['tableId'] == table['id']
    assert len(center.tables.list()) == 1
    assert center.signup.maps('pixel8-abc')['maps'][0]['appVersion'] == '350.0'


def test_phone_and_gateway_ship_identical_schema_only_assets():
    root = Path(__file__).resolve().parents[3]
    mobile = json.loads((root / 'apps/mobile/app/src/main/assets/signup/instagram.json').read_text(encoding='utf-8'))
    assert mobile == instagram_map()


def test_install_failure_rolls_back_partial_tables(starter, monkeypatch):
    center, _ = starter
    def fail(*args, **kwargs):
        raise RuntimeError('simulated guide failure')
    monkeypatch.setattr(center.pages, 'create', fail)
    with pytest.raises(RuntimeError, match='simulated'):
        center.signup.install_starters()
    assert center.tables.list() == [] and center.signup.starters()['starters'] == []


@pytest.mark.parametrize('limit', ['MAX_TABLES', 'MAX_PAGES'])
def test_full_workspace_still_starts_and_can_install_later(starter, monkeypatch, limit):
    from cyclone_device_gateway.command import signup_starters
    center, _ = starter
    with monkeypatch.context() as patch:
        patch.setattr(signup_starters, limit, 0)
        center.signup.install_starters()
        assert center.tables.list() == [] and center.signup.starters()['starters'] == []
    center.signup.install_starters()
    assert len(center.signup.starters()['starters']) == 1


def test_reopen_keeps_the_same_starter_and_owner_edits(tmp_path):
    path = tmp_path / 'persistent.db'
    first = CommandCenter(path, RunContract(), lambda: [], clock=Clock())
    try:
        first.signup.install_starters()
        item = first.signup.starters()['starters'][0]
        first.tables.update(item['tableId'], {'title': 'My custom signup'})
    finally:
        first.stop()
    second = CommandCenter(path, RunContract(), lambda: [], clock=Clock())
    try:
        second.signup.install_starters()
        assert second.signup.starters()['starters'][0] == item
        assert len(second.tables.list()) == 1
        assert second.tables.get(item['tableId'])['title'] == 'My custom signup'
    finally:
        second.stop()
