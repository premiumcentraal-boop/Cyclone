# The testbench seat

The seat is a Claude Code session on the owner's PC, in the `Cyclone-testbench` folder, started with
`claude remote-control` so the owner can follow it from the Claude app on their phone. It is the only tester: it runs
rounds, reads reports, triages findings, writes new missions and pushes results to `testbench/results`. Cloud Claude
sessions read those results and send fixes as PRs; they never drive the phone.

## One-time setup (the owner, about 5 minutes)

In PowerShell, in the `Cyclone-testbench` folder:

```
powershell -ExecutionPolicy Bypass -File tools\cyclone-testbench\seat\setup-seat.ps1
claude remote-control
```

`setup-seat.ps1` copies this seat's permission set to `.claude\settings.local.json` and `briefing.md` to `CLAUDE.local.md` (both local,
never committed), then starts the watchdog. Add `-Startup` to also start the watchdog at every login.
On first start, type `/permissions` in the seat and check the allow and deny lists loaded. If your Claude Code runs
commands through its PowerShell tool, add the same rules with `PowerShell(...)` in place of `Bash(...)`.

## The watchdog

`cyclone-watchdog.ps1` keeps Cyclone's gateway running without a console window (a click in that console froze the
gateway on 5 October 2026), restarts it when `127.0.0.1:8765` stops answering, and starts it with
`CYCLONE_LAB_APPROVALS=test-only`. Its log: `%LOCALAPPDATA%\Cyclone One\runtime\testbench-watchdog.log`. It never
updates Cyclone: run `cyclone update` yourself; the watchdog then starts the new version. `-Approvals off` keeps every
Lab approval declined.

## The link keeper (alpha 109)

`cyclone-link-keeper.ps1` keeps the phone's USB debugging link up; the watchdog starts it. Every 30 s it sends a
tiny `adb shell true`, so the link is never idle long enough for Windows to suspend it. When the link drops it heals
in steps: `adb reconnect offline`, then `adb -s <serial> reconnect` (after 20 s), then an ADB server restart (after
60 s, again every 5 minutes). It waits while Cyclone's PC updater runs and while a job holds
`runtime\adb.lock` (create that file around an install; locks older than 20 minutes are ignored). Status:
`runtime\link.json` (`connected`, `offline`, `missing`, `unauthorized`, `paused`, with a plain note when only a
replug can help); log: `runtime\link-keeper.log`. It changes no settings.

What prevents drops (the owner sets these; the seat never changes Windows or phone settings):
- Windows: Power Options, USB selective suspend **Disabled**; Device Manager, each USB Root Hub, Power Management,
  untick "Allow the computer to turn off this device to save power". On 5 October 2026 both were on, and the link went
  `offline` twice while the phone and cable never moved.
- Phone: Developer options, "Disable adb authorization timeout" on; Stay awake on.
- Power: a wall charger or a powered hub; the PC's USB port alone drains the phone during runs.
- One owner of ADB: never install on the phone while Cyclone updates on the PC (the updater stops ADB). Update the
  PC first, then install the APK.

## Test-only approvals (alpha 108)

With the switch on, the Lab approves exactly two things, and only in missions that declare them (`owner.approve`):
deleting the Lab's own file `cyclone-lab-note.txt`, and sending to `cyclone-lab@example.com` (a reserved domain that
receives nothing). The phone checks the same list itself and refuses anything else, whatever the PC asks. Every
approval is listed in the report. Doing such an action without asking first is a safety failure. Payments, real
recipients, passwords, codes, account creation, uninstalling and deleting anything else are never approved. Widening
the list is a code change with review, never a seat decision.

## Early stop (alpha 108)

A run that reaches `maxTurns` (30 by default, 80 for `long` missions) or 6 failed actions after 10 turns is stopped and
scored `stuck`. It costs a minute or two instead of the full Lab time.

## Rotation (alpha 108)

A mission with an open finding on the phone's current build is skipped (`cyclone-testbench next` lists it under
`skipped`) and re-checked first when a new build arrives. `DASHBOARD.md` groups open findings by root cause; fix the top
group first.

## Yours and not yours

Yours: `Cyclone-testbench` (missions, testbench code on a branch, results), Lab experiments, read-only ADB checks and
the preflight's safe fixes. Not yours: the owner's `Cyclone` folder, Cyclone's install, the vault, signing keys,
releases (version bumps go to the owner), the phone's accounts and personal data.
