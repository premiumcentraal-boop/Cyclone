# Cyclone V5 alpha.116 dev1: Glass Cloak profile identities

Developer alpha based on Cyclone `5.0.0-alpha.115.dev1`.

- **Mobile:** `5.0.0-alpha.116.dev1` (version code 269).
- **Cyclone Glass:** `1.0.0-alpha.63`.
- **PC device gateway and MCP components:** `5.0.0-alpha.116.dev1`.
- **Publication:** authorized by the owner on 2026-10-09 through the established paired signing workflow.

## What's new

Cyclone Glass Home now includes a Cloak profile overview. Each ready Cyclone profile is labeled **Rooted** when Cyclone
has a valid Cloak identity binding, or **Native** when it has no binding. The device-info disclosure shows only the
versioned display fields: name, manufacturer, model, Android release and SDK. It also shows how many app bindings agree.

Cyclone reads Cloak's namespaced profile config on the phone and verifies each config's profile ID, Android user ID and
package before joining it. If app configs disagree, Glass shows the most common identity only when it has a strict
majority. Without a majority, the profile remains marked as bound but Glass does not guess which device identity to
show.

The PC receives no raw Cloak profile ID, hardware identifier or generic connector config. These identity details stay
out of Glass storage and the downloadable Home report. **Rooted** means a Cloak identity is configured; it does not
report live root access, module status or route health.

## Compatibility

The trusted phone gateway adds the read-only `profiles.cloak` operation and Cyclone's PC gateway exposes it at
`/v1/devices/{device_id}/profiles/cloak-identities`. The response includes one atomic profile roster and the narrow
identity projection, so Glass can join identities by profile ID and Android user ID without a second phone query.

The existing `profiles.list` operation still accepts its earlier response shape. A phone or PC gateway that does not
support the new identity operation shows an update message in the Cloak overview while the rest of Glass continues to
load.

## Verification

- Glass: `npm test` passed (332 tests); `npm run build` passed.
- PC gateway: the full regression run passed (1,084 passed, 19 skipped); after the final response-envelope refinement,
  the focused profile-contract suite passed (4 tests).
- Mobile: `:app:testDebugUnitTest` and `:app:assembleRelease` passed.
- APK inspection confirmed `com.cyclone.mobile`, version code 269 and version name `5.0.0-alpha.116.dev1`.
- Release component version guard passed.

## Physical acceptance

UNVERIFIED. No Android device is attached to ADB in this environment. The unsigned local APK is not an installable
update for an existing installation. The release workflow rebuilds and signs the APK using the configured signing key.
