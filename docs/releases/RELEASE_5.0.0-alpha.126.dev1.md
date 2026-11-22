# Cyclone Alpha 126 — glass Profiles and a living Cyclone overlay

Built on the published Alpha 125 source. Android version code: 280.

Two owner-reviewed redesigns: every Profiles screen, and the whole Cyclone overlay, iterated render by render until the owner approved.

## Profiles
- **One calm-glass style for every profile screen**, like Home: a 3-column grid of profile faces with a New tile, a Room meter, and plain rows for Shared apps, In every profile, Recently deleted and the debug report.
- **Profile detail** has one primary action (Switch to … / Ask Cyclone here) and the apps as an icon grid.
- **Make room** is one sheet (N of M, 10/20/50/100, storage, the one restart when needed, undo and restore) instead of a dialog and a card.
- **Problem card**: the headline and the fix first; what Cyclone checked folds under Details.
- Buttons and status words stay on one line: no more letters stacking vertically in squeezed pills.

## Cyclone overlay
- **One glass for every panel** (Ask bar, working card, island, confirmation gate, questions, typing-while-working options, status pills): darkened smoke instead of teal, the in-app Ask bar's continuous corners, one sharp shine, no inner panels.
- **Living halftone**: the edge dots sit in rows that follow each panel's outline, so the corners fill evenly. A highlight follows the phone's tilt; gravity is smoothed, so a shake averages out while a real tilt comes through. A slow wave breathes round the panel, and a tilt sends a short ripple. Everything is still with animations off. The light is softer and has no hard sheen line at any angle.
- **Working card**: the apps it worked in stack at the top right with depth (earlier ones faded) and fan out into a slidable row. A change of plan stays inside the card as one compact "Plan diverted" mark; a tap shows why and the earlier plan.
- **Done / couldn't finish**: the result is one line with a chevron; a tap unfolds the steps it took, the stopping step marked.
- **Approvals** show the message in its own box under the icon of the app it goes through. **Questions** have a lit answer field.
- **Confirmation gate**: the app (or Cyclone's mark outside any app) and what it wants to do, without a disclaimer. An ⓘ shows Cyclone's own reason: the Mind's current plan step and its "why", or the classic agent's displaySummary. The reason is user-facing model output, never hidden reasoning. It is kept in memory only (never saved, logged or sent), dropped after ten minutes and when the run ends. Approval boundaries are unchanged: the gate still blocks the exact action until the owner taps "Do this".

## Blur behind the overlay, with a smoke fallback
On Android 12+ phones that can blur, the app behind each overlay panel is blurred and the smoke gets lighter. Only Android's own signals decide, so the check costs nothing:
- the cross-window blur switch: off on phones that cannot blur, in battery saver, and when window blurs are off;
- the heat status: from "moderate" up.

Otherwise the overlay shows the darkened smoke, fading between the two over a third of a second. Each panel gets one small, untouchable blur window under the overlay window. A blur window that fails to open keeps smoke for the session.

Glass remains Alpha 65; gateway/MCP remain Alpha 123. Publication uses the established CI build/signing lane and the update-compatible Android signer.

Validation: Android unit tests, with new and updated tests: `GlassOpticsTest`, `GlassBlurTest`, `GateShownTest`, `PlanDiversionTest`, `ProfileGlassKitTest`, `TaskAppTrailTest` and the UI contract pins. The CI guards (`pytest scripts/ci/tests`, `mobile_product_guard.py`) also pass. Every state was rendered and reviewed with the owner, including tilt videos.

**UNVERIFIED on a physical phone:** the blur windows (placement, corners, battery-saver fallback, touches passing to the panels), the tilt and smoothing feel, and the redesigned Profiles screens. CI results do not imply physical acceptance.
