"""The port catalog of contract ``cyclone.ports/1`` (plan 45). One source of truth for the SDK, the Dev Hub, the
conformance checker and the tests. The real Port Hub in the gateway will import the same table.

A port is a named, typed point on a run:
- ``out`` ports carry data from the phone run to a plugin (fire and forget, with a receipt);
- ``in`` ports carry data from a plugin to a run that is waiting for it (always with a timeout).

Sensitivity decides what may reach a plugin:
- ``public`` ports go to any plugin the owner bound;
- ``personal`` ports need the owner's per-plugin consent;
- ``secret`` ports carry codes or keys and are handled by the hub (see SPEC.md §7).
"""
from __future__ import annotations

from dataclasses import dataclass

CONTRACT = "cyclone.ports/1"


@dataclass(frozen=True)
class Port:
    name: str
    way: str            # "out" | "in"
    sensitivity: str    # "public" | "personal" | "secret"
    plugin_served: bool  # False: handled by the hub and the vault only in v1
    summary: str


CATALOG: dict[str, Port] = {p.name: p for p in [
    Port("run.event", "out", "public", True, "Lifecycle: started, page, step, needs_you, created, done, failed, cancelled."),
    Port("screen.shot", "out", "personal", True, "PNG of the current screen with its page key, as a one-time artifact link."),
    Port("account.fields", "out", "personal", True, "The row's typed fields (name, date of birth, email, username). Never a password."),
    Port("page.text", "out", "personal", True, "The visible text of the page, secret-shaped values hidden."),
    Port("file.out", "out", "personal", True, "A file the run made or downloaded, as a one-time artifact link."),
    Port("log.line", "out", "public", True, "A short note the run writes."),
    Port("file.in", "in", "personal", True, "A file for the phone (image, PDF), saved to the run's folder."),
    Port("value.in", "in", "personal", True, "Text or JSON the run asked for."),
    Port("code.in", "in", "secret", True, "A verification code (SMS or email) from a source the owner registered."),
    Port("link.in", "in", "personal", True, "A confirmation link (https) from the owner's own inbox."),
    Port("secret.out", "out", "secret", False, "Hub/vault only in v1: a password or API key sealed on the phone to the vault."),
    Port("secret.in", "in", "secret", False, "Hub/vault only in v1: a vault item released to the phone for one run."),
]}

RUN_STAGES = ("started", "page", "step", "needs_you", "created", "done", "failed", "cancelled")

# Field names that never travel on account.fields, whatever the plugin asks for.
FORBIDDEN_FIELD_WORDS = ("password", "passcode", "secret", "token", "otp", "pin", "cvv", "cvc", "card")

LIMITS = {
    "envelope_bytes": 256 * 1024,
    "log_line_chars": 500,
    "value_bytes": 64 * 1024,
    "file_bytes": 20 * 1024 * 1024,
    "code_chars": 12,
    "await_timeout_max_s": 600,
    "signature_skew_s": 300,
}


def plugin_ports() -> list[str]:
    return [name for name, port in CATALOG.items() if port.plugin_served]
