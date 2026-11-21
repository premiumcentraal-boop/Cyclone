# Cyclone Alpha 125 — room for up to 100 profiles

Built on the published Alpha 124 source. Android version code: 279.

Cyclone profiles were effectively capped at three on a Pixel. This release lifts that, so a rooted phone can hold many profiles and manage many things at once. The default is 20; the ceiling is 100.

- **Reads Android 16 correctly.** `pm get-max-users` on Android 16 says "Maximum supported switchable users"; Cyclone now reads that as well as the older wording, so the room screen and the pre-create check no longer show "? places" or misjudge the limit.
- **Raises the limit up to 100 (was 16).** "Allow more profiles" now offers 10, 20, 50 or 100 (default 20), never below what is already in use. Storage, not Android, is the real ceiling, so the confirm sheet shows free space and warns when it is low.
- **Clears the per-type cap too.** Some phones (this Pixel included) separately cap "extra users of this kind", which the user limit does not change. When Cyclone detects that cap, the "Cyclone profiles" module also turns on Android's own creation override. This needs one restart to take effect and makes the phone debuggable while the module is installed; the confirm sheet says so, and **Restore default** removes it (a restart turns the debuggable flag back off). On phones without that cap, nothing extra is set.
- **"Allow more profiles" is now offered for a per-type-cap error**, not only a total-limit error, since the override clears both.
- **The debug file tells the whole story:** it records the per-type-cap unlock and whether a restart is still pending, alongside the user list, the limits and each step.

Safety and scope are unchanged. Cyclone's runtime `resetprop` still only ever sets `fw.max_users`; the two creation-override properties live only inside the module's generated `system.prop`, applied by the root manager at boot, and are fully removed by Restore default. Cyclone writes one property and one module folder (`/data/adb/modules/cyclone_profiles`) and nothing else. Raising the limit, cleaning up users and restoring stay owner-only buttons in Cyclone's own UI — no Mind, Instant, gateway or MCP path reaches them. No app data or existing profile is deleted by this change.

Glass remains Alpha 65; gateway/MCP remain Alpha 123. Publication uses the established CI build/signing lane and the update-compatible Android signer.

Validation: Android unit tests (`ProfileHardening57Test`, including the Android-16 read, the 4..100 range, the per-type-cap unlock confined to `system.prop`, and that the override is offered for a per-type cap) and the profile-room, security and product guards. **UNVERIFIED on a physical phone:** the per-type-cap unlock, the one-restart step and the 100-profile ceiling have not been exercised on a real device; CI results do not imply physical acceptance. The design is built to prove itself on the owner's phone through the debug file, one step at a time.
