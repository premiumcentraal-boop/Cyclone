"""Alpha.117 (plan 56): the owner's VMOS buttons. Rent phones by the period (a week, a month) or pay-for-time, renew,
power a pay-for-time phone on and off, back a phone up and restore its own backup. Money moves only for the exact
total the owner confirmed."""
from __future__ import annotations

import json

import pytest

from cyclone_device_gateway.cloud_fleet.models import CloudPhone, ProviderError
from cyclone_device_gateway.cloud_fleet.providers.vmos import VmosCloud, sign_v2
from cyclone_device_gateway.desktop_runtime.models import DesktopRuntimeError
from test_cloud_fleet import AK, NOW, SK, Clock, FakeHttp, add_vmos, make_service, phone_of, run, vmos_routes

P = "/vcpcloud/api/padApi/"
OFFERS = {"code": 200, "data": {"goodId": 1, "configs": [
    {"configId": 13, "configName": "Galaxy A53", "sellOutFlag": False, "timingSellOutFlag": True, "androidVersion": "13",
     "goodTimes": [
         {"id": 74, "showContent": "7 days", "goodTime": 10080, "goodPrice": 500, "equipmentNumber": 1, "autoRenew": True},
         {"id": 75, "showContent": "30 days", "goodTime": 43200, "goodPrice": 1800, "equipmentNumber": 1},
         {"id": "bad", "goodTime": 1, "goodPrice": 1}],
     "timingGoodTimes": [{"id": 1001, "showContent": "1 hour", "goodTime": 60, "goodPrice": 10, "equipmentNumber": 1}]},
    {"configId": 14, "configName": "Sold out", "sellOutFlag": True,
     "goodTimes": [{"id": 90, "goodTime": 1440, "goodPrice": 100}]},
]}}


def routes(**over):
    base = vmos_routes(**{
        P + "getCloudGoodList": OFFERS,
        P + "createMoneyOrder": {"code": 200, "data": [{"orderId": "VMOS-1", "equipmentId": 500001}]},
        P + "createByTimingOrder": {"code": 200, "data": [{"padCode": "ACT0001", "equipmentId": 600001}]},
        P + "timingPadOn": {"code": 200, "data": None},
        P + "timingPadOff": {"code": 200, "data": None},
        P + "openAutoRenew": {"code": 200, "data": None},
        P + "closeAutoRenew": {"code": 200, "data": None},
        P + "backupCalculate": {"code": 200, "data": {"taskId": "1", "padCode": "AC32010230001", "vmStatus": 1}},
        P + "queryBackupCalculateResult": {"code": 200, "data": {"padCode": "AC32010230001", "status": "ready", "totalSize": 4096}},
        P + "addBackup": {"code": 200, "data": {"batchId": "B1", "padCount": 1}},
        P + "queryBackupBatch": {"code": 200, "data": {"batchId": "B1", "taskStatus": 2, "items": [
            {"padCode": "AC32010230001", "status": 2, "backupId": "bkp-AC1-1"}]}},
        P + "clonePadBackup": {"code": 200, "data": None},
    })
    base.update(over)
    return base


def calls(http, name):
    return [c for c in http.calls if c[0] == P + name]


# The provider ---------------------------------------------------------------------------------------------------

def test_offers_are_a_signed_get_and_read_as_periods_and_pay_for_time():
    http = FakeHttp(routes())
    offers = VmosCloud(AK, SK, transport=http, clock=Clock()).offers(13)
    path, headers, query = http.calls[0]
    assert path == P + "getCloudGoodList" and query == {"androidVersion": "13"}
    assert headers["X-Sign"] == sign_v2(SK, str(int(NOW)), P + "getCloudGoodList", "androidVersion=13")
    assert "Content-Type" not in headers
    assert [o["name"] for o in offers] == ["Galaxy A53"]  # sold out left out
    assert [(r["skuId"], r["label"], r["priceCents"]) for r in offers[0]["rentals"]] == [(74, "7 days", 500), (75, "30 days", 1800)]
    assert [(t["skuId"], t["label"], t["priceCents"], t["kind"]) for t in offers[0]["timing"]] == [(1001, "1 hour", 10, "timing")]
    with pytest.raises(ProviderError):
        VmosCloud(AK, SK, transport=http, clock=Clock()).offers(12)  # Cyclone's floor is Android 13


def test_rent_renew_pay_for_time_and_power_send_what_vmos_documents():
    http = FakeHttp(routes())
    vmos = VmosCloud(AK, SK, transport=http, clock=Clock())
    phone = CloudPhone("vmos", "ACT0001", "T1")
    assert vmos.rent(75, 14, 2, auto_renew=True) == [500001]
    assert calls(http, "createMoneyOrder")[-1][2] == {"androidVersionName": "Android14", "goodId": 75, "goodNum": 2, "autoRenew": True}
    vmos.renew(500001, 74, 13, auto_renew=False)
    assert calls(http, "createMoneyOrder")[-1][2]["equipmentId"] == "500001"
    assert vmos.rent_timing(1001, 13, 1) == ["ACT0001"]
    assert calls(http, "createByTimingOrder")[-1][2] == {"goodId": 1, "goodTimeId": 1001, "goodNum": 1, "androidVersion": 33}
    vmos.power(phone, False)
    vmos.power(phone, True)
    # Off always keeps the environment; on never asks for a "new device".
    assert calls(http, "timingPadOff")[-1][2] == {"padCodes": ["ACT0001"], "isBackUp": 1}
    assert calls(http, "timingPadOn")[-1][2] == {"padCodes": ["ACT0001"], "defCode": 0}
    vmos.set_auto_renew(phone, False)
    assert calls(http, "closeAutoRenew")[-1][2] == {"padCode": "ACT0001"}


def test_backup_steps_read_vmos_answers():
    http = FakeHttp(routes(**{P + "queryBackupBatch": {"code": 200, "data": {"taskStatus": 1, "items": [
        {"padCode": "AC32010230001", "status": 3, "failMsg": "Insufficient cloud-disk storage"}]}}}))
    vmos = VmosCloud(AK, SK, transport=http, clock=Clock())
    phone = CloudPhone("vmos", "AC32010230001", "Shop 1")
    assert vmos.backup_size(phone) == ("ready", 4096)
    assert vmos.backup_start(phone, "Before update") == "B1"
    assert calls(http, "addBackup")[-1][2] == {"vcPadBackupList": [{"padCode": "AC32010230001", "name": "Before update"}]}
    assert vmos.backup_progress("B1", phone) == {"state": "failed", "backupId": None, "message": "Insufficient cloud-disk storage"}
    vmos.restore("bkp-AC1-1", phone)
    assert calls(http, "clonePadBackup")[-1][2] == {"vcPadBackupList": [{"backupId": "bkp-AC1-1"}], "pads": [{"padCode": "AC32010230001"}]}


# The service ----------------------------------------------------------------------------------------------------

def test_renting_needs_the_exact_total_the_owner_confirmed_and_the_new_phone_is_kept(tmp_path):
    listing = {"rows": [{"padCode": "AC32010230001", "padStatus": 10}]}
    pads = [{"padCode": "AC32010230001", "equipmentId": 100}]
    env = make_service(tmp_path, routes=routes(**{
        P + "infos": lambda _b: {"code": 200, "data": {"pageData": listing["rows"]}},
        P + "userPadList": lambda _b: {"code": 200, "data": pads},
    }))
    account = add_vmos(env)
    body = {"kind": "rental", "skuId": 74, "android": 13, "count": 2, "autoRenew": True}
    with pytest.raises(DesktopRuntimeError) as exc:
        env.service.rent(account["id"], {**body, "expectedPriceCents": 500})  # the owner saw one phone's price
    assert "$10.00" in exc.value.safe_message and not calls(env.http, "createMoneyOrder")
    with pytest.raises(DesktopRuntimeError):
        env.service.rent(account["id"], {**body, "count": 6, "expectedPriceCents": 3000})
    public = env.service.rent(account["id"], {**body, "expectedPriceCents": 1000})
    assert len(calls(env.http, "createMoneyOrder")) == 1
    assert public["pendingRentals"] == 1 and public["orders"][0]["totalCents"] == 1000 and public["orders"][0]["period"] == "7 days"
    # VMOS makes the phone a little later: it is kept connected, as a rental.
    listing["rows"].append({"padCode": "ACNEW1", "padStatus": 10})
    pads.append({"padCode": "ACNEW1", "equipmentId": 500001, "padName": "New rental"})
    run(env, 61)
    public = env.service.account_public(account["id"])
    new = phone_of(public, "ACNEW1")
    assert new["keep"] is True and new["billing"] == "rental" and new["autoRenew"] is True and new["name"] == "New rental"
    assert public["pendingRentals"] == 0


def test_a_pay_for_time_phone_powers_off_without_nagging_and_back_on(tmp_path):
    env = make_service(tmp_path, routes=routes(**{
        P + "infos": {"code": 200, "data": {"pageData": [{"padCode": "ACT0001", "padStatus": 10},
                                                          {"padCode": "AC32010230001", "padStatus": 10}]}},
        P + "adb": lambda body: vmos_routes()[P + "adb"],
    }))
    account = add_vmos(env)
    env.service.rent(account["id"], {"kind": "timing", "skuId": 1001, "android": 13, "count": 1, "expectedPriceCents": 10})
    phone = phone_of(env.service.account_public(account["id"]), "ACT0001")
    assert phone["keep"] and phone["billing"] == "timing" and not phone["poweredOff"]
    env.service.tick()
    assert phone_of(env.service.account_public(account["id"]), "ACT0001")["serial"]
    with pytest.raises(DesktopRuntimeError):
        env.service.power(account["id"], "AC32010230001", False)  # not a pay-for-time phone
    public = env.service.power(account["id"], "ACT0001", False)
    off = phone_of(public, "ACT0001")
    assert off["poweredOff"] and off["state"] == "waiting" and env.spawned[0].terminated
    adb_calls = len(calls(env.http, "adb"))
    run(env, 120)
    off = phone_of(env.service.account_public(account["id"]), "ACT0001")
    assert off["state"] == "off" and "Power it on here" in off["message"]
    assert len(calls(env.http, "adb")) == adb_calls  # no link attempts while it is off
    on = phone_of(env.service.power(account["id"], "ACT0001", True), "ACT0001")
    assert not on["poweredOff"] and on["poweredOnAtMs"] == env.service._now_ms()
    run(env)
    assert len(calls(env.http, "adb")) == adb_calls + 1


def test_a_backup_runs_when_the_owner_asks_and_only_its_own_phone_can_be_restored(tmp_path):
    env = make_service(tmp_path, routes=routes())
    account = add_vmos(env)
    with pytest.raises(DesktopRuntimeError):
        env.service.start_backup(account["id"], "AC32010230002")  # abnormal, not running
    public = env.service.start_backup(account["id"], "AC32010230001", "Before the update")
    assert phone_of(public, "AC32010230001")["backup"]["stage"] == "sizing"
    with pytest.raises(DesktopRuntimeError) as exc:
        env.service.start_backup(account["id"], "AC32010230001")
    assert "one at a time" in exc.value.safe_message
    run(env, 16)
    assert calls(env.http, "addBackup")[0][2]["vcPadBackupList"][0]["name"] == "Before the update"
    run(env, 31)
    phone = phone_of(env.service.account_public(account["id"]), "AC32010230001")
    assert phone["backup"] is None and phone["lastBackup"]["ok"] is True
    assert phone["backups"][0]["backupId"] == "bkp-AC1-1" and phone["backups"][0]["sizeBytes"] == 4096
    # Nothing ran on its own: no backup without the owner's button.
    assert len(calls(env.http, "backupCalculate")) == 1
    with pytest.raises(DesktopRuntimeError):
        env.service.restore(account["id"], "AC32010230002", "bkp-AC1-1")  # another phone's backup
    env.service.restore(account["id"], "AC32010230001", "bkp-AC1-1")
    assert calls(env.http, "clonePadBackup")[0][2]["pads"] == [{"padCode": "AC32010230001"}]


def test_a_failed_backup_says_why(tmp_path):
    env = make_service(tmp_path, routes=routes(**{P + "addBackup": {"code": 40016, "msg": "Insufficient cloud-disk storage"}}))
    account = add_vmos(env)
    env.service.start_backup(account["id"], "AC32010230001")
    run(env, 16)
    phone = phone_of(env.service.account_public(account["id"]), "AC32010230001")
    assert phone["backup"] is None and phone["lastBackup"]["ok"] is False
    assert "storage" in phone["lastBackup"]["message"]


def test_renewing_uses_the_phone_s_device_id_and_the_confirmed_price(tmp_path):
    env = make_service(tmp_path, routes=routes(**{
        P + "userPadList": {"code": 200, "data": [{"padCode": "AC32010230001", "equipmentId": 100, "androidVersion": "13"}]}}))
    account = add_vmos(env)
    with pytest.raises(DesktopRuntimeError):
        env.service.renew(account["id"], "AC32010230001", 75, 500)
    env.service.renew(account["id"], "AC32010230001", 75, 1800)
    sent = calls(env.http, "createMoneyOrder")[-1][2]
    assert sent["equipmentId"] == "100" and sent["goodId"] == 75
    env.service.set_auto_renew(account["id"], "AC32010230001", True)
    assert phone_of(env.service.account_public(account["id"]), "AC32010230001")["autoRenew"] is True


def test_the_owner_routes_check_their_bodies(tmp_path):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from cyclone_device_gateway.cloud_fleet.api import create_cloud_fleet_router

    env = make_service(tmp_path, routes=routes())
    app = FastAPI()
    app.include_router(create_cloud_fleet_router(env.service, "tok"))
    client = TestClient(app)
    auth = {"Authorization": "Bearer tok"}
    account_id = add_vmos(env)["id"]
    base = f"/v1/cloud/accounts/{account_id}"
    assert client.get(f"{base}/offers?android=13").status_code == 401
    offers = client.get(f"{base}/offers?android=13", headers=auth).json()
    assert offers["offers"][0]["rentals"][0]["priceCents"] == 500
    assert client.get(f"{base}/offers?android=12", headers=auth).status_code == 422
    rent = {"kind": "timing", "skuId": 1001, "android": 13, "count": 1, "expectedPriceCents": 10}
    assert client.post(f"{base}/rent", headers=auth, json={**rent, "count": 9}).status_code == 422
    assert client.post(f"{base}/rent", headers=auth, json={**rent, "command": "x"}).status_code == 422
    assert client.post(f"{base}/rent", headers=auth, json={**rent, "expectedPriceCents": 1}).status_code == 400
    assert client.post(f"{base}/rent", headers=auth, json=rent).status_code == 200
    assert client.post(f"{base}/phones/ACT0001/power", headers=auth, json={"on": False}).status_code == 200
    assert client.post(f"{base}/phones/AC32010230001/restore", headers=auth, json={"backupId": "../x"}).status_code == 422
    assert SK not in json.dumps(client.get("/v1/cloud", headers=auth).json())
