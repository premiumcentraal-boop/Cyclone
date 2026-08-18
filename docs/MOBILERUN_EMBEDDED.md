# Embedded Mobilerun Portal — licensing and source provenance

Cyclone Mobile V2.1 embeds the **actual upstream Mobilerun Portal Android source** as a pinned git submodule and compiles it into the Cyclone APK through the `:mobilerun-embedded` Android library wrapper.

Upstream source:

- project: `droidrun/mobilerun-portal`
- pinned commit: `d3dae858ecc5ec3bfd3701ff27d58465c9f661b4`
- upstream license: GNU Affero General Public License v3 or later (AGPL-3.0-or-later)
- upstream copyright notice remains in the upstream `LICENSE` file in the submodule

The wrapper does not relicense Mobilerun code. Cyclone-specific glue and UI live outside the submodule. The embedded module uses upstream Java/Kotlin sources, Android resources, assets and upstream runtime components directly.

## Internal-use decision

This repository currently builds the merged APK for internal/private use. Internal use does not require removing or weakening the upstream license notices. If the combined APK or a modified Mobilerun-backed network service is later conveyed or offered to third parties, review and satisfy the AGPL source/notice obligations for the covered work before distribution or deployment.

## Build topology

```text
Cyclone app
  ├─ Cyclone native phone.* toolbox
  ├─ Automation Studio
  ├─ Hermes/Core bridge
  └─ :mobilerun-embedded
       └─ third_party/mobilerun-portal (pinned upstream source)
```

The Mobilerun launcher is not a second launcher icon. Its activities and services are merged into the Cyclone APK and surfaced from Cyclone Settings under **Enhanced Mobilerun engine**.
