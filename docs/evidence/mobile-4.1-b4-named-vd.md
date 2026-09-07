# Mobile 4.1 B4 — named-VD Fast Path evidence

**Identity:** mobile `4.1.0-alpha.4` / versionCode `79`  
**Physical Pixel 8:** **UNVERIFIED**

This session did not assemble, gradle-test, adb-smoke, or time Chrome on a Pixel. No local Gradle, no adb, no Pixel run. CI green is not device evidence.

Chrome search ≤90s on a mid-model Pixel is the acceptance criterion to fill later. This session did not measure it. Do not treat the harness schema below as a captured device timing artifact.

## Source harness artifact schema

`NamedWorkspaceFastPath` records this schema (values are the acceptance fixture, not a Pixel measurement):

| Field | Value |
| --- | --- |
| scenario | `chrome-search-named-vd` |
| sessionId | `named-vd` (`≠ default-foreground`) |
| displayId | `7` (`>0`) |
| planeKind | `session_kernel_vd` |
| budgetMs | `90000` |
| physicalPixel8 | `UNVERIFIED` |

Fill `elapsedMs` / pass-fail on a later mid-model Pixel run. Until then every Pixel checklist row in `docs/MOBILE_4.1_STAGE4_FASTPATH_BG.md` stays **UNVERIFIED**.
