"""Ablation research on Cyclone's app maps, its memory and its built-in skills, measured on the real phone.

Two phases, each one Lab experiment with four arms. Every arm sets all three knobs explicitly, because the phone
defaults a knob that a variant leaves out (freshMemory defaults to fresh, so a variant without it starts with no
owner memory). A research arm that leaves one out is refused before anything is sent.

  no-skills  suite ig-maps: app map on or off, by fresh memory or owner memory. No built-in skill on any arm.
  skills     suite ig-skills: built-in skill on or off, by app map on or off. Fresh memory on every arm.

The report pools each factor across the arms of one experiment and compares the two sides with a Wilson interval on
each rate, a Newcombe interval on the difference, and a two-sided Fisher exact p. Infra and skipped trials are counted
apart and never enter a rate. Every number comes from the phone's own record of the run.
"""
from __future__ import annotations

import json
import math
import re
import statistics
import time
from fractions import Fraction
from math import comb
from pathlib import Path
from typing import Any

from .results import redact_text

PACK_FILE = "testbench-instagram.json"
PACK = Path(__file__).resolve().parent.parent / "missions" / PACK_FILE
KNOBS = ("useMap", "freshMemory", "skills")
#: What the phone uses when a variant leaves a knob out. Shown in every report so no arm is read against the wrong default.
LAB_DEFAULTS = {"useMap": True, "freshMemory": True, "skills": True}
VARIANT_NAME = re.compile(r"^[A-Za-z0-9 ._-]{1,40}$")
NAME = re.compile(r"^[a-z0-9][a-z0-9_-]{0,47}$")
EXPERIMENT_ID = re.compile(r"^exp-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$")
SAFETY = frozenset({"missed_boundary", "boundary_broken"})
Z = 1.96

#: phase -> suite, the question, and the arms as (name, useMap, freshMemory, skills).
PHASES: dict[str, dict[str, Any]] = {
    "no-skills": {
        "suite": "ig-maps",
        "question": "Does the learned app map help, and does owner memory help, when Cyclone has no built-in Instagram skill?",
        "arms": [
            ("maps-fresh", True, True, False),
            ("maps-memory", True, False, False),
            ("nomaps-fresh", False, True, False),
            ("nomaps-memory", False, False, False),
        ],
    },
    "skills": {
        "suite": "ig-skills",
        "question": "Does the built-in Instagram skill help, with and without the learned app map? Fresh memory on every arm.",
        "arms": [
            ("skills-maps", True, True, True),
            ("skills-nomaps", False, True, True),
            ("noskills-maps", True, True, False),
            ("noskills-nomaps", False, True, False),
        ],
    },
}

#: factor -> the knob, the value that means "on", and what "off" is called in the report.
FACTORS: dict[str, dict[str, Any]] = {
    "app map": {"knob": "useMap", "on": True, "on_label": "map on", "off_label": "map off"},
    "owner memory": {"knob": "freshMemory", "on": False, "on_label": "owner memory on", "off_label": "fresh (no memory)"},
    "built-in skill": {"knob": "skills", "on": True, "on_label": "skill on", "off_label": "skill off"},
}


class ResearchError(ValueError):
    pass


def check_name(name: str) -> str:
    if not NAME.match(name or ""):
        raise ResearchError("A report name is 1..48 lowercase letters, digits, dash or underscore.")
    return name


def check_experiment_id(exp_id: str) -> str:
    """Experiment ids are fetched from the gateway's path, so only the id's own shape gets through."""
    if not EXPERIMENT_ID.match(exp_id or ""):
        raise ResearchError(f"{exp_id!r} is not an experiment id (exp-YYYYMMDD-HHMMSS-xxxx).")
    return exp_id


def explicit_variant(raw: Any) -> dict[str, Any]:
    """A variant with a name and all three knobs set. A knob left out would fall back to the phone's default."""
    if not isinstance(raw, dict) or not isinstance(raw.get("name"), str) or not VARIANT_NAME.match(raw["name"]):
        raise ResearchError("A variant has a name of 1..40 letters, digits, space, dot, dash or underscore.")
    name = raw["name"]
    missing = [knob for knob in KNOBS if knob not in raw]
    if missing:
        raise ResearchError(f"variant {name} leaves out {', '.join(missing)}; the phone would use its own default, "
                            "so the arm would not say what it measures. Set every knob.")
    wrong = [knob for knob in KNOBS if not isinstance(raw[knob], bool)]
    if wrong:
        raise ResearchError(f"variant {name}: {', '.join(wrong)} must be true or false")
    return {"name": name, **{knob: raw[knob] for knob in KNOBS}}


def arms_for(phase: str) -> list[dict[str, Any]]:
    """The phase's arms as variant dicts for the Lab, each one checked by explicit_variant."""
    spec = _phase(phase)
    return [explicit_variant({"name": name, "useMap": use_map, "freshMemory": fresh, "skills": skills})
            for name, use_map, fresh, skills in spec["arms"]]


def _phase(phase: str) -> dict[str, Any]:
    if phase not in PHASES:
        raise ResearchError(f"phase is one of {', '.join(PHASES)}")
    return PHASES[phase]


def pack_missions(suite: str, path: Path = PACK) -> list[dict[str, Any]]:
    rows = json.loads(path.read_text(encoding="utf-8"))
    return [row for row in rows if suite in (row.get("suites") or [])]


def plan_text(phase: str, *, reps: int, path: Path = PACK) -> str:
    spec = _phase(phase)
    missions = pack_missions(spec["suite"], path)
    arms = arms_for(phase)
    trials = len(missions) * len(arms) * reps
    lines = [
        f"Phase {phase} · suite {spec['suite']} · {len(missions)} missions × {len(arms)} arms × {reps} reps = {trials} trials",
        f"Question: {spec['question']}",
        "",
        "Arms (every knob set):",
    ]
    for arm in arms:
        lines.append(f"  {arm['name']:<16} app map {'on ' if arm['useMap'] else 'off'} · "
                     f"memory {'fresh' if arm['freshMemory'] else 'owner'} · built-in skill {'on ' if arm['skills'] else 'off'}")
    lines += [
        "",
        "Missions: " + ", ".join(m["id"] for m in missions),
        "",
        "The phone must run alpha 151 or later: it is the first build that knows the `skills` knob. An older phone refuses "
        "the variant with 'Unknown variant field', so the run never starts.",
        f"Start it: cyclone-testbench research start --phase {phase} --reps {reps}",
    ]
    return "\n".join(lines)


# ---- statistics --------------------------------------------------------------------------------------------------------

def wilson(successes: int, n: int, z: float = Z) -> tuple[float, float] | None:
    """Wilson score interval for a proportion. None when there is nothing to measure."""
    if n <= 0:
        return None
    p = successes / n
    z2 = z * z
    denom = 1 + z2 / n
    centre = (p + z2 / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denom
    return max(0.0, centre - half), min(1.0, centre + half)


def newcombe(k1: int, n1: int, k2: int, n2: int) -> tuple[float, float, float] | None:
    """Difference of two proportions (p1 - p2) with Newcombe's interval, built from the two Wilson intervals."""
    w1, w2 = wilson(k1, n1), wilson(k2, n2)
    if w1 is None or w2 is None:
        return None
    p1, p2 = k1 / n1, k2 / n2
    d = p1 - p2
    low = d - math.sqrt((p1 - w1[0]) ** 2 + (w2[1] - p2) ** 2)
    high = d + math.sqrt((w1[1] - p1) ** 2 + (p2 - w2[0]) ** 2)
    return d, low, high


def fisher_two_sided(a: int, b: int, c: int, d: int) -> float:
    """Two-sided Fisher exact p for the table [[a, b], [c, d]]: the sum of every table no more likely than this one."""
    row1, row2 = a + b, c + d
    col1, n = a + c, a + b + c + d
    if n == 0 or row1 == 0 or row2 == 0 or col1 == 0 or col1 == n:
        return 1.0

    def prob(x: int) -> Fraction:
        return Fraction(comb(col1, x) * comb(n - col1, row1 - x), comb(n, row1))

    observed = prob(a)
    total = Fraction(0)
    for x in range(max(0, row1 - (n - col1)), min(row1, col1) + 1):
        if prob(x) <= observed:
            total += prob(x)
    return min(1.0, float(total))


def median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


# ---- reading experiments -------------------------------------------------------------------------------------------------

def _scored(trials: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [t for t in trials if t.get("verdict") in {"pass", "fail"}]


def _phone(trial: dict[str, Any]) -> dict[str, Any]:
    return trial.get("phone") if isinstance(trial.get("phone"), dict) else {}


def arm_stats(trials: list[dict[str, Any]]) -> dict[str, Any]:
    scored = _scored(trials)
    passes = sum(1 for t in scored if t["verdict"] == "pass")
    seconds = [t["durationMs"] / 1000 for t in scored if isinstance(t.get("durationMs"), (int, float))]
    turns = [_phone(t)["turns"] for t in scored if isinstance(_phone(t).get("turns"), (int, float))]
    costs = [_phone(t)["costUsd"] for t in scored if isinstance(_phone(t).get("costUsd"), (int, float))]
    return {
        "total": len(trials), "scored": len(scored), "passes": passes,
        "infra": sum(1 for t in trials if t.get("verdict") == "infra"),
        "skipped": sum(1 for t in trials if t.get("verdict") == "skipped"),
        "wilson": wilson(passes, len(scored)),
        "median_s": median(seconds), "median_turns": median(turns),
        "mean_cost": sum(costs) / len(costs) if costs else None,
        "safety": sum(1 for t in trials if t.get("category") in SAFETY),
        "false_success": sum(1 for t in trials if t.get("category") == "false_success"),
    }


def _knobs(variant: dict[str, Any] | None) -> dict[str, Any] | None:
    """The knobs an arm really set, or None when it leaves one out (its numbers then say nothing about one factor)."""
    if not variant or any(knob not in variant for knob in KNOBS):
        return None
    return {knob: bool(variant[knob]) for knob in KNOBS}


def summarize_experiment(payload: dict[str, Any]) -> dict[str, Any]:
    experiment = payload.get("experiment") or {}
    trials = payload.get("trials") or []
    variants = {v.get("name"): v for v in experiment.get("variants") or [] if isinstance(v, dict)}
    arms = {}
    for name in sorted({t.get("variant") for t in trials if t.get("variant")}):
        arm_trials = [t for t in trials if t.get("variant") == name]
        arms[name] = {
            "knobs": _knobs(variants.get(name)),
            "stats": arm_stats(arm_trials),
            "first": arm_stats([t for t in arm_trials if t.get("rep") == 0]),
            "later": arm_stats([t for t in arm_trials if isinstance(t.get("rep"), int) and t["rep"] >= 1]),
        }
    return {"experiment": experiment, "trials": trials, "arms": arms}


def _factor_sides(summary: dict[str, Any], factor: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]] | None:
    spec = FACTORS[factor]
    on: list[dict[str, Any]] = []
    off: list[dict[str, Any]] = []
    for name, arm in summary["arms"].items():
        if arm["knobs"] is None:
            continue
        arm_trials = [t for t in summary["trials"] if t.get("variant") == name]
        (on if arm["knobs"][spec["knob"]] == spec["on"] else off).extend(arm_trials)
    if not on or not off:
        return None
    return on, off


def compare_factor(summary: dict[str, Any], factor: str) -> dict[str, Any] | None:
    sides = _factor_sides(summary, factor)
    if sides is None:
        return None
    on, off = (arm_stats(side) for side in sides)
    if on["scored"] == 0 or off["scored"] == 0:
        return None
    diff = newcombe(on["passes"], on["scored"], off["passes"], off["scored"])
    p = fisher_two_sided(on["passes"], on["scored"] - on["passes"], off["passes"], off["scored"] - off["passes"])
    return {"on": on, "off": off, "diff": diff, "p": p}


# ---- report ------------------------------------------------------------------------------------------------------------

def _pct(value: float | None) -> str:
    return "–" if value is None else f"{value * 100:.0f}%"


def _rate(stats: dict[str, Any]) -> str:
    if stats["scored"] == 0:
        return "– (0 scored)"
    lo, hi = stats["wilson"]
    return f"{_pct(stats['passes'] / stats['scored'])} ({_pct(lo)}–{_pct(hi)}) n={stats['scored']}"


def _secs(value: float | None) -> str:
    return "–" if value is None else f"{value:.0f} s"


def _arm_knobs(knobs: dict[str, Any] | None) -> str:
    if knobs is None:
        return "knobs not all set (Lab defaults assumed; excluded from factors)"
    return (f"{'map on' if knobs['useMap'] else 'map off'} · {'fresh' if knobs['freshMemory'] else 'owner memory'} · "
            f"{'skill on' if knobs['skills'] else 'skill off'}")


def build_report(name: str, payloads: list[dict[str, Any]], *, when: float | None = None) -> str:
    check_name(name)
    summaries = [summarize_experiment(p) for p in payloads]
    safety = sum(s["stats"]["safety"] for summary in summaries for s in summary["arms"].values())
    false_success = sum(s["stats"]["false_success"] for summary in summaries for s in summary["arms"].values())
    stamp = time.strftime("%Y-%m-%d %H:%M", time.localtime(when if when is not None else time.time()))
    lines = [f"# Research: {name}", "", f"Written {stamp}. Every number is from the phone's own record of each run.", "",
             "## Lab defaults", "",
             "A variant that leaves a knob out gets the phone's default: app map on, memory fresh (no owner memory), "
             "built-in skill on.",
             "This report compares only arms that set all three knobs.", "", "## Safety", ""]
    if safety:
        lines.append(f"**SAFETY FAILURE: {safety} run(s) acted without approval (missed boundary or broken boundary). "
                     "Stop the research and look at these runs before anything else.**")
    else:
        lines.append("Safety failures (acted without approval): **0**.")
    lines.append(f"False successes (said done, the phone disagrees): {false_success}.")
    for summary in summaries:
        experiment = summary["experiment"]
        trials = summary["trials"]
        infra = sum(1 for t in trials if t.get("verdict") == "infra")
        lines += ["", f"## {experiment.get('name') or experiment.get('id') or 'experiment'}", "",
                  f"Experiment `{experiment.get('id')}` · status {experiment.get('status')} · {len(trials)} trials · "
                  f"{infra} infra (not counted in any rate).", "",
                  "| Arm | Knobs | Scored | Pass (95% Wilson) | Median time | Median turns | Mean cost | Infra | Skipped |",
                  "|---|---|---|---|---|---|---|---|---|"]
        for arm_name, arm in summary["arms"].items():
            st = arm["stats"]
            cost = "–" if st["mean_cost"] is None else f"${st['mean_cost']:.3f}"
            lines.append(f"| {arm_name} | {_arm_knobs(arm['knobs'])} | {st['scored']} | {_rate(st)} | {_secs(st['median_s'])} | "
                         f"{'–' if st['median_turns'] is None else round(st['median_turns'])} | {cost} | {st['infra']} | {st['skipped']} |")
        lines += ["", "### Factors", "", "| Factor | Side on | Pass rate on | Side off | Pass rate off | Difference on minus off (95% Newcombe) | Fisher p |",
                  "|---|---|---|---|---|---|---|"]
        for factor, spec in FACTORS.items():
            result = compare_factor(summary, factor)
            if result is None:
                lines.append(f"| {factor} | {spec['on_label']} | – | {spec['off_label']} | – | not compared (one side has no arm) | – |")
                continue
            d, low, high = result["diff"]
            lines.append(f"| {factor} | {spec['on_label']} | {_rate(result['on'])} | {spec['off_label']} | {_rate(result['off'])} | "
                         f"{d * 100:+.0f} pts ({low * 100:+.0f} to {high * 100:+.0f}) | {result['p']:.3f} |")
        lines += ["", "### Repeats (the memory question)", "",
                  "Records number reps from 0. Rep 1 is the first run of each mission; reps 2 and 3 are later runs of the same "
                  "mission in the same arm.", "",
                  "| Arm | Rep 1 pass · median time | Reps 2–3 pass · median time |", "|---|---|---|"]
        for arm_name, arm in summary["arms"].items():
            first, later = arm["first"], arm["later"]
            lines.append(f"| {arm_name} | {_rate(first)} · {_secs(first['median_s'])} | {_rate(later)} · {_secs(later['median_s'])} |")
    lines += ["", "## How to read it", "",
              "- A difference counts when its interval leaves zero and the Fisher p is under 0.05. With a few dozen scored runs "
              "per side the intervals are wide; say so before you act on a small gap.",
              "- Owner memory reaches the owner's own memory and recent missions. Keep the owner's account out of the picture "
              "where you can: use a test account for the Instagram runs.",
              "- Nothing here was measured on a phone by this report. It only reads what the phone recorded."]
    return redact_text("\n".join(lines) + "\n")
