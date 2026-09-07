# Cyclone Mobile 4.0.3 — Layer 2 Workspaces + Root Features

Base: published `v4.0.1` at `44446e4b6fa18833670a1eafe7803b1a9490375c`. Mobile 4.0.3 / versionCode 74. This uses 4.0.3 because 4.0.2 was already published before this Layer 2 branch was created.

## Layer 2 workspaces

- Adds a persistent workspace model and registry: id, label, app package, Android user ID, display ID and idle/running/paused/gated state.
- Adds one Layer 2 mutate-lock owner. Switching releases the previous owner before launch, verifies package/user/display, re-binds Accessibility observation, and acquires the new owner only after successful verification.
- Switch failure is fail-closed: launch, package, user, display or observation mismatch never grants mutation ownership.
- Human-confirmation GATE state remains authoritative. A gated current or target workspace blocks switching rather than handing control around the gate.
- Layer 2 switching intentionally remains display-0 only. Android user IDs other than 0 are stored for future/external isolation, but this build does not invent cross-user launch authority.
- The existing PhoneToolExecutor mutation monitor, Accessibility service, Session Kernel identity rules, Fast Path and background workspace engine remain the action authority. No second mutation engine or second Accessibility service was added.

## Setup → Root features

A first-class **Root features** card now lives in the existing Background tasks setup flow. It shows **Rooted / Not rooted / Unknown** using conservative, non-crashing signals (PATH/common `su` locations, readable Magisk indicators and build tags). Detection never executes `su`, installs root software, patches boot images or changes root state.

One-tap guided wizards replace documentation walls:

- **Check root** — re-run detection and explain what the result unlocks.
- **Multi-profile isolation** — links to Island, Shelter and OEM settings plus a Profile A / B / C readiness checklist. Isolation remains user-controlled and external.
- **Register workspaces** — save label + package + Android user ID into the Layer 2 registry.
- **Test switch** — a human-run two-workspace switch test using the fail-closed Session Kernel switch path.
- **Mutate lock status** — shows the current Layer 2 owner and provides explicit Release / Pause debugging controls.

Non-root users can open the entire section. Root-only isolation is visibly locked, with a note that OEM Dual Apps/app cloning or Island may still work without root when supported by the device.

## Explicit limits

- **NO phone / Pixel / USB / adb acceptance testing was performed for this release.** Physical Pixel 8 and UI acceptance remain `UNVERIFIED`.
- Root detection only: no Magisk install, no boot patching and no root-shell automation.
- Layer 1 profile/app isolation is still manual/external. Cyclone does not create Shelter/Island/OEM profiles itself.
- No virtual-display expansion or true simultaneous dual UI.
- No Instagram-specific skills.
- No GATE bypass.
- Overlay glass was not changed in this slice.

GitHub Mobile CI is the build/test authority for this candidate. The release should use the exact green `Cyclone-Android-4.0.3` CI artifact and its provenance sidecars.
