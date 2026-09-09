# One 1.1.3 release checkpoints

Base: published Mobile 4.2.4 / 85, tag v4.2.4, SHA 2957bc5e6df60c80eb3510b78b1ebbab5d3b1b32. PC, gateway, both MCP packages, packaging and PC scripts compare byte-for-byte with proven Live Phone source bda79a24b0db44d58bfeb6f38c908a1c82e3058a (Windows CI 34299725847 green).

Target: One 1.1.3 paired with Mobile 4.2.4 / 85. Do not change the APK. Native Codex remains CycloneAgentMCP; Live Phone is the separate typed foreground adapter; Remote MCP remains available separately. No new mutation engine.

Checkpoint 1: exact existing baseline verified. Next: release identity/packaging, focused connection/vision/action checks, mode UI, Windows installer acceptance, physical acceptance, immutable publication. Physical results are UNVERIFIED until actually performed.

Checkpoint 2: 183b271 — One identity 1.1.3; Mobile stays 4.2.4/85; all three sidecars packaged.
Checkpoint 3: f50c3a1 — decoded image validation and stale screenshot removal.
Checkpoint 4: f644745 — failed actions remain failures; control generation/restart checks.
Checkpoint 5: 0f5ed18 — mode-separated UI and truthful readiness.
Final review: provider-neutral PC connector status, startup-off explanation, screenshot/gesture limits, typed secret response redaction. Exact-source Windows candidate now triggers on this release branch. Narrow publisher waits for that exact green candidate, verifies checksums/provenance and installed-binary acceptance, then creates immutable one-1.1.3. User explicitly deferred physical testing until after release; no phone tests or additional APK changes are authorized by this continuation.
