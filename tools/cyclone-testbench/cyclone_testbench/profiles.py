"""Plan 57 P3 (alpha.121): suite `profiles`, the switch matrix on a real phone.

Cycles through the phone's ready profiles over the PC gateway (the same owner routes Glass uses) and records, for every
switch, whether the phone ended where asked, came back by itself (the dead-man return), or got stuck. Then it saves the
phone's profile debug file (redacted on the phone and again by the gateway). Creating profiles stays on the phone, by
design; see docs/PROFILES_DEVICE_MATRIX.md for the steps a person does.

Standard library only; the gateway, clock and sleep are passed in so the logic is tested without a phone.
"""
from __future__ import annotations

import time
import urllib.parse
from typing import Any, Callable

ARRIVED = "arrived"
CAME_BACK = "came_back"
STUCK = "stuck"
REFUSED = "refused"


def switch_plan(profile_ids: list[str], rounds: int) -> list[str]:
    """Every ready profile in turn, `rounds` times, never the same one twice in a row (Main included)."""
    ids = list(dict.fromkeys(profile_ids))
    if len(ids) < 2 or rounds < 1:
        raise ValueError("The profiles suite needs at least two ready profiles (Main and one more) and one round.")
    plan: list[str] = []
    for r in range(rounds):
        # Forward, then backward, so every profile is switched to from every other one.
        order = ids if r % 2 == 0 else list(reversed(ids))
        while plan and order[0] == plan[-1]:
            order = order[1:] + order[:1]
        plan.extend(order)
    return plan


def ready_profiles(listing: dict[str, Any]) -> list[str]:
    return [p["id"] for p in listing.get("profiles") or [] if isinstance(p, dict) and p.get("ready") and not p.get("inTrash")]


def _path(device: str, *parts: str) -> str:
    return "/v1/devices/" + "/".join(urllib.parse.quote(p, safe="") for p in (device, "profiles", *parts))


def run_matrix(gw: Any, device: str, rounds: int, *, settle_s: float = 70.0, poll_s: float = 2.0,
               clock: Callable[[], float] = time.monotonic, sleep: Callable[[float], None] = time.sleep,
               say: Callable[[str], None] = print) -> dict[str, Any]:
    """Runs the matrix and returns {switches[], counts{}, ok}. `ok` means no switch got stuck or was refused."""
    listing = gw.get(_path(device))
    current = listing.get("current")
    plan = switch_plan(ready_profiles(listing), rounds)
    if plan and plan[0] == current:
        plan = plan[1:]
    switches: list[dict[str, Any]] = []
    for index, target in enumerate(plan, start=1):
        source = current
        started = clock()
        row: dict[str, Any] = {"n": index, "from": source, "to": target}
        try:
            gw.post(_path(device, target, "switch"))
        except Exception as exc:  # noqa: BLE001 - the phone said no; record why and go on from where it is
            row.update(outcome=REFUSED, seconds=round(clock() - started, 1), error=str(exc)[:200])
        else:
            outcome, seen = STUCK, None
            while clock() - started < settle_s:
                try:
                    seen = gw.get(_path(device)).get("current")
                except Exception:  # noqa: BLE001 - the gateway can be briefly unreachable during a switch
                    seen = None
                if seen == target:
                    outcome = ARRIVED
                    break
                if seen == source and clock() - started > 45:
                    outcome = CAME_BACK
                    break
                sleep(poll_s)
            row.update(outcome=outcome, seconds=round(clock() - started, 1), seen=seen)
        try:
            current = gw.get(_path(device)).get("current")
        except Exception:  # noqa: BLE001
            current = row.get("seen") or source
        switches.append(row)
        say(f"{'✓' if row['outcome'] == ARRIVED else '↩' if row['outcome'] == CAME_BACK else '✗'} {index:>3}. "
            f"{source} → {target}: {row['outcome']} ({row['seconds']} s)")
    counts = {k: sum(1 for s in switches if s["outcome"] == k) for k in (ARRIVED, CAME_BACK, STUCK, REFUSED)}
    return {"device": device, "switches": switches, "counts": counts, "ok": counts[STUCK] == 0 and counts[REFUSED] == 0}


def debug_file(gw: Any, device: str) -> dict[str, Any] | None:
    """The phone's profile debug file, as Glass downloads it (already redacted twice)."""
    try:
        return gw.get(_path(device, "debug"))
    except Exception:  # noqa: BLE001 - an older phone has no debug route; the matrix still stands
        return None
