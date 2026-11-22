"""Plan 76, stage 1 (run 1b): the workspace follows the live channel; it does not poll.

Counts polling timers in Glass's workspace (``setInterval``) and holds them at zero, and checks each view that used to
poll now follows the live channel. A one-shot ``setTimeout`` (a debounce, a retry after an edit closes) is not polling.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WORKSPACE = ROOT / "apps/glass/src/workspace"
FOLLOWERS = ("pageView.ts", "sidebar.ts", "home.ts", "tableView.ts", "views.ts", "plan.ts")
#: The workspace's polling timers. It may only go down; it is zero since run 1b.
ALLOWED_POLLING_TIMERS = 0


class GlassLiveGuard(unittest.TestCase):
    def test_the_workspace_has_no_polling_timers(self):
        found = {p.name: len(re.findall(r"\bsetInterval\s*\(", p.read_text(encoding="utf-8"))) for p in WORKSPACE.glob("*.ts")}
        polling = {name: n for name, n in found.items() if n}
        self.assertLessEqual(sum(polling.values()), ALLOWED_POLLING_TIMERS, f"follow the live channel instead: {polling}")

    def test_every_view_that_polled_follows_the_live_channel(self):
        for name in FOLLOWERS:
            text = (WORKSPACE / name).read_text(encoding="utf-8")
            self.assertIn('from "../services/live.js"', text, name)
            self.assertRegex(text, r"follow\(ctx\.live,", name)

    def test_one_channel_per_tab_on_the_gateway_route(self):
        live = (ROOT / "apps/glass/src/services/live.ts").read_text(encoding="utf-8")
        self.assertIn("/v1/cc/live", live)
        self.assertIn("OFFLINE_AFTER_MS = 3_000", live)
        app = (ROOT / "apps/glass/src/app.ts").read_text(encoding="utf-8")
        self.assertIn("createLive(", app)
        self.assertEqual(1, app.count("createLive("), "one live channel per tab")


if __name__ == "__main__":
    unittest.main()
