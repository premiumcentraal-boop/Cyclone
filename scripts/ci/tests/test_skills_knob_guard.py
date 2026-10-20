"""Alpha 151: the skills knob is one Lab switch on the phone, the gateway and the PC tools. Absent or true keeps today's
behaviour. False withholds the built-in tested Android routes (Instagram guidance and abilities advice) and nothing else:
it does not touch the publish gate, the boundaries, or approvals."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MINDLAB = "apps/mobile/app/src/main/java/com/cyclone/mobile/mind/lab/MindLab.kt"
MISSIONS = "apps/mobile/app/src/main/java/com/cyclone/mobile/mind/mission/MindMissions.kt"
TOOLBOX = "apps/mobile/app/src/main/java/com/cyclone/mobile/mind/PhoneMindToolbox.kt"
RUNNER = "apps/device-gateway/cyclone_device_gateway/lab/runner.py"
MCP_TOOLS = ("tools/codex-phone-mcp/cyclone_phone_mcp/tools.py", "tools/cyclone-agent-mcp/cyclone_agent_mcp/tools.py")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def quoted(block: str) -> set[str]:
    return set(re.findall(r'"(\w+)"', block))


class SkillsKnobGuard(unittest.TestCase):
    def test_the_phone_and_the_gateway_accept_the_same_knobs(self):
        phone = quoted(re.search(r"val KEYS = setOf\((.*?)\)", read(MINDLAB)).group(1))
        gateway = quoted(re.search(r"VARIANT_KEYS = frozenset\(\{(.*?)\}\)", read(RUNNER)).group(1))
        self.assertIn("skills", phone)
        self.assertEqual(phone, gateway, "a knob the phone refuses (or the PC never sends) is a silent gap")

    def test_the_pc_tools_offer_the_knob_and_nothing_the_phone_refuses(self):
        phone = quoted(re.search(r"val KEYS = setOf\((.*?)\)", read(MINDLAB)).group(1))
        for path in MCP_TOOLS:
            keys = quoted(re.search(r"LAB_VARIANT_KEYS = frozenset\(\{(.*?)\}\)", read(path)).group(1))
            self.assertIn("skills", keys, path)
            self.assertLessEqual(keys, phone, path)

    def test_the_gateway_never_fills_the_knob_in_for_an_older_phone(self):
        runner = read(RUNNER)
        self.assertNotIn('setdefault("skills"', runner)
        self.assertNotRegex(runner, r'["\']skills["\']\s*:\s*True')

    def test_the_phone_defaults_the_knob_to_on(self):
        lab = read(MINDLAB)
        self.assertIn("val skills: Boolean = true", lab)
        self.assertIn('json.optBooleanStrict("skills", true)', lab)
        self.assertIn('.put("skills", skills)', lab)

    def test_every_instagram_guidance_injection_is_gated_by_the_knob(self):
        missions = read(MISSIONS)
        self.assertIn("val skillsOn = variant?.skills != false", missions)
        injections = [line for line in missions.splitlines() if "InstagramSkills.forGoal(run.mission.goal)" in line]
        self.assertEqual(2, len(injections), "the OneMind opening and the non-OneMind system prompt")
        for line in injections:
            self.assertIn("takeIf { skillsOn }", line)
        self.assertIn("nativeSkills = skillsOn", missions)

    def test_the_toolbox_withholds_route_advice_only_when_the_knob_is_off(self):
        toolbox = read(TOOLBOX)
        self.assertIn("private val nativeSkills: Boolean = true", toolbox)
        self.assertIn("if (nativeSkills && ", toolbox)
        self.assertIn("InstagramSkills.PACKAGE", toolbox)
        self.assertIn("InstagramSkills.advice(goalText)", toolbox)

    def test_the_publish_gate_is_not_part_of_the_knob(self):
        # A skills-off Lab run still gates a posting goal's final Share as a send: the knob is guidance, not a boundary.
        missions = read(MISSIONS)
        gate = [line for line in missions.splitlines() if "PublishGate.mark(initial.id, true)" in line or "POST)" in line]
        self.assertTrue(gate, "the posting gate is still there")
        for line in gate:
            self.assertNotIn("skillsOn", line)


if __name__ == "__main__":
    unittest.main()
