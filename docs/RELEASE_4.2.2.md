# Cyclone 4.2.2

Cyclone Mobile 4.2.2 is a focused repair and UX release built on the preserved 4.2.1 source line.

## Profiles / Profile B provisioning

- Adds typed provisioning failures and bounded platform-error reporting.
- Verifies root availability at provisioning time instead of treating a historical grant as proof of current readiness.
- Adds Android user/profile preflight and exact managed-profile ownership checks.
- Prevents duplicate Profile B creation and supports durable retry/recovery after interrupted provisioning.
- Makes selected-app installation resumable and verifies final profile readiness before reporting success.
- Keeps Shizuku capability truth separate from root/profile provisioning truth.
- Surfaces clearer Profile UI headline, reason and recovery actions when provisioning cannot continue.

## Ask Cyclone and task routing

- Removes the old Chat / Phone task selector and New request control.
- Adds deterministic local CHAT vs PHONE_TASK routing without a separate classifier/API request.
- Keeps general questions and attached-image questions in normal chat without requiring Accessibility.
- Routes phone operations into the existing Workspace flow and queues additional phone requests instead of replacing the active task.
- Keeps chat available while phone work is running and restores attachments after failed/stopped chat replies.
- Ensures Stop Reply cancels only the current chat-provider job.

## Current task and queue glass

- Removes the phantom current-task card and rebuilds the task glass around the newer compact hierarchy.
- Resolves real app icons and humanizes task names.
- Keeps View Progress bound to the exact active task.
- Removes Stop/Close from collapsed task cards.
- Gives each queued task its own Steer / Stop controls and preserves queued attachments.
- Adds conservative FIFO auto-promotion and overlay-safe destination steering.
- Improves light/dark treatment, keyboard compaction and large-font layout behavior.

## Navigation polish

- Uses one Cyclone-blue selected treatment for AI navigation without the old green Material selection pill.
- Cleans up themed content colors, outlines and flat surfaces across the updated AI/task UI.

## Validation status

GitHub CI is required to validate the exact release-source commit with Android unit tests, lint and release assembly before publication. The release workflow signs only the exact successful CI artifact and verifies update signer continuity against Cyclone 4.2.1 before publishing.

Physical Profile B creation with real root/Shizuku/OEM behavior, plus final light/dark/keyboard overlay acceptance, remain **UNVERIFIED on a physical phone** for this release cut. CI success is not physical-device evidence.

The Android APK is signed with Cyclone's historical update-compatible personal-development key so existing compatible Cyclone installations can update in place. This signer is update-compatible but is not a production-secure distribution key.
