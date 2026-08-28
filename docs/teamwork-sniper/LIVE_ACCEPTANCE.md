# Teamwork Sniper Live Acceptance

## Evidence status

No physical-device run was possible from the environment that created this branch. The commands below are the exact acceptance sequence required to convert the provisional contract into an observed contract. Do not mark Cyclone 3.5.2 release-complete until these have been executed against the target device and the resulting evidence committed.

## 1. Identity and versions

```powershell
adb -s 3B171FDJH0061G devices
adb -s 3B171FDJH0061G shell getprop ro.build.version.release
adb -s 3B171FDJH0061G shell wm size
adb -s 3B171FDJH0061G shell wm density
adb -s 3B171FDJH0061G shell dumpsys package tech.picnic.workapp
```

Record Android version, Teamwork versionName/versionCode, and timestamp.

## 2. Launch and semantic dump

```powershell
adb -s 3B171FDJH0061G shell am force-stop tech.picnic.workapp
adb -s 3B171FDJH0061G shell am start -W -n tech.picnic.workapp/.MainActivity
adb -s 3B171FDJH0061G shell uiautomator dump /sdcard/teamwork.xml
adb -s 3B171FDJH0061G pull /sdcard/teamwork.xml tools/teamwork-sniper-probe/fixtures/calendar_live.xml
python tools/teamwork-sniper-probe/probe.py tools/teamwork-sniper-probe/fixtures/calendar_live.xml --fail-on-ambiguity
```

Manual coordinates may be used only to reach the calendar during exploration; record them nowhere in production parsing rules.

## 3. Full-week traversal

For each semantic scroll action:

1. dump XML;
2. parse candidates;
3. record normalized fingerprint;
4. record scroll success/failure;
5. repeat until no new normalized candidates and fingerprint is unchanged, or scrolling fails.

Commit each genuinely distinct real capture with timestamp/provenance comments or a neighboring manifest.

## 4. Claim-path inspection

Do not claim an unrelated shift. For a safe target only:

- locate the open marker;
- trace nearest enabled clickable ancestor;
- dump pre-click tree;
- activate that semantic node;
- immediately dump any dialog/interstitial;
- if confirmation exists, record its semantic labels/actions;
- only complete a claim if the chosen target is explicitly safe;
- dump post-action tree and establish the success postcondition.

Record trigger-to-first-readable-tree timing with `am start -W` and timestamped dump calls.

## 5. Notifications

```powershell
adb -s 3B171FDJH0061G shell dumpsys notification
```

Extract only Teamwork entries. Record package, channel/category, title/body, contentIntent availability, and destination if safely invoked.

## 6. No-screenshot audit

Executable probe code must contain no screenshot/OCR/vision/image capture calls. Suggested audit:

```powershell
git grep -n -E "screencap|takeScreenshot|OCR|vision model|image parsing|pixel detection" -- tools/teamwork-sniper-probe
```

Expected: matches may exist only in documentation/comments explaining prohibitions; none may be executable calls.

## Acceptance answers required

A. Exact live tree pattern identifying an open shift.  
B. Exact row-binding boundary for date + code + time + state.  
C. Exact semantic clickable node.  
D. Observed success postcondition.  
E. Observed confirmation UI.  
F. Exact scrollable node/action.  
G. Repeatedly stable IDs/content descriptions.  
H. Ambiguity behavior (must fail closed).  
I. Whether Teamwork notification contentIntent reliably reaches calendar.  
J. Observed trigger-to-tree-read timing.

Until populated from physical-device evidence, A–G/I/J are explicitly **UNVERIFIED**, and D/E are **UNKNOWN**.
