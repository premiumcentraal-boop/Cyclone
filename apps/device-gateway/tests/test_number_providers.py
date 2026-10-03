"""Documented provider fixtures; no real credentials, balance debit or inbox reads."""
import hashlib
import json
import socket
import threading
import time
from datetime import datetime, timezone
from types import SimpleNamespace
from urllib.parse import urlsplit

import pytest
import uvicorn
from fastapi import FastAPI
from fastapi.testclient import TestClient
from cyclone_ports import sign
from cyclone_ports.conformance import Check, check_plugin

from cyclone_device_gateway.cloud_fleet.vault import CloudVault
from cyclone_device_gateway.command.api import create_command_router
from cyclone_device_gateway.command.center import CommandCenter
from cyclone_device_gateway.number_providers.api import create_number_providers_router
from cyclone_device_gateway.number_providers.providers import PREFIX, Provider, ProviderError, cents, stamp
from cyclone_device_gateway.number_providers.service import NumberProviders, PLUGIN, TTL
from cyclone_device_gateway.numbers.service import NumbersService
from cyclone_device_gateway.ports.hub import PortHub
from test_command_center import FakeContract

SECRET = "test-credential-should-never-be-returned"


class World:
    def __init__(self, root, *, real_checks=False, base="http://127.0.0.1:8765"):
        self.now = int(time.time() * 1000)
        self.price = 998
        self.purchases = []
        self.fail_purchase = False
        self.messages = []
        self.cc = CommandCenter(root / "cc.db", FakeContract(), lambda: [])
        self.hub = PortHub(root / "ports", base_url=base, checker=None if real_checks else lambda *_: [Check("fixture", True)])
        self.numbers = NumbersService(root / "numbers.db", devices=lambda: [], read_phone=lambda _: {},
            plugins=lambda: self.hub.overview()["plugins"], accounts=self.cc.list_accounts, clock=lambda: self.now)
        self.service = NumberProviders(root / "providers", self.numbers, self.hub, self.cc,
            vault=CloudVault(root / "test-keys", persistent=False), fetch=self.fetch, clock=lambda: self.now)
        self.cc.number_providers = self.hub.number_providers = self.service
        self.app = FastAPI()
        self.app.include_router(create_number_providers_router(self.service, "owner-token"))
        self.app.include_router(create_command_router(SimpleNamespace(command=self.cc), "owner-token"))
        self.client = TestClient(self.app)
        if not real_checks:
            def local(method, url, body=None, headers=None, timeout=None):
                response = self.client.request(method, urlsplit(url).path, content=body, headers=headers)
                return response.status_code, response.json(), 0
            self.hub._fetch = local

    def iso(self, ms):
        return datetime.fromtimestamp(ms / 1000, timezone.utc).isoformat()

    def fetch(self, method, url, body, headers):
        path = urlsplit(url).path
        data = json.loads(body) if body else {}
        if "vmoscloud" in url:
            assert headers["X-Access-Key"] == SECRET
            assert headers["X-Sign"] == hashlib.sha256((SECRET + headers["X-Timestamp"] + path).encode() + (body or b"")).hexdigest()
            if path.endswith("/skus"):
                result = [{"countryCode": "GB", "status": "AVAILABLE", "skus": [{"planId": 2, "durationDays": 30, "chargeCents": self.price}]}]
            elif path.endswith("/purchase"):
                self.purchases.append(data)
                if self.fail_purchase:
                    raise ProviderError("NETWORK_UNCERTAIN", uncertain=True)
                result = self.vmos_result()
            elif path.endswith("/purchase/status"):
                result = self.vmos_result()
            elif path.endswith("/sms/list"):
                result = {"records": self.messages}
            elif path.endswith("/list"):
                result = {"records": [{"number": "447700900123", "countryCode": "GB", "status": "normal", "autoRenew": 0, "expireTime": self.iso(self.now + 25 * 86400000)}]}
            else:
                raise AssertionError(path)
            return 200, {"code": 200, "data": result}
        assert headers["Authorization"] == "Bearer " + SECRET
        if path.endswith("/countries"):
            result = [{"code": "ES", "name": "Spain", "count": 2}]
        elif path.endswith("/templates"):
            result = [{"id": "template_1", "country": "ES", "name": "Business", "isActive": True, "prices": {"7": "0.30"}}]
        elif path.endswith("/templates/template_1/rent"):
            self.purchases.append(data)
            if self.fail_purchase:
                raise ProviderError("NETWORK_UNCERTAIN", uncertain=True)
            result = {"requested": 1, "created": [{"id": "rental_1", "number": "34689018024"}], "failed": []}
        elif path.endswith("/templates/template_1"):
            result = {"id": "template_1", "country": "ES", "isActive": True, "prices": {"7": "0.30"},
                "services": [{"serviceId": "service_1", "name": "Bling"}], "unavailable": []}
        elif path.endswith("/rentals/rental_1"):
            result = {"id": "rental_1", "number": "34689018024", "country": "ES", "status": "ACTIVE", "price": "0.30",
                "templateId": "template_1", "startDate": self.iso(self.now), "endDate": self.iso(self.now + 7 * 86400000), "smsMessages": self.messages}
        else:
            raise AssertionError(path)
        return 200, {"success": True, "data": result}

    def vmos_result(self):
        return {"status": "COMPLETED", "deliveredQuantity": 1, "quantity": 1, "numbers": ["447700900123"], "chargedCents": self.price, "terminal": True}

    def connect(self, kind="vmos", **policy):
        return self.service.configure(kind, {**({"accessKey": SECRET, "secretKey": SECRET} if kind == "vmos" else {"apiKey": SECRET}),
                                             "agentEnabled": True, **policy})

    def selection(self, kind="vmos", request="job1"):
        return {"requestId": request, **({"country": "GB", "planId": 2} if kind == "vmos" else {"country": "ES", "templateId": "template_1", "period": "7"})}

    def request(self, kind="vmos", **kwargs):
        q = self.service.quote(kind, self.selection(kind), **kwargs)
        return self.service.request_approval(q["id"])

    def approve(self, order, action="approve"):
        self.cc.answer(order["approvalId"], {"action": action})

    def wait(self, kind, port, match, run="mission_1", app="com.example.shop"):
        opened = self.hub.traffic.wait(run, port, match, 120, {"plugin": PLUGIN[kind], "app": app})
        assert opened["state"] == "waiting", opened
        self.hub.traffic.flush()
        return self.hub.store.wait(opened["awaitId"])

    def prepare(self, kind="smsbot"):
        wait = self.wait(kind, "value.in", {"ask": "prepare-code", "requestId": "job1"})
        self.service.tick()
        assert self.hub.traffic.result(wait["awaitId"])["value"]["status"] == "prepared"

    def close(self):
        self.service.stop(); self.hub.stop(); self.numbers.close(); self.cc.stop()


@pytest.fixture
def world(tmp_path):
    world = World(tmp_path)
    yield world
    world.close()


@pytest.mark.parametrize("kind", ["vmos", "smsbot"])
def test_keys_are_write_only_and_purchase_needs_the_existing_owner_approval(world, kind):
    world.connect(kind)
    order = world.request(kind)
    review = world.cc.list_approvals()[0]["send"]["arguments"]
    assert review["Numbers"] == 1 and review["Automatic renewal"] == "Off"
    assert review["Total price"] == ("9.98 USD" if kind == "vmos" else "0.30 EUR")
    world.service.tick()
    assert not world.purchases
    assert SECRET not in json.dumps(world.service.overview())
    assert SECRET not in world.service._db.execute("SELECT quote FROM number_order").fetchone()[0]
    world.approve(order)
    world.service.tick(); world.service.tick()
    assert len(world.purchases) == 1
    saved = world.service.order(order["id"])
    assert saved["status"] == "complete", saved
    assert saved["number"].startswith("+")
    if kind == "vmos":
        assert world.purchases[0]["autoRenew"] == 0 and world.purchases[0]["quantity"] == 1
        assert world.purchases[0]["expectedTotalCents"] == 998
    else:
        assert world.purchases[0] == {"country": "ES", "period": "7", "quantity": 1, "expectedPrice": 0.3}
    assert world.service.request_approval(order["id"])["status"] == "complete"


@pytest.mark.parametrize("case", ["decline", "expire", "reprice", "disable"])
def test_declined_expired_repriced_or_disabled_requests_do_not_spend(world, case):
    world.connect()
    order = world.request()
    if case == "expire": world.now += TTL + 1
    if case == "disable": world.connect(agentEnabled=False); world.hub.pause(PLUGIN["vmos"], True)
    world.approve(order, "decline" if case == "decline" else "approve")
    if case == "reprice": world.price += 1
    world.service.tick()
    assert not world.purchases
    assert world.service.order(order["id"])["status"] in ("rejected", "declined", "expired", "cancelled")


def test_same_request_is_durable_and_different_parameters_conflict(world):
    world.connect()
    first = world.service.quote("vmos", world.selection())
    assert world.service.quote("vmos", world.selection())["id"] == first["id"]
    with pytest.raises(ProviderError, match="Request id conflict"):
        world.service.quote("vmos", {**world.selection(), "planId": 3})
    with pytest.raises(ProviderError):
        world.service.quote("vmos", {**world.selection(), "approved": True})


@pytest.mark.parametrize("kind", ["vmos", "smsbot"])
def test_uncertain_purchase_never_retries_and_restart_reconciles_read_only(world, kind):
    world.connect(kind)
    order = world.request(kind)
    world.fail_purchase = True
    world.approve(order); world.service.tick()
    assert world.service.order(order["id"])["status"] == "unknown"
    world.fail_purchase = False
    world.service.tick(); world.service.tick()
    assert len(world.purchases) == 1
    saved = world.service.order(order["id"])
    assert saved["status"] == ("complete" if kind == "vmos" else "unknown")
    if kind == "smsbot":
        result = world.service.reconcile(order["id"], "rental_1")
        assert result["status"] == "complete"
        assert len(world.purchases) == 1


@pytest.mark.parametrize("kind", ["vmos", "smsbot"])
def test_unrecognized_purchase_error_is_uncertain_not_permission_to_buy_again(world, kind):
    world.connect(kind)
    order = world.request(kind)
    original = world.service.fetch
    def error_response(method, url, body, headers):
        result = original(method, url, body, headers)
        if url.endswith("/purchase"):
            return 200, {"code": 200, "data": {"errorCode": "INTERNAL_ERROR", "message": SECRET}}
        if url.endswith("/rent"):
            return 200, {"success": False, "code": SECRET, "message": SECRET}
        return result
    world.service.fetch = error_response
    world.approve(order); world.service.tick()
    assert world.service.order(order["id"])["status"] == "unknown"
    assert SECRET not in json.dumps(world.service.overview())
    world.service.tick(); world.service.tick()
    assert len(world.purchases) == 1


def test_agent_scopes_and_owner_port_map_choices_are_enforced(world):
    world.connect(apps=["com.example.shop"])
    assert world.service.skills("com.other.app") == []
    assert len(world.service.skills("com.example.shop")) == 1
    with pytest.raises(ProviderError):
        world.service.quote("vmos", world.selection(), wait={"runId": "mission_1", "awaitId": "aw_bad", "request": {"app": "com.other.app"}})
    world.hub.store.set_binding("app:com.example.shop", "value.in", [])
    assert world.hub.traffic.wait("mission_1", "value.in", {"ask": "catalogue"}, 10, {"plugin": "vmos-numbers", "app": "com.example.shop"})["state"] != "waiting"


def test_stopped_wait_cannot_purchase_after_owner_approval(world):
    world.connect()
    wait = world.wait("vmos", "value.in", {"ask": "rent", **world.selection()})
    world.service.tick()
    order = world.service.overview()["orders"][0]
    world.approve(order)
    world.hub.traffic.cancel(wait["awaitId"])
    world.service.tick()
    assert not world.purchases
    assert world.service.order(order["id"])["status"] == "cancelled"


@pytest.mark.parametrize("case", ["expire", "stop", "permission"])
def test_stale_owner_prompt_is_withdrawn_without_spend_or_stranded_scope(world, case):
    world.connect()
    wait = world.wait("vmos", "value.in", {"ask": "rent", **world.selection()})
    world.service.tick()
    order = world.service.overview()["orders"][0]
    assert world.cc.list_approvals()
    if case == "expire":
        world.now += TTL + 1
    elif case == "stop":
        world.hub.traffic.cancel(wait["awaitId"])
    else:
        world.connect(agentEnabled=False)
    world.service.tick()
    assert world.service.order(order["id"])["status"] == ("expired" if case == "expire" else "cancelled")
    assert not world.cc.list_approvals() and not world.purchases
    # A later request can proceed; the old owner prompt cannot authorize it.
    world.connect()
    assert world.service.quote("vmos", world.selection(request="next_request"))["status"] == "quoted"
    with pytest.raises(ValueError):
        world.approve(order)
    assert not world.purchases


def test_agent_number_and_code_are_bound_to_the_run_and_never_written(world):
    world.connect("smsbot")
    waiting = world.wait("smsbot", "value.in", {"ask": "rent", **world.selection("smsbot")})
    world.service.tick()
    order = world.service.overview()["orders"][0]
    world.approve(order); world.service.tick()
    number_result = world.hub.traffic.result(waiting["awaitId"])
    assert number_result["value"]["number"] == "+34689018024"
    world.prepare()
    wait = world.wait("smsbot", "code.in", {"requestId": "job1", "from": "Bling", "length": 6})
    world.now = max(world.now, wait["createdAt"] + 1)
    world.messages = [{"id": "sms_1", "sender": "Bling", "message": "Your code is 847291", "extractedCode": "847291", "receivedAt": world.iso(world.now)}]
    world.service.tick()
    result = world.hub.traffic.result(wait["awaitId"])
    assert result["state"] == "delivered", result
    assert result["codeLength"] == 6 and "code" not in result
    assert world.hub.traffic.take_code(wait["awaitId"]) == "847291"
    assert world.hub.traffic.take_code(wait["awaitId"]) is None
    for db in (world.hub.store._db, world.service._db, world.numbers._db):
        assert "847291" not in "".join(db.iterdump())
    other = world.wait("smsbot", "code.in", {"requestId": "job1", "from": "Bling"}, run="other_run")
    world.service.tick()
    assert world.hub.traffic.result(other["awaitId"])["state"] == "waiting"


@pytest.mark.parametrize("case", ["stale", "wrong_sender", "ambiguous", "paused", "revoked"])
def test_codes_fail_closed(world, case):
    world.connect("smsbot")
    rental_wait = world.wait("smsbot", "value.in", {"ask": "rent", **world.selection("smsbot")})
    world.service.tick(); order = world.service.overview()["orders"][0]
    world.approve(order); world.service.tick()
    world.prepare()
    wait = world.wait("smsbot", "code.in", {"requestId": "job1", "from": "Bling"})
    world.now = max(world.now, wait["createdAt"] + 1)
    world.messages = [{"id": "sms_1", "sender": "Other" if case == "wrong_sender" else "Bling", "message": "Your code is 847291", "receivedAt": world.iso(world.now - 60000 if case == "stale" else world.now)}]
    if case == "ambiguous": world.messages.append({**world.messages[0], "id": "sms_2", "message": "Your code is 123456"})
    if case == "paused": world.numbers.update(world.service.order(order["id"])["numberId"], {"paused": True})
    if case == "revoked": world.hub.set_port("smsbot-numbers", "code.in", False)
    world.service.tick()
    assert world.hub.traffic.result(wait["awaitId"])["state"] != "delivered"
    assert world.hub.traffic.take_code(wait["awaitId"]) is None


def test_api_auth_signatures_and_replays(world):
    assert world.client.get("/v1/number-providers").status_code == 401
    world.connect()
    path = "/v1/number-plugins/vmos/ports/code.in/await"
    raw = b'{"awaitId":"sample","deliverUrl":"https://invalid.example","token":"sample"}'
    key = world.hub.store.key("vmos-numbers")
    header = {"X-Cyclone-Signature": sign(key, "POST", path, raw)}
    assert world.client.post(path, content=raw).status_code == 401
    assert world.client.post(path, content=raw, headers=header).status_code == 202
    assert world.client.post(path, content=raw, headers=header).status_code == 409
    assert world.client.post(path, content=raw + b" ", headers=header).status_code == 401
    assert not world.purchases


def test_failed_account_authentication_preserves_the_old_connection_and_redacts_errors(world):
    world.service.fetch = lambda *args: (401, {"message": SECRET, "code": SECRET})
    with pytest.raises(ProviderError) as error:
        world.connect()
    assert error.value.code == "INVALID_API_KEY" and SECRET not in str(error.value)
    assert world.service.vault.account("vmos") is None
    assert world.hub.store.plugin("vmos-numbers") is None
    assert not world.purchases


@pytest.mark.parametrize("kind", ["vmos", "smsbot"])
def test_provider_checks_current_rental_ownership_and_expiry_before_reading_codes(world, kind):
    world.connect(kind)
    original = world.service.fetch
    def expired(method, url, body, headers):
        status, response = original(method, url, body, headers)
        if url.endswith("/list"):
            response["data"]["records"][0]["status"] = "expired"
        elif url.endswith("/rentals/rental_1"):
            response["data"]["endDate"] = world.iso(world.now - 1000)
        return status, response
    world.service.fetch = expired
    with pytest.raises(ProviderError, match="Rental not active"):
        world.service._provider(kind).messages("447700900123" if kind == "vmos" else "rental_1",
                                               "+447700900123" if kind == "vmos" else "+34689018024")
    assert not world.purchases


def test_vmos_business_error_and_secret_echo_are_redacted():
    p = Provider("vmos", {"accessKey": SECRET, "secretKey": SECRET}, fetch=lambda *args: (200, {"code": 200, "data": {"errorCode": "REJECTED", "message": SECRET}}))
    with pytest.raises(ProviderError) as error: p.catalogue()
    assert SECRET not in str(error.value)
    assert cents("0.30") == 30
    for amount in (True, "NaN", "Infinity", "0.301", -1):
        with pytest.raises(ProviderError): cents(amount)
    assert stamp("2026-10-01 08:00:00", vmos=True) == stamp("2026-10-01T00:00:00Z")


def test_actual_restart_never_resubmits_a_write_ahead_order(world, tmp_path):
    world.connect("smsbot")
    order = world.request("smsbot"); world.approve(order)
    world.service._set(order["id"], state="submitting")
    vault = world.service.vault
    world.service.stop()
    world.service = NumberProviders(tmp_path / "providers", world.numbers, world.hub, world.cc, vault=vault, fetch=world.fetch, clock=lambda: world.now)
    world.cc.number_providers = world.hub.number_providers = world.service
    assert world.service.order(order["id"])["status"] == "unknown"
    world.service.tick(); world.service.tick()
    assert not world.purchases


def test_assignment_replay_and_cross_country_identity(world):
    world.connect()
    account = world.cc.create_account({"service": "example.com", "handle": "owner", "ownerBasis": "mine"})
    selection = {**world.selection(), "accountId": account["id"]}
    order = world.service.request_approval(world.service.quote("vmos", selection)["id"])
    world.approve(order); world.service.tick()
    assert world.numbers.number_for_account(account["id"])["number"] == "+447700900123"
    assert world.service.quote("vmos", selection)["status"] == "complete"
    other = world.numbers.add({"number": "+317700900123", "origin": "rental", "provider": "Example"})
    assert other["id"] != world.service.order(order["id"])["numberId"]


def test_baseline_messages_are_ignored_but_fast_sms_before_the_wait_is_received(world):
    world.connect("smsbot")
    waiting = world.wait("smsbot", "value.in", {"ask": "rent", **world.selection("smsbot")})
    world.service.tick(); order = world.service.overview()["orders"][0]; world.approve(order); world.service.tick()
    world.now += 1000
    world.messages = [{"id": "old", "sender": "Bling", "message": "Old code 123456", "receivedAt": world.iso(world.now)}]
    world.prepare()
    world.now += 1
    world.messages.append({"id": "fast", "sender": "Bling", "message": "Your code 847291", "receivedAt": world.iso(world.now)})
    time.sleep(.02)
    wait = world.wait("smsbot", "code.in", {"requestId": "job1", "from": "Bling"})
    world.service.tick()
    assert world.hub.traffic.take_code(wait["awaitId"]) == "847291"


def test_native_starters_pass_real_sdk_conformance(tmp_path):
    sock = socket.socket(); sock.bind(("127.0.0.1", 0)); port = sock.getsockname()[1]
    world = World(tmp_path, real_checks=True, base=f"http://127.0.0.1:{port}")
    server = uvicorn.Server(uvicorn.Config(world.app, log_level="error"))
    thread = threading.Thread(target=lambda: server.run(sockets=[sock]), daemon=True); thread.start()
    try:
        deadline = time.monotonic() + 10
        while not server.started and time.monotonic() < deadline: time.sleep(.02)
        assert server.started
        for kind in ("vmos", "smsbot"):
            overview = world.connect(kind)
            checks = world.hub.plugin(PLUGIN[kind])["plugin"]["checks"]
            assert checks["passed"], checks
            assert world.hub.plugin(PLUGIN[kind])["plugin"]["status"] == "active"
        assert not world.purchases, "conformance samples cannot trigger provider mutations"
    finally:
        server.should_exit = True; thread.join(5); world.close()
