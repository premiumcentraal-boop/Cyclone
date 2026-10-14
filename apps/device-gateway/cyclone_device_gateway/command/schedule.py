"""Routine schedules for the Command Center (plan 33, C0; richer kinds in roadmap run 16). Small, checkable kinds
instead of full RRULE:

- ``{"kind": "daily", "time": "07:30", "days": [1, 2, 3, 4, 5]}``: at a local time on ISO weekdays (1 = Monday);
- ``{"kind": "every", "minutes": 120}``: every N minutes (15 minutes to 7 days), counted from the last run;
- ``{"kind": "monthly", "time": "09:00", "day": 15}``: on a day of the month (-1 = the last day; a month without
  that day runs on its last day);
- ``{"kind": "nth", "time": "09:00", "weekday": 1, "nth": 1}``: the first (1..4) or last (-1) weekday of the month,
  for example the first Monday;
- triggers, which have no time of their own:
  - ``{"kind": "after", "routineId": "rtn_…", "on": "succeeded"}``: when a run of another routine ends
    (``succeeded``, ``failed`` or ``finished`` for either);
  - ``{"kind": "webhook"}``: when the routine's own secret link is called.

Missed runs are never replayed: the next run is always in the future.
"""
from __future__ import annotations

import re
import calendar
from datetime import datetime, timedelta
from typing import Any

TIME = re.compile(r"^([01][0-9]|2[0-3]):([0-5][0-9])$")
ROUTINE_ID = re.compile(r"^rtn_[A-Za-z0-9_-]{6,40}$")
MIN_EVERY = 15
MAX_EVERY = 7 * 24 * 60
TRIGGERS = ("after", "webhook")
AFTER_ON = ("succeeded", "failed", "finished")


class ScheduleError(ValueError):
    pass


def parse(raw: Any) -> dict[str, Any]:
    """A validated copy of [raw], or ScheduleError."""
    if not isinstance(raw, dict):
        raise ScheduleError("A schedule is an object.")
    kind = raw.get("kind")
    if kind == "daily":
        if set(raw) != {"kind", "time", "days"}:
            raise ScheduleError("A daily schedule has kind, time and days.")
        time = raw.get("time")
        if not isinstance(time, str) or not TIME.match(time):
            raise ScheduleError("time is HH:MM, 24-hour.")
        days = raw.get("days")
        if not isinstance(days, list) or not days or not all(type(d) is int and 1 <= d <= 7 for d in days):
            raise ScheduleError("days are ISO weekdays 1 (Monday) to 7 (Sunday).")
        return {"kind": "daily", "time": time, "days": sorted(set(days))}
    if kind == "every":
        if set(raw) != {"kind", "minutes"}:
            raise ScheduleError("An every schedule has kind and minutes.")
        minutes = raw.get("minutes")
        if type(minutes) is not int or not MIN_EVERY <= minutes <= MAX_EVERY:
            raise ScheduleError(f"minutes is {MIN_EVERY} to {MAX_EVERY}.")
        return {"kind": "every", "minutes": minutes}
    if kind == "monthly":
        if set(raw) != {"kind", "time", "day"}:
            raise ScheduleError("A monthly schedule has kind, time and day.")
        day = raw.get("day")
        if type(day) is not int or not (1 <= day <= 31 or day == -1):
            raise ScheduleError("day is 1 to 31, or -1 for the last day of the month.")
        return {"kind": "monthly", "time": _time(raw), "day": day}
    if kind == "nth":
        if set(raw) != {"kind", "time", "weekday", "nth"}:
            raise ScheduleError("An nth-weekday schedule has kind, time, weekday and nth.")
        weekday, nth = raw.get("weekday"), raw.get("nth")
        if type(weekday) is not int or not 1 <= weekday <= 7:
            raise ScheduleError("weekday is 1 (Monday) to 7 (Sunday).")
        if type(nth) is not int or not (1 <= nth <= 4 or nth == -1):
            raise ScheduleError("nth is 1 to 4, or -1 for the last one in the month.")
        return {"kind": "nth", "time": _time(raw), "weekday": weekday, "nth": nth}
    if kind == "after":
        if set(raw) != {"kind", "routineId", "on"}:
            raise ScheduleError("An after trigger has kind, routineId and on.")
        if not isinstance(raw.get("routineId"), str) or not ROUTINE_ID.match(raw["routineId"]):
            raise ScheduleError("routineId names the routine this one follows.")
        if raw.get("on") not in AFTER_ON:
            raise ScheduleError("on is succeeded, failed or finished.")
        return {"kind": "after", "routineId": raw["routineId"], "on": raw["on"]}
    if kind == "webhook":
        if set(raw) != {"kind"}:
            raise ScheduleError("A webhook trigger has only its kind; its link is made for it.")
        return {"kind": "webhook"}
    raise ScheduleError("kind is daily, every, monthly, nth, after or webhook.")


def _time(raw: dict[str, Any]) -> str:
    time = raw.get("time")
    if not isinstance(time, str) or not TIME.match(time):
        raise ScheduleError("time is HH:MM, 24-hour.")
    return time


def is_trigger(schedule: dict[str, Any]) -> bool:
    return schedule.get("kind") in TRIGGERS


def _day_in(year: int, month: int, schedule: dict[str, Any]) -> int:
    """The day of [month] a monthly or nth schedule runs on."""
    last = calendar.monthrange(year, month)[1]
    if schedule["kind"] == "monthly":
        return last if schedule["day"] == -1 else min(schedule["day"], last)
    weekday = schedule["weekday"] - 1  # Monday = 0, like datetime.weekday()
    first = (weekday - datetime(year, month, 1).weekday()) % 7 + 1
    if schedule["nth"] == -1:
        return first + 7 * ((last - first) // 7)
    return first + 7 * (schedule["nth"] - 1)  # the 4th always exists


def next_after(schedule: dict[str, Any], after: datetime) -> datetime | None:
    """The first run strictly after [after] (an aware local datetime); None for a trigger, which has no time."""
    if is_trigger(schedule):
        return None
    if schedule["kind"] == "every":
        return after + timedelta(minutes=schedule["minutes"])
    hour, minute = (int(part) for part in schedule["time"].split(":"))
    if schedule["kind"] in ("monthly", "nth"):
        year, month = after.year, after.month
        for _ in range(14):
            candidate = after.replace(year=year, month=month, day=_day_in(year, month, schedule), hour=hour, minute=minute,
                                      second=0, microsecond=0)
            if candidate > after:
                return candidate
            year, month = (year + 1, 1) if month == 12 else (year, month + 1)
        raise ScheduleError("No run in the next year.")  # unreachable
    days = set(schedule["days"])
    for offset in range(0, 8):
        day = after + timedelta(days=offset)
        candidate = day.replace(hour=hour, minute=minute, second=0, microsecond=0)
        if candidate > after and candidate.isoweekday() in days:
            return candidate
    raise ScheduleError("No run in the next week.")  # unreachable with at least one day


ORDINAL = {1: "First", 2: "Second", 3: "Third", 4: "Fourth", -1: "Last"}
WEEKDAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def _nth_day(day: int) -> str:
    if day == -1:
        return "the last day"
    suffix = "th" if 10 <= day % 100 <= 20 else {1: "st", 2: "nd", 3: "rd"}.get(day % 10, "th")
    return f"the {day}{suffix}"


def describe(schedule: dict[str, Any], *, routine_title: str | None = None) -> str:
    kind = schedule["kind"]
    if kind == "after":
        verb = {"succeeded": "succeeds", "failed": "fails", "finished": "finishes"}[schedule["on"]]
        return f"After “{routine_title or 'another routine'}” {verb}"
    if kind == "webhook":
        return "When its link is called"
    if kind == "monthly":
        return f"Monthly on {_nth_day(schedule['day'])} at {schedule['time']}"
    if kind == "nth":
        return f"{ORDINAL[schedule['nth']]} {WEEKDAY_NAMES[schedule['weekday'] - 1]} of the month at {schedule['time']}"
    if schedule["kind"] == "every":
        minutes = schedule["minutes"]
        if minutes % 1440 == 0:
            return f"Every {minutes // 1440} day(s)"
        if minutes % 60 == 0:
            return f"Every {minutes // 60} hour(s)"
        return f"Every {minutes} minutes"
    names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    days = schedule["days"]
    label = "Every day" if len(days) == 7 else "Weekdays" if days == [1, 2, 3, 4, 5] else ", ".join(names[d - 1] for d in days)
    return f"{label} at {schedule['time']}"
