# Cyclone 3.9.9 — release audit and implementation

Baseline: published v3.9.8, source 18d2b0ea58de8e4a79491c02237a81d6f628ec7f, versionCode 62.
Branch: agent/399-composer-and-model-compat. Preserve the published APK.

## Audit of 3.9.8 against the requested behavior

- Partial: floating composer has microphone/send, but no plus button and settings occupy the header. Fixed-height sheet clips expanded settings/content. This does not meet the supplied compact Gemini reference.
- Partial: downward swipe triggers minimization, but the panel does not follow the finger.
- Implemented in source: session-scoped background pixels, bounded preview, pause/handoff, notifications and a non-touchable active border. Physical acceptance was explicitly left unverified by the previous agent; a successful build does not establish it.
- Implemented conservatively: chronological history, task-scoped browser typing and at-most-once shutter ledger. Saved-photo proof still depends on authorized MediaStore access; an uncertain capture hands back rather than retries.
- Partial: provider diagnostics preserve errors, but model qualification, planning and optional learning have different request shapes. Qualification forces temperature and 300 max tokens, planning omits reasoning despite a universal slider, and learning sends model-specific effort strings with JSON mode. Contributor access was never account-verified.
- Missing from floating composer: attachment selection. Main chat contextual actions do not satisfy this requirement.
- Documentation drift: README still advertises 3.9.5.

## 3.9.9 work

1. One compact composer: plus/settings left, unboxed text center, microphone/send right; continuous drag with settle/dismiss; scrollable settings above it.
2. Use a shared portable request shape and live endpoint discovery, retain exact model/privacy identity, expose actionable access errors and same-account qualification. Do not assume generic reasoning tiers or silently weaken Contributor privacy settings.
3. Add regression tests, update version to 3.9.9/63, run Mobile CI and publish from its exact verified artifact.

## Evidence limits

Both new screenshots were inspected. OpenRouter's public Contributor endpoint listing confirms the exact slug and a Meta endpoint with text/image support, reasoning and response_format capabilities. Public availability is not proof of this user's account entitlement. No API key or physical Android device is available here; do not claim an end-to-end Muse success without a live authorized probe.

Implementation and validation in progress. Intermediate checkpoints are not release acceptance.

## Implementation checkpoint
Compact composer and continuous drag implemented; attachments use an OS picker/camera thumbnail and remain separate from trusted task text. Failed runs leave active styling. Shared portable requests now avoid reasoning/sampling/schema assumptions, use available endpoints advertising max_tokens, and retain Contributor routing/privacy identity. Qualification receives sufficient output budget and is invalidated on account change. Public endpoint metadata was fetched for all eight registry models; none proves account authorization. CI validation pending.
