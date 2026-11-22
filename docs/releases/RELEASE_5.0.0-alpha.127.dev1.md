# Cyclone Alpha 127 — one smooth overlay

Built on the published Alpha 126 source. Android version code: 281.

Fixes the owner found on Alpha 126.

- **One motion for the whole overlay.** Opening, folding, the Ask bar turning into the island and back, the Steer / Queue / Parallel choice, and a card's sections unfolding now all ride the same no-bounce spring (`OverlayMotion`). Every part starts, travels and lands together; the bar no longer slides in from below. When the Ask bar and the island swap, the old one fades out before the new one fades in, so their words never overlap. A reversed gesture picks up from where it is instead of jumping.
- **Blur that never lags.** A blur window can only follow a panel a frame or two late, so it no longer tries. While a panel appears, moves, resizes or goes (or the overlay window itself moves), its blur steps aside at once and the panel shows darkened smoke. 160 ms after the panel settles, the blur returns and the smoke fades lighter. No frozen blurred frame is left behind.
- **New text after a finished mission is a new request.** A finished mission's card could stay on screen and make the Ask bar offer Steer / Queue / Parallel for a task that had already ended; every choice then did nothing. The choice is now offered only while a mission runs. A choice whose task ended meanwhile sends the text as a new request.

Glass remains Alpha 65; gateway/MCP remain Alpha 123. Publication uses the established CI build/signing lane and the update-compatible Android signer.

Validation: Android unit tests, including `GlassBlurTest.aMovingPanelHasNoBlurUntilItSettles`. The CI guards also pass. The open/fold/swap motion was rendered frame by frame and reviewed. **UNVERIFIED on a physical phone:** the motion feel, and the blur stepping aside and returning; CI results do not imply physical acceptance.
