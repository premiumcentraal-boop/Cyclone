# Cyclone Alpha 128 — the Ask bar moves with you

Built on the published Alpha 127 source. Android version code: 282.

The places the owner still felt lag or a jump on Alpha 127.

- **Keyboard.** The overlay window used to jump straight to its new height above the keyboard while the keyboard slid in. It now glides there over the keyboard's own duration and curve (280 ms, fast out and slow in), one window update per frame, so the bar and the keyboard move as one. A change of direction picks up from where it is.
- **Opening from the collapsed button.** The launcher-to-composer and bar-to-island swaps are now anchored at the bottom, where the bar sits. Before, a size change grew from the top-left corner.
- **The working card closing.** When a task ends or is cleared, the card keeps its last content while it folds away. The fold now runs on the card's real height instead of the card vanishing in one frame.
- **Battery.** Calm glass (no ripple) now asks for a frame about 20 times a second instead of on every screen refresh while the overlay shows.

Known and accepted: while the bar glides with the keyboard, the blur steps aside (smoke) and returns once the window is still.

Also in this release: `docs/HANDOFF_2026-10-13_testing-debugging-improvements.md`, a handoff for the next testing agent with ranked improvements.

Glass remains Alpha 65; gateway/MCP remain Alpha 123. Publication uses the established CI build/signing lane and the update-compatible Android signer.

Validation: Android unit tests and the CI guards. **UNVERIFIED on a physical phone:** the keyboard glide, the swaps, the fold and the clock change; CI results do not imply physical acceptance.
