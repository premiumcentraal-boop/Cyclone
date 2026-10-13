# Connector app inventory on Alpha 128

## Cause

The connector `profiles` method returned `CycloneProfileRecord.packages`. That is the workspace selection journal,
not Android's installed inventory. `ProfileSetupRuntime.create` deliberately subtracts `automaticPackages` from
`selectedPackages`, so Essentials (Cyclone, installed support/root tools, Cloak and system basics) could be installed
but absent from connector listings. `ProfileConfigKey.parse` then rejected them using that same set.

The shared root executor also capped ordinary command output at 8,192 characters. A full package listing would hit
that cap on many phones. The new inventory operation has its own bounded 1 MiB character budget, detects incomplete
reads/overflow and fails explicitly. Inventory output is excluded from the setup-step journal and last-step diagnostics.

The independent PC app-management surface also uses `ProfileSetupPlan.listProfileApps`, whose `-3` option excludes
system packages, and removes Cyclone itself. That management surface is unchanged in this cut. Installer source is
not involved. Android caller package visibility can also hide non-launcher apps from PackageManager; the complete
connector inventory therefore uses a user-scoped, read-only Android package listing for every user, including Main.

## Fix and compatibility

`ProfilePackageInventory` is the canonical connector-side metadata inventory. The installed package set comes from
`pm list packages --user <id>`, without `-3`, launcher/role filters, or `-u`. A second `-s` read classifies system apps.
Essentials membership only classifies `bundled`; it never decides presence. Labels use Android resources where
visible, otherwise the package name. No files, installer paths, APK contents or app data are returned.

`hello.minor` is now **4**, with major `cyclone.connector/1` unchanged. Existing `profiles` fields remain compatible;
with `profiles.apps.read`, `packages` and `appCount` now reflect the actual installed set. Without that scope no
package query runs and no names are disclosed. Config/status binding keeps the same `(profileId, androidUserId,
packageName)` identity and existing readiness, approval and storage boundaries, but verifies live membership.

New `profiles.apps.v1` requires both `profiles.read` and `profiles.apps.read`. Arguments: `profileId` (`owner` or a
Cyclone profile id), optional integer `offset` (default 0), `limit` (1–100, default 100). Result:

```json
{"version":1,"profileId":"Cyclone_aaaaaaaaaaaaaaaa","apps":[
  {"packageName":"com.android.settings","label":"Settings","androidUserId":11,
   "profileId":"Cyclone_aaaaaaaaaaaaaaaa","installed":true,"system":true,"bundled":true}
],"nextOffset":null}
```

Follow `nextOffset` until null; ordering is by package name. Re-scan after package changes (pages are live, not a
transactional snapshot). Owner records use the actual Main user id, never a guessed zero. Each user's package is
a separate record. Unassigned profiles have an empty inventory. An invalid Android profile identity or failed
privileged read fails the call with a sanitized error, never a fabricated empty success or Main's packages.
The existing `profiles` bulk response is retained for compatibility; use the paginated method for large inventories.

Only metadata is added. No UI, companion implementation, startup callback behavior, package installation/removal,
Cyclone registry content, or profile creation policy changes. This branch is based on the published Alpha 128 tag;
it does not publish a new product release or claim physical-device acceptance.

## Verification

Unit coverage includes ordinary apps, non-system bundled apps, system apps, disappeared packages, empty inventories,
duplicate packages, Android's platform package, two non-zero users, legacy package/config compatibility, pagination,
scope denial before enumeration, and sanitized failures. SDK JSON Schema and client helper accompany minor 4.
