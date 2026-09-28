# Cyclone V5 Alpha 62: the working status shows the app, not Cyclone

Developer alpha for owner testing. It builds on Alpha 61 (the Driver mode crash fix) and includes it.
- **Mobile:** `5.0.0-alpha.62.dev1` (version code 207).
- **Cyclone for Windows:** `Cyclone-Setup-5.0.0-alpha.62.dev1.exe` (runtime `5.0.0-alpha.60.dev1`, unchanged).
- **Glass:** `1.0.0-alpha.36` (unchanged).

## What changed

While Cyclone works, its status now shows **the logo of the app it is working in**, the way a call or a timer shows
its app. Before, several places showed Cyclone's own logo.

| Where | Before | Now |
|---|---|---|
| **Overlay island** (the folded working bar), on-screen tasks | Cyclone's logo, always | The app on screen that the task works in |
| **Overlay island**, background tasks | Cyclone's logo until the task reported an app | The app the task works in, as soon as it is known |
| **Overlay working card**, on-screen tasks | Cyclone's logo, always | The app on screen |
| **In-app island** | As the overlay | As the overlay |
| **In-app task cards** (the task list, the Ask panel, View progress) | Cyclone's own launcher icon whenever the task was on a Cyclone screen | The app the task last worked in |

**Rules:**
- Cyclone itself, the system bars and the home screen never count as "the app". Cyclone keeps the last real app it
  saw.
- Cyclone's mark appears only before any app is known, for example in the first second of a new task.
- Apps are remembered in memory only, as before.

## Also checked: the Alpha 61 crash pattern

I checked the whole app for the Driver mode crash pattern (a Compose view in its own window, inside a host view
that lacks the lifecycle). **The Drive button was the only case.**
- Cyclone's other overlay windows (the Ask bar, the idle bubble, the screen-share pill and the tools sheet) already
  set it correctly.
- Every other screen is an Android activity, which sets it automatically.
- The remaining overlays (the AI trace and the debug sandbox) use plain Android views, with no Compose.

## Validation and limits

- **Passes:** a JVM test of the app choice (`WorkingAppTest`), all CI guards, and the Mobile CI build and unit tests.
- **Home screen:** Cyclone now asks Android which app is the home screen, so the launcher is never shown. This adds
  one manifest `<queries>` entry, not a permission.
- **Physical status: UNVERIFIED.**
