"""Public provider contracts reviewed 2026-10-03. Never retry a purchase here."""
from __future__ import annotations

import hashlib
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from typing import Any

NAMES = {"vmos": "VMOS Cloud", "smsbot": "SMSBot.cc"}
PREFIX = "/vcpcloud/api/padApi/cloudNumber"
IDENT = re.compile(r"^[A-Za-z0-9_-]{1,80}$")
E164 = re.compile(r"^\+?[1-9][0-9]{6,14}$")
PUBLIC_ERRORS = frozenset({"REFRESHING", "REJECTED", "NOT_FOUND", "UNAVAILABLE", "RATE_LIMITED", "INVALID_REQUEST",
    "INVALID_API_KEY", "ACCOUNT_BLOCKED", "RENTAL_NOT_FOUND", "BAD_REQUEST", "INSUFFICIENT_BALANCE", "INVALID_PARAMETERS",
    "NO_NUMBERS_AVAILABLE", "RENTAL_NOT_ACTIVE", "PRICE_CHANGED", "BAD_COUNTRY", "TEMPLATE_NOT_FOUND",
    "TEMPLATE_NO_SERVICES_FOR_COUNTRY", "IDEMPOTENCY_CONFLICT", "IDEMPOTENCY_KEY_REUSED", "RATE_LIMIT_EXCEEDED", "INTERNAL_ERROR"})


class ProviderError(ValueError):
    def __init__(self, code: str, *, uncertain: bool = False):
        # Never include the provider's free-text message/body: it can echo credentials or SMS.
        self.code = code if re.fullmatch(r"[A-Z0-9_]{1,60}", code) else "PROVIDER_ERROR"
        self.uncertain = uncertain
        super().__init__(self.code.replace("_", " ").capitalize())


def cents(value: Any) -> int:
    if isinstance(value, bool):
        raise ProviderError("INVALID_PRICE")
    try:
        amount = Decimal(str(value)) * 100
        if not amount.is_finite() or amount < 0 or amount != amount.to_integral_value() or amount > 1000000:
            raise ProviderError("INVALID_PRICE")
        return int(amount)
    except (InvalidOperation, ValueError):
        raise ProviderError("INVALID_PRICE") from None


def positive(value: Any) -> int:
    if type(value) is not int or not 0 < value <= 1000000:
        raise ProviderError("INVALID_PROVIDER_RESPONSE")
    return value


def identifier(value: Any) -> str:
    if not isinstance(value, str) or not IDENT.fullmatch(value):
        raise ProviderError("INVALID_IDENTIFIER")
    return value


def country_code(value: Any) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Z]{2}", value):
        raise ProviderError("INVALID_COUNTRY")
    return value


def phone(value: Any) -> str:
    if not isinstance(value, str) or not E164.fullmatch(value):
        raise ProviderError("INVALID_NUMBER")
    return "+" + value.lstrip("+")


def stamp(value: Any, *, vmos: bool = False) -> int | None:
    try:
        date = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if date.tzinfo is None:
            if not vmos:
                return None
            date = date.replace(tzinfo=timezone(timedelta(hours=8)))
        return int(date.timestamp() * 1000)
    except (ValueError, TypeError, AttributeError, OverflowError):
        return None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def transport(method: str, url: str, body: bytes | None, headers: dict[str, str]) -> tuple[int, Any]:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, data=body, headers=headers, method=method), timeout=10) as response:
            status, raw = response.status, response.read(524289)
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read(524289)
    except (OSError, ValueError):
        raise ProviderError("NETWORK_UNCERTAIN", uncertain=True) from None
    if len(raw) > 524288:
        raise ProviderError("INVALID_PROVIDER_RESPONSE", uncertain=True)
    try:
        return status, json.loads(raw)
    except (ValueError, UnicodeError):
        raise ProviderError("INVALID_PROVIDER_RESPONSE", uncertain=True) from None


class Provider:
    def __init__(self, kind: str, credentials: dict[str, Any], *, fetch=transport, clock=time.time):
        if kind not in NAMES:
            raise ProviderError("UNKNOWN_PROVIDER")
        self.kind, self.credentials, self.fetch, self.clock = kind, credentials, fetch, clock

    def call(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode() if payload is not None else None
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        if self.kind == "vmos":
            base = "https://api.vmoscloud.com"
            ts = str(int(self.clock()))
            material = self.credentials["secretKey"].encode() + ts.encode() + path.encode() + (raw or b"")
            headers.update({"X-Access-Key": self.credentials["accessKey"], "X-Timestamp": ts,
                            "X-Sign": hashlib.sha256(material).hexdigest()})
        else:
            base = "https://cabinet.smsbot.cc"
            headers["Authorization"] = "Bearer " + self.credentials["apiKey"]
            path = "/api/v1" + path
        status, data = self.fetch(method, base + path, raw, headers)
        if not isinstance(data, dict) or status >= 500 or 300 <= status < 400:
            raise ProviderError("PROVIDER_UNCERTAIN", uncertain=True)
        if self.kind == "vmos":
            inner = data.get("data")
            if status != 200 or data.get("code") != 200:
                known = {401: "INVALID_API_KEY", 403: "ACCOUNT_BLOCKED", 429: "RATE_LIMITED"}.get(status)
                raise ProviderError(known or "PROVIDER_UNCERTAIN", uncertain=known is None)
            if isinstance(inner, dict) and inner.get("errorCode"):
                code = inner["errorCode"]
                known = isinstance(code, str) and code in PUBLIC_ERRORS and code != "INTERNAL_ERROR"
                raise ProviderError(code if known else "PROVIDER_UNCERTAIN", uncertain=not known)
            return inner
        if status >= 400 or data.get("success") is not True:
            code = data.get("code")
            known = isinstance(code, str) and code in PUBLIC_ERRORS and code != "INTERNAL_ERROR"
            authentication = {401: "INVALID_API_KEY", 403: "ACCOUNT_BLOCKED", 429: "RATE_LIMITED"}.get(status)
            raise ProviderError(authentication or (code if known else "PROVIDER_UNCERTAIN"), uncertain=not (known or authentication))
        return data.get("data")

    def catalogue(self, country: str | None = None, area: str = "213") -> dict[str, Any]:
        if self.kind == "vmos":
            if area not in ("213", "480", "702", "813"):
                raise ProviderError("INVALID_AREA_CODE")
            data = self.call("POST", PREFIX + "/skus", {"areaCode": area})
            if not isinstance(data, list):
                raise ProviderError("INVALID_PROVIDER_RESPONSE")
            return {"countries": [{"code": country_code(c["countryCode"]), "name": country_code(c["countryCode"]),
                    "available": c.get("status") == "AVAILABLE", "plans": [{"id": positive(s["planId"]),
                    "days": positive(s["durationDays"]), "amountCents": positive(s["chargeCents"]), "currency": "USD"}
                    for s in c.get("skus", [])]} for c in data], "templates": [], "areaCode": area}
        countries = self.call("GET", "/countries")
        templates = self.call("GET", "/templates")
        if not isinstance(countries, list) or not isinstance(templates, list):
            raise ProviderError("INVALID_PROVIDER_RESPONSE")
        return {"countries": [{"code": country_code(c["code"]), "name": str(c.get("name", c["code"]))[:80],
                 "available": int(c.get("count", 0)) > 0, "plans": []} for c in countries],
                "templates": [{"id": identifier(t["id"]), "name": str(t.get("name", ""))[:100],
                 "country": country_code(t["country"]), "periods": list((t.get("prices") or {}).keys())}
                 for t in templates if t.get("isActive") is True and (not country or t["country"] == country)]}

    def quote(self, selection: dict[str, Any]) -> dict[str, Any]:
        country = country_code(selection.get("country"))
        if self.kind == "vmos":
            area = selection.get("areaCode", "213")
            data = self.catalogue(area=area)
            c = next((c for c in data["countries"] if c["code"] == country and c["available"]), None)
            plan = next((p for p in c["plans"] if p["id"] == selection.get("planId")), None) if c else None
            if not plan:
                raise ProviderError("NO_NUMBERS_AVAILABLE")
            return {"country": country, "planId": plan["id"], "areaCode": area if country == "US" else None,
                    "days": plan["days"], "amountCents": plan["amountCents"], "currency": "USD",
                    "services": ["SMS reception"], "autoRenew": False,
                    "terms": "One temporary number. Auto-renew off. Release does not refund the paid period."}
        template, period = identifier(selection.get("templateId")), str(selection.get("period", ""))
        if period not in ("1", "3", "7", "14", "30"):
            raise ProviderError("INVALID_PERIOD")
        data = self.call("GET", f"/templates/{template}?" + urllib.parse.urlencode({"country": country, "period": period}))
        if not isinstance(data, dict) or data.get("id") != template or data.get("country") != country or data.get("isActive") is not True:
            raise ProviderError("INVALID_PROVIDER_RESPONSE")
        if data.get("unavailable") or not data.get("services"):
            raise ProviderError("TEMPLATE_SERVICES_UNAVAILABLE")
        amount = cents((data.get("prices") or {}).get(period))
        return {"country": country, "templateId": template, "period": period, "days": int(period),
                "amountCents": amount, "currency": "EUR", "services": [str(s["name"])[:100] for s in data["services"]],
                "serviceIds": [identifier(s["serviceId"]) for s in data["services"]], "autoRenew": False,
                "terms": "One temporary template number. No partial refunds. Uncertain purchases are never retried."}

    def purchase(self, quote: dict[str, Any], token: str) -> dict[str, Any]:
        if self.kind == "vmos":
            body = {"countryCode": quote["country"], "planId": quote["planId"], "quantity": 1, "autoRenew": 0,
                    "clientToken": token, "expectedTotalCents": quote["amountCents"]}
            if quote["country"] == "US":
                body["areaCode"] = quote["areaCode"]
            return self.call("POST", PREFIX + "/purchase", body)
        # Documented price guard; a one-shot request. Never retry this non-idempotent endpoint.
        return self.call("POST", f"/templates/{quote['templateId']}/rent", {"country": quote["country"],
                         "period": quote["period"], "quantity": 1, "expectedPrice": quote["amountCents"] / 100})

    def status(self, token: str) -> dict[str, Any]:
        if self.kind != "vmos":
            raise ProviderError("OWNER_RECONCILIATION_REQUIRED")
        return self.call("POST", PREFIX + "/purchase/status", {"clientToken": token})

    def rental(self, rental_id: str) -> dict[str, Any]:
        identifier(rental_id)
        if self.kind == "vmos":
            data = self.call("POST", PREFIX + "/list", {"keyword": rental_id, "page": 1, "size": 100})
            record = next((r for r in data.get("records", []) if phone(r["number"]) == phone(rental_id)), None)
            if not record:
                raise ProviderError("RENTAL_NOT_FOUND")
            return {"id": phone(record["number"]).lstrip("+"), "number": phone(record["number"]),
                    "country": country_code(record["countryCode"]), "active": record.get("status") in ("normal", "soon"),
                    "expiresAt": stamp(record.get("expireTime"), vmos=True), "autoRenew": record.get("autoRenew") == 1}
        data = self.call("GET", "/rentals/" + rental_id)
        if not isinstance(data, dict) or data.get("id") != rental_id:
            raise ProviderError("INVALID_PROVIDER_RESPONSE")
        return {"id": rental_id, "number": phone(data["number"]), "country": country_code(data["country"]),
                "active": data.get("status") == "ACTIVE", "expiresAt": stamp(data.get("endDate")), "autoRenew": False,
                "templateId": data.get("templateId"), "startedAt": stamp(data.get("startDate")), "priceCents": cents(data.get("price"))}

    def messages(self, rental_id: str, expected_number: str) -> list[dict[str, Any]]:
        if self.kind == "vmos":
            rental = self.rental(rental_id)
            if rental["number"] != expected_number or not rental["active"] or not rental["expiresAt"] or rental["expiresAt"] <= self.clock() * 1000:
                raise ProviderError("RENTAL_NOT_ACTIVE")
            data = self.call("POST", PREFIX + "/sms/list", {"number": phone(expected_number).lstrip("+"), "page": 1, "size": 100})
            rows = data.get("records", [])
            return [{"id": str(r["id"]), "sender": str(r.get("sender", "")), "body": str(r.get("content", "")),
                     "at": stamp(r.get("receivedTime"), vmos=True)} for r in rows
                    if not r.get("number") or phone(r["number"]) == expected_number]
        data = self.call("GET", "/rentals/" + identifier(rental_id))
        if not isinstance(data, dict) or data.get("id") != rental_id or phone(data.get("number")) != expected_number or data.get("status") != "ACTIVE" or not stamp(data.get("endDate")) or stamp(data["endDate"]) <= self.clock() * 1000:
            raise ProviderError("RENTAL_NOT_ACTIVE")
        return [{"id": str(r["id"]), "sender": str(r.get("sender", "")), "body": str(r.get("message", "")),
                 "extractedCode": r.get("extractedCode"), "at": stamp(r.get("receivedAt"))} for r in data.get("smsMessages", [])]
