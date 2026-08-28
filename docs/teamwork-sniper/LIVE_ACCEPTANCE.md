# Teamwork Sniper Live Acceptance

## One-command path

Once the Pixel is online, the intended first pass is:

```powershell
adb devices
python tools/teamwork-sniper-probe/live_acceptance.py --serial 3B171FDJH0061G
```

The command validates the serial, collects Android and Teamwork package/version data, launches `tech.picnic.workapp/.MainActivity`, performs a `uiautomator dump`, parses normalized candidate shifts, inventories semantic `scrollable="true"` nodes, captures Teamwork notification diagnostics from `dumpsys notification`, records per-command latencies, and writes:

`tools/teamwork-sniper-probe/output/live_acceptance.json`

Transient pulled XML is deleted after parsing. Do not commit output blindly; sanitize and promote only intentional fixtures with provenance.

## If the calendar is not the current screen

The tool deliberately does not contain a historical coordinate or guessed navigation click. If the initial hierarchy contains no useful calendar/scrollable semantics, navigate to Teamwork's shift/calendar screen using a semantic control if available and rerun the same command. If semantic navigation is not discoverable, perform the minimum manual navigation needed, then rerun.

## Full-week follow-up

After the first live hierarchy identifies the actual semantic scroll container/action, add that selector only if it is live-confirmed. Then rerun with repeated dump/parse cycles until:

- no new normalized shifts appear; and
- the semantic fingerprint repeats; or
- the semantic scroll action reports failure.

The multi-dump parser already supports:

```powershell
python tools/teamwork-sniper-probe/probe.py page1.xml page2.xml page3.xml --evidence-level LIVE_CONFIRMED
```

## Claim path

Do not claim an unrelated shift. For a safe target only, record pre-action XML, activate the live-confirmed semantic claim node, record any confirmation hierarchy, and record the post-action hierarchy. A click is never considered proof of success by itself.

## Required promotion of evidence

Synthetic assumptions are not preserved by force. Update `ACCESSIBILITY_CONTRACT.md` after live validation so every provisional rule is explicitly marked either:

- `LIVE CONFIRMED`, or
- `REJECTED BY LIVE EVIDENCE`.

Real fixtures must record: device serial, capture UTC/local time, Android version, Teamwork versionName/versionCode, screen/state, and whether any user action occurred.

## Safety audit

Run:

```powershell
python tools/teamwork-sniper-probe/safety_guard.py tools/teamwork-sniper-probe
```

Expected result: PASS.
