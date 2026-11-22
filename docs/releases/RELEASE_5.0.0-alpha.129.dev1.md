# Cyclone Alpha 129 — complete profile app inventory

Built on published Alpha 128. Android version code: 283.

Companion connectors can now discover installed Essentials, bundled and system apps alongside ordinary apps, with an explicit Android user and Cyclone profile identity for every record. A package installed in multiple users remains a separate record in each user; absent packages are never invented.

The omission came from reading selected workspace packages rather than actual installed packages. Inventory reads also previously inherited an 8 KB output limit. The new read path captures a bounded complete listing and fails explicitly if the listing cannot be completed.

Connector contract minor 4 adds the scoped, paginated `profiles.apps.v1` method and system/bundled markers. Existing consumers remain compatible. No UI or companion behavior changes. See `docs/PROFILE_APP_INVENTORY_AUDIT.md` for the audit and contract details.

Validation: 2,636 Android unit tests, 61 guards/schema tests and connector client compilation passed before the release metadata update. Release CI verifies the final commit and signs the APK with the established update-compatible signer. Physical-device verification of the new inventory is UNVERIFIED.

Glass remains Alpha 65; gateway/MCP remain Alpha 123. The Windows package is rebuilt by the standard release workflow.
