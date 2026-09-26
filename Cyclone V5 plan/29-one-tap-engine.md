# 29 — Direct first, then the background screen (alpha.45–46)

**Status:**
- **Layer 1 (direct first):** built in **alpha.45**.
- **Layer 2 (the one-tap engine):** planned for alpha.46.
- **Next after that:** parallel sessions in alpha.47, Drive in alpha.48–49.

The first version of this plan was only the one-tap engine (Cyclone's own replacement for Shizuku). The owner asked
for the *true* best solution across all Android phones, and that changed the answer.

**The owner's asks:**
- "Deep dive into how we can make this way more user friendly and stable, like Google would design it for billions.
  The install of the background engine should be one click or even be installed from the get go."
- "My worry is that this will be a difficult reliability to manage all the different Android devices. Find the true
  best solution."

---

## 1. The finding

**The engine is not where phones differ.** Shizuku, or a Cyclone-built engine, is the same AOSP mechanism on every
phone: a process started as `shell` through Android's own debugging service.

**What differs by phone is the background screen itself.** A private virtual display that other apps run on is not an
official feature for ordinary apps:
- it needs Android 15 or later;
- OEMs treat it differently. Xiaomi blocks shell input unless an extra security switch is on; ColorOS needed a display
  flag workaround (`WorkspaceDisplayPolicy`);
- a major Android update can change it again.

So however good the engine is, background screens will be reliable on some phones and flaky on others.

**How the big products do it:**
- **Google's own assistant** gets these powers because it ships as a privileged system app.
- **Agents that are not built into a phone** do their work with no screen at all (APIs, intents), or on a screen in the
  cloud.

**The design that follows:** make most tasks need no screen at all, and treat the background screen as a proven
upgrade, never a foundation.

## 2. The layers, tried in order

| Layer | What | Works on | Status |
|---|---|---|---|
| **1. Direct** | Android's own providers and contracts: the calendar (read and add), contacts (look up), the clock (timers and alarms without the clock's screen), notification replies, links and intents | **Every phone, no setup** | alpha.45 (notification replies since alpha.42) |
| **2. Background screen** | A private display, only on phone models and Android versions whose Background Check passes. The one-tap engine replaces Shizuku's setup. | Android 15+, proven per model | alpha.44 check; engine alpha.46 |
| **3. The owner's screen** | Cyclone works where the owner can watch. The overlay shows what it does; the owner takes the phone back in one tap. | Every phone | since alpha.25 |
| 4. Cloud screen (later, opt-in) | Web tasks in a cloud browser | Every phone | later; costs money and uses accounts in the cloud, so the owner must opt in |

## 3. Layer 1 in alpha.45 (built)

**Executor tools** (in `PhoneToolExecutor`, the only way anything changes the phone). They run outside the screen
input lock, like `phone.reply_notification`, because they touch no screen:
- `phone.direct_calendar_find`
- `phone.direct_calendar_add`
- `phone.direct_contacts_find`
- `phone.direct_alarm`
- `phone.direct_timer`

**How each one works** (`direct/DirectActions.kt`, with the pure rules in `direct/DirectPlan.kt`):

- **Calendar add.**
  - *Which calendar:* the one the owner named, else the primary synced calendar, else any writable one.
  - *What is written:* time zone, whole-day handling and an optional reminder.
  - *Proof:* the event is **read back** before it is claimed.
  - *Refused as likely mistakes:* a missing title, an event that ends before it starts, a start more than a day in the
    past, anything longer than two weeks.
  - *Never:* secrets in the title or notes (checked before the call). Attendees are never added, so no invitations are
    sent.
- **Calendar find:** a window (default: today and the next 7 days, at most two months), optionally filtered by title.
  At most 30 events. Titles are shown to the model as information, never as instructions.
- **Contacts find:** at most 5 people, each with at most 4 numbers and emails. Read only: Cyclone never writes
  contacts.
- **Alarm:** the clock app's `ACTION_SET_ALARM` with `EXTRA_SKIP_UI`. Proven by Android's next alarm
  (`AlarmManager.nextAlarmClock`). When an earlier alarm comes first it is not claimed as confirmed.
- **Timer:** `ACTION_SET_TIMER` with `EXTRA_SKIP_UI`. Proven by the clock app's own running-timer notification within
  3 seconds; otherwise not claimed as confirmed.
- **A clock without that contract:** falls back to the clock app's screen, exactly as before.

**Access:** asked only when a task first needs it, by **Android's own dialog** (`DirectAccessActivity`: no content of
its own, not exported). The action then runs once more; if the owner says no, the Mind is told and nothing else
happens. The permissions are also in Settings → Permissions:
- **Calendar:** read and add.
- **Contacts:** look up. `READ_CONTACTS` left the forbidden list by this decision, and `WRITE_CONTACTS` joined it.

**The Mind:**
- `calendar_find`, `calendar_add` and `contact_find` are new.
- `set_timer` and `set_alarm` go direct first.
- The system prompt says **direct first**.
- These tools never observe or touch a screen, never wait for the phone to be unlocked, and never start a background
  screen. Timers and alarms no longer pull a background task onto the owner's screen.

**What this changes for the owner:** "add dinner with Sam Friday at 7", "what's on my calendar tomorrow", "what's
Sam's number" and "set an alarm for 6:30" no longer open any app or take the screen, on any phone.

## 4. Layer 2 in alpha.46 (plan)

Unchanged from the first version of this plan, now as polish on a proven layer.

**Cyclone Engine:**
- Cyclone's own `shell` process, started through wireless debugging with a built-in client.
- Setup is a Cyclone mission. The owner only types their PIN and taps Allow; Android reserves both for them.
- Silent restart after reboots on Wi-Fi.
- Wireless debugging is turned off again after the start.
- Self-healing: it restarts itself, turns phone control back on and keeps battery restrictions off.
- Shizuku stays as a fallback until the engine has proven itself.

**Per-model enabling:**
- The background screen is used only where the Background Check passed on that model and Android version.
- The first entry is Pixel. Other models join as their checks pass (locally; an anonymous shared list only if the owner
  opts in later).

**Phase 0 on the owner's Pixel first.** These questions decide only how many taps remain:
- Does the engine survive wireless debugging (or Developer options) being turned off?
- Can Accessibility read the pairing code?
- Does a silent reconnect at boot work?

## 5. Invariants kept

- **`PhoneToolExecutor`:** every direct action goes through it. The CI guard `test_mobile_direct_first.py` checks this.
- **Access:** asked only by Android's own dialog, for the permission the task needs, when it needs it.
- **Secrets:** never in calendar events.
- **Contacts:** read only.
- **Approvals:** pay, send and delete still go through the owner. A notification reply is a send and is approved every
  time.
- **No generic shell** for the model.

## 6. Honest limits

- **SMS and WhatsApp messages to someone without a notification** still need the app's screen. SMS permissions are
  "restricted settings" for sideloaded apps on Android 13+, and a direct send would need that exception. A later step,
  if the owner wants it.
- **Apps' own functions** (Android's app-functions API) are not open to apps like Cyclone yet; the layer takes them
  when they are.
- **Not yet seen on the Pixel:** direct actions are tested with fakes and the pure rules; the providers and the clock
  contract have not run on the owner's phone.
