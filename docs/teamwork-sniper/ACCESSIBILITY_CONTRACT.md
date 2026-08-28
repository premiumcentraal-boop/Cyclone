# Teamwork Sniper Accessibility Contract

## Scope and evidence status

Target package: `tech.picnic.workapp`  
Launcher: `tech.picnic.workapp/.MainActivity`  
Target device requested: Pixel serial `3B171FDJH0061G`, 1080x2400 @ 420 dpi.

This branch was authored from a ChatGPT environment that **does not expose the user's Windows ADB shell or physical device**. Therefore this document deliberately contains **no claimed live Teamwork observations**. Anything under "Provisional parser contract" is a fail-closed implementation contract to validate against a real `uiautomator dump` before production use.

No executable probe code calls screencap, screenshot APIs, OCR, vision, image parsing, or pixel detection.

## Required live capture commands

```powershell
adb -s 3B171FDJH0061G devices
adb -s 3B171FDJH0061G shell am start -n tech.picnic.workapp/.MainActivity
adb -s 3B171FDJH0061G shell uiautomator dump /sdcard/teamwork.xml
adb -s 3B171FDJH0061G pull /sdcard/teamwork.xml tools/teamwork-sniper-probe/fixtures/calendar_live.xml
adb -s 3B171FDJH0061G shell getprop ro.build.version.release
adb -s 3B171FDJH0061G shell dumpsys package tech.picnic.workapp
adb -s 3B171FDJH0061G shell dumpsys notification
```

A fixture must not be named as real unless produced from such a live semantic dump.

## Provisional parser contract

An open-shift candidate is admitted only when one smallest ancestor subtree contains:

1. exactly one semantic `Open to take` marker in `text` or `content-desc`;
2. exactly one recognized shift code (currently `M<n>` or `S<n>`);
3. exactly one start/end pair, either one range token such as `08:00–10:35` or exactly two distinct HH:MM tokens;
4. exactly one unambiguous date in a supported grammar;
5. an enabled clickable ancestor reachable from the open marker.

If any binding is ambiguous, the candidate is returned with `ambiguous=true`; production claim logic must reject it.

### Binding rule

Bind date + code + time + state only inside the smallest ancestor of the `Open to take` node that simultaneously contains a valid code and a valid time pair. Do not bind tokens across sibling shift-row ancestors or across day boundaries.

### Claim-node rule

The probe identifies, but never invokes, the nearest enabled ancestor of the `Open to take` marker with `clickable="true"`. This is only a candidate claim node until verified live. If none exists, fail closed.

### Success rule

No success grammar is asserted yet. Production logic must not infer success solely from a click. Live acceptance must establish a semantic postcondition such as a confirmation message, row-state transition, disappearance of `Open to take`, or appearance in a user's assigned-shift state.

## Scroll discovery algorithm

1. Dump and parse current hierarchy.
2. Add normalized tuples `(date, code, start, end, state)` to a set.
3. Identify the semantic node with `scrollable="true"` that contains shift rows.
4. Invoke semantic/uiautomator scroll on that container (not coordinate-based parser logic).
5. Dump again.
6. Stop when scroll reports failure, or both the normalized shift set and normalized semantic-tree fingerprint cease changing.
7. Deduplicate repeated rows across pages by normalized tuple.

The exact live scrollable class/resource-id and action remain unverified.

## Week/date algorithm

Prefer explicit date text/content descriptions associated with each row/day heading. If Teamwork exposes only day names under a semantically labelled week header, resolve dates only when the week anchor itself is unambiguous. Never guess a calendar year/week from device date alone when multiple interpretations are possible.

Week navigation must use semantic text/content-desc/resource-id discovered live. No coordinates belong in the parser contract.

## Stable identifiers

None are asserted as stable until captured repeatedly across live states/app relaunches. Resource IDs in `synthetic_*` fixtures are intentionally fake and must never ship into production selectors.

## Notifications

No live notification sample was available in this execution environment. The required contract is to record package, channel/category, title/body, contentIntent presence and destination after safe invocation. Do not assume contentIntent reaches the calendar.

## Fail-closed recommendations

- Reject multiple codes in one row scope.
- Reject more/fewer than two unambiguous times unless one valid range exists.
- Reject missing/ambiguous date.
- Reject missing clickable semantic ancestor.
- Reject cross-row token binding.
- Reject duplicate semantic candidates that disagree on date/time/code.
- Treat unknown confirmation/post-click state as failure, not success.
- Treat notification routing as untrusted until live-tested.
