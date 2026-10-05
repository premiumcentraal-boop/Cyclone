"""Alpha 108 testbench changes: root-cause grouping, re-checks only on a new build, the ADB preflight, report lines."""
import unittest

from cyclone_testbench import findings, preflight
from cyclone_testbench.report import dashboard, render
from cyclone_testbench.rotation import DEFAULT_CAMPAIGN, plan_batch

REFUSED = "Not done: Cyclone could not tell that control apart from other controls that overlap or match it (a safety check)"


def failed(mission, n_refusals=4, category="timeout", exp="exp-1"):
    events = [{"ok": False, "text": REFUSED} for _ in range(n_refusals)] + [{"ok": False, "text": 'ERROR: "x" is not on the map'}]
    return {"verdict": "fail", "category": category, "missionId": mission, "trialId": f"{exp}-{mission}", "experimentId": exp,
            "phone": {"turns": 40, "events": events}, "durationMs": 390000}


class RootCauseTests(unittest.TestCase):
    def test_one_cause_across_missions_is_one_group(self):
        cands = [findings.classify(failed(m)) for m in ("settings.timeout.2min", "tb.judge.do.font.implied", "boundary.delete.file")]
        self.assertEqual(len({c["signature"] for c in cands}), 1)
        ledger, _ = findings.merge([], cands, at=1, app_version="5.0.0-alpha.107.dev1")
        roots = findings.root_causes(ledger)
        self.assertEqual(len(roots), 1)
        self.assertEqual(len(roots[0]["findings"]), 3)
        self.assertIn("could not tell that control apart", roots[0]["signature"])
        self.assertIn("Root causes", dashboard([], ledger))

    def test_stuck_is_a_reliability_finding(self):
        cand = findings.classify(failed("settings.timeout.2min", category="stuck"))
        self.assertEqual((cand["area"], cand["severity"]), ("reliability", "medium"))


class RecheckTests(unittest.TestCase):
    catalog = [{"id": "settings.timeout.2min", "suites": ["smoke"]}, {"id": "clock.timer.5", "suites": ["smoke"]},
               {"id": "nav.home", "suites": ["smoke"]}]

    def ledger(self, version):
        led, _ = findings.merge([], [findings.classify(failed("settings.timeout.2min"))], at=1, app_version=version)
        return led

    def test_a_known_failure_is_not_rerun_on_the_same_build(self):
        plan = plan_batch(DEFAULT_CAMPAIGN, self.catalog, [], self.ledger("107"), app_version="107")
        self.assertNotIn("settings.timeout.2min", plan["missions"])
        self.assertIn("settings.timeout.2min", plan["skipped"])
        self.assertIn("clock.timer.5", plan["missions"])

    def test_it_is_rechecked_first_on_a_new_build(self):
        plan = plan_batch(DEFAULT_CAMPAIGN, self.catalog, [], self.ledger("107"), app_version="108")
        self.assertEqual(plan["missions"][0], "settings.timeout.2min")
        self.assertIn("new build", plan["reasons"]["settings.timeout.2min"])

    def test_a_slow_pass_is_not_rechecked_on_the_same_build_but_may_run_in_its_slot(self):
        slow = {"verdict": "pass", "category": "pass", "missionId": "nav.home", "phone": {"turns": 25}, "durationMs": 200000}
        led, _ = findings.merge([], [findings.classify(slow)], at=1, app_version="107")
        plan = plan_batch(DEFAULT_CAMPAIGN, self.catalog, [], led, app_version="107")
        self.assertFalse(any(r.startswith("re-check") for r in plan["reasons"].values()))
        self.assertIn("nav.home", plan["missions"])

    def test_without_a_known_version_it_behaves_as_before(self):
        plan = plan_batch(DEFAULT_CAMPAIGN, self.catalog, [], self.ledger("107"))
        self.assertIn("settings.timeout.2min", plan["missions"])


class FakePhone:
    def __init__(self, locked=False, stay="0", idle="", services="com.cyclone.mobile/.Overlay", level=80, temp=310,
                 packages=("com.android.settings",), dismissable=True):
        self.locked, self.stay, self.idle, self.services = locked, stay, idle, services
        self.level, self.temp, self.packages, self.dismissable = level, temp, set(packages), dismissable
        self.commands = []

    def __call__(self, args):
        self.commands.append(args)
        a = args[1:]
        if a == ["dumpsys", "window"]:
            return f"mShowingLockscreen={'true' if self.locked else 'false'}"
        if a == ["wm", "dismiss-keyguard"]:
            if self.dismissable:
                self.locked = False
            return ""
        if a[:3] == ["settings", "get", "global"]:
            return self.stay
        if a[:3] == ["settings", "put", "global"]:
            self.stay = a[4]
            return ""
        if a == ["dumpsys", "deviceidle", "whitelist"]:
            return self.idle
        if a[:3] == ["dumpsys", "deviceidle", "whitelist"]:
            self.idle += "\nuser,com.cyclone.mobile,10234"
            return ""
        if a[:3] == ["settings", "get", "secure"]:
            return self.services
        if a == ["dumpsys", "battery"]:
            return f"  level: {self.level}\n  temperature: {self.temp}\n"
        if a == ["pm", "list", "packages"]:
            return "\n".join(f"package:{p}" for p in self.packages)
        return ""


class PreflightTests(unittest.TestCase):
    def test_fix_puts_the_phone_right_and_reports_missing_apps_without_blocking(self):
        phone = FakePhone(locked=True)
        checks = {c.name: c for c in preflight.preflight(phone, fix=True, apps={"com.android.settings", "com.google.android.keep"})}
        self.assertTrue(all(c.ok for n, c in checks.items() if n != "mission apps installed"))
        self.assertTrue(checks["stays awake on power"].fixed and checks["Cyclone not put to sleep"].fixed)
        self.assertFalse(checks["mission apps installed"].ok)
        self.assertFalse(checks["mission apps installed"].blocking)
        self.assertIn(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], phone.commands)

    def test_a_pin_locked_phone_hot_battery_or_accessibility_off_blocks_the_round(self):
        phone = FakePhone(locked=True, dismissable=False, temp=455, services="")
        checks = {c.name: c for c in preflight.preflight(phone, fix=True)}
        self.assertFalse(checks["unlocked"].ok)
        self.assertFalse(checks["temperature"].ok)
        self.assertFalse(checks["Cyclone accessibility on"].ok)

    def test_without_fix_nothing_is_changed(self):
        phone = FakePhone()
        preflight.preflight(phone, fix=False)
        self.assertFalse(any(c[1] in {"input", "wm"} or c[1:3] == ["settings", "put"] for c in phone.commands))
        self.assertFalse(any(len(c) > 4 and c[1:4] == ["dumpsys", "deviceidle", "whitelist"] for c in phone.commands))

    def test_preflight_never_runs_destructive_commands(self):
        phone = FakePhone(locked=True)
        preflight.preflight(phone, fix=True, apps={"x.y"})
        flat = " ".join(" ".join(c) for c in phone.commands)
        for banned in ("uninstall", "pm clear", "rm ", "reboot", "factory", "locksettings"):
            self.assertNotIn(banned, flat)


class ReportTests(unittest.TestCase):
    def test_test_only_approvals_and_early_stops_are_listed(self):
        trials = [{"missionId": "tb.safety.delete.approved", "verdict": "pass", "category": "pass",
                   "owner": [{"kind": "approval", "action": "approve", "approved": {"gate": "delete"}}], "phone": {}},
                  failed("settings.timeout.2min", category="stuck")]
        payload = {"experiment": {"id": "exp-1", "name": "x", "status": "done"}, "trials": trials,
                   "stats": {"arms": {}}, "insights": []}
        text = render(payload, {}, {"new": 0, "repeat": 0, "regressed": 0}, [])
        self.assertIn("Test-only approvals by the lab:** 1 (tb.safety.delete.approved: delete)", text)
        self.assertIn("Stopped early as stuck:** 1", text)


if __name__ == "__main__":
    unittest.main()
