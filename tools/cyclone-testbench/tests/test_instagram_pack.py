"""The Instagram app-map pack: every mission parses with the gateway, stays read-only, and copies the built-in skill goals
exactly from the phone's source. The gateway package is required; a missing import fails the tests, it does not skip."""
from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

try:
    from cyclone_device_gateway.lab.missions import parse_mission
except ImportError as exc:  # reported by the tests below, never skipped
    parse_mission, IMPORT_ERROR = None, str(exc)
else:
    IMPORT_ERROR = ""

ROOT = Path(__file__).resolve().parents[3]
PACK = Path(__file__).resolve().parents[1] / "missions" / "testbench-instagram.json"
KOTLIN = ROOT / "apps/mobile/app/src/main/java/com/cyclone/mobile/market/InstagramSkills.kt"
PACKAGE = "com.instagram.android"
SUITES = {"ig-maps", "ig-skills"}
#: The placeholders the skill takes as inputs, and the fixed values the pack uses (generated from the source, see GUIDE.md).
SUBSTITUTIONS = {
    "{media}": "the newest photo in my Gallery",
    "{caption}": "Cyclone lab test, do not share",
    "{mode}": "Review only",
    "{query}": "instagram",
}
#: Action verbs a read-only goal must not ask for. A clause may name one only with a negation, or as a label.
ACTION = re.compile(r"\b(send|sends|sending|post|posts|publish|publishes|submit|follow|follows|like|likes|comment|comments|"
                    r"delete|deletes|pay|pays|accept|reply|save|saves|share|type|typing|change|changes|toggle|remove|log|"
                    r"sign)\b", re.I)
NEGATION = re.compile(r"\b(do not|don't|never|without|nor|no|not|only)\b|stop before", re.I)
#: Navigation labels and list names that contain an action word but only name a screen, a menu item or a list.
LABELS = ("Share profile", "Create > Post", "Message > Requests", "message requests", "Settings and activity", "Tagged posts",
          "people who follow me")
#: Screen words the chrome checks may use. A check on any other word would read content, not the app's chrome.
CHROME = {"Reels", "Requests", "Primary", "General", "Tagged", "Remove", "Close friends", "Edit profile", "Share profile",
          "Username", "Bio", "Accounts", "Collections", "Series", "QR code", "Account Center", "Your activity",
          "Hidden requests", "Tag people", "Add location", "Share"}
BANNED_ACCOUNT_SKILLS = ("tb.ig.skill.account-setup", "tb.ig.skill.comments", "tb.ig.skill.cold-dms", "tb.ig.skill.reels-follow",
                         "tb.ig.skill.reels-warmup")


def load_pack() -> list[dict]:
    return json.loads(PACK.read_text(encoding="utf-8"))


def kotlin_descriptions() -> dict[str, str]:
    """Skill id -> 'Instagram skill: <name>. <goal>', read from the phone's source the same way the phone builds it."""
    src = KOTLIN.read_text(encoding="utf-8")
    pattern = re.compile(r'skill\(\s*"([^"]+)",\s*"([^"]+)",\s*"((?:[^"\\]|\\.)*)",\s*"((?:[^"\\]|\\.)*)"', re.S)
    return {sid: f"Instagram skill: {name}. {goal}" for sid, name, _summary, goal in pattern.findall(src)}


class PackParses(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(parse_mission, f"the gateway package must import: pip install -e apps/device-gateway ({IMPORT_ERROR})")

    def test_every_mission_parses_with_the_gateway(self):
        problems = []
        for raw in load_pack():
            try:
                parse_mission(raw)
            except ValueError as exc:
                problems.append(str(exc))
        self.assertEqual(problems, [])

    def test_ids_are_unique_prefixed_and_the_file_is_named_for_the_lab(self):
        rows = load_pack()
        ids = [row["id"] for row in rows]
        self.assertEqual(len(ids), len(set(ids)), "duplicate mission ids")
        self.assertTrue(all(i.startswith("tb.ig.") for i in ids))
        self.assertTrue(PACK.name.startswith("testbench-") and PACK.name.endswith(".json"))
        self.assertLessEqual(len(rows), 50, "the Lab reads at most 50 mission files; keep the pack small")

    def test_every_mission_is_in_one_research_suite_on_instagram(self):
        for row in load_pack():
            with self.subTest(mission=row["id"]):
                self.assertEqual(len(set(row["suites"]) & SUITES), 1, "one ig suite per mission")
                self.assertEqual(set(row["suites"]) - SUITES, set(), "no other suites")
                self.assertEqual(row["category"], "instagram")
                self.assertEqual(row["apps"], [PACKAGE])
                self.assertEqual(row.get("expect", "done"), "done", "read-only missions never wait at a boundary")
                self.assertEqual(row["setup"][0], {"do": "force_stop", "package": PACKAGE})

    def test_both_suites_are_present_with_about_eight_missions(self):
        counts = {suite: sum(1 for row in load_pack() if suite in row["suites"]) for suite in SUITES}
        self.assertTrue(all(6 <= n <= 10 for n in counts.values()), counts)


class ReadOnly(unittest.TestCase):
    def clauses(self, goal: str) -> list[str]:
        text = goal
        if text.startswith("Instagram skill: "):
            text = text.split(". ", 1)[1]  # the marker's skill name is a label, not an instruction
        for label in LABELS:
            text = text.replace(label, "")
        return [c.strip() for c in re.split(r"[.;]", text) if c.strip()]

    def test_no_goal_asks_for_an_action_that_changes_anything(self):
        for row in load_pack():
            for clause in self.clauses(row["goal"]):
                if not ACTION.search(clause) or NEGATION.search(clause):
                    continue
                with self.subTest(mission=row["id"], clause=clause):
                    self.fail("a read-only goal names an action without a negation")

    def test_no_goal_reads_the_owner_gallery_or_a_photo(self):
        # A Lab run sends screens to a model; a goal that picks the owner's newest photo would send private photos.
        for row in load_pack():
            self.assertNotRegex(row["goal"], r"(?i)gallery|photo", row["id"])

    def test_no_goal_keeps_a_placeholder(self):
        for row in load_pack():
            self.assertNotRegex(row["goal"], r"\{[a-z]+\}", row["id"])

    def test_excluded_skills_never_appear(self):
        rows = load_pack()
        ids = {row["id"] for row in rows}
        for banned in BANNED_ACCOUNT_SKILLS:
            self.assertNotIn(banned, ids)
        text = " ".join(row["goal"] + " " + row["title"] for row in rows)
        for phrase in ("Set up an Instagram account", "Read Instagram comments", "cold DM", "reels following", "Create an account"):
            self.assertNotIn(phrase, text)

    def test_every_chrome_check_uses_only_chrome_words(self):
        for row in load_pack():
            for check in row["checks"]:
                words = list(check.get("any", [])) + list(check.get("all", [])) + list(check.get("selected", []))
                for word in words:
                    with self.subTest(mission=row["id"], word=word):
                        self.assertIn(word, CHROME, "a check names something that is not in the app's chrome")


class SkillGoals(unittest.TestCase):
    def test_skill_goals_are_the_phone_source_text_with_the_fixed_inputs(self):
        self.assertTrue(KOTLIN.is_file(), f"the phone's skill source is missing: {KOTLIN}")
        descriptions = kotlin_descriptions()
        for row in load_pack():
            if not row["id"].startswith("tb.ig.skill."):
                continue
            skill_id = row["id"][len("tb.ig.skill."):]
            with self.subTest(skill=skill_id):
                self.assertIn(skill_id, descriptions, "no such skill in InstagramSkills.kt")
                expected = descriptions[skill_id]
                for key, value in SUBSTITUTIONS.items():
                    expected = expected.replace(key, value)
                self.assertEqual(row["goal"], expected)

    def test_skill_goals_carry_the_marker_and_maps_goals_do_not(self):
        for row in load_pack():
            if row["id"].startswith("tb.ig.skill."):
                self.assertTrue(row["goal"].startswith("Instagram skill: "), row["id"])
            else:
                self.assertFalse(row["goal"].startswith("Instagram skill:"), row["id"])

    def test_every_skill_in_the_pack_is_read_only_by_its_own_listing(self):
        allowed = {"profile", "profile-editor", "search-accounts", "saved", "share-profile", "settings", "requests"}
        self.assertEqual({row["id"][len("tb.ig.skill."):] for row in load_pack() if row["id"].startswith("tb.ig.skill.")}, allowed)


if __name__ == "__main__":
    unittest.main()
