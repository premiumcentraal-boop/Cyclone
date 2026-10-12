# Cyclone testbench

Round-the-clock testing of Cyclone on a real phone, built on Cyclone Lab: mission packs (with the ask-or-do
"judgement" suite), a rotation that re-checks what broke, redacted reports and one findings ledger shared between
Claude sessions.

- People: read [GUIDE.md](GUIDE.md).
- Claude: the skill is [.claude/skills/cyclone-testing/SKILL.md](../../.claude/skills/cyclone-testing/SKILL.md).
- A new PC session starts from [HANDOFF.md](HANDOFF.md).

```
pip install -e apps/device-gateway -e tools/cyclone-testbench
cyclone-testbench doctor && cyclone-testbench install
cyclone-testbench run --next --wait
```
