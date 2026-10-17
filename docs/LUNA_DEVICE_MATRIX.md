# Luna Decision Box device matrix (plan 58)

Everything here is **UNVERIFIED** until it is run on the owner's phone. Mark each row with the date, the build and the
result. Settings → Speed shows the numbers; Glass → Home → phone care carries them to the PC.

**Phone:** ______ · **Android:** ______ · **Cyclone:** 5.0.0-alpha.123.dev1 or newer · **OpenRouter key:** set in Cyclone.

## alpha.123 · Foundation

### 0. Once, on the PC

| # | Steps | Expected | Result |
|---|---|---|---|
| 0.1 | `OPENROUTER_API_KEY=… python scripts/dev/decisions_probe.py` | Prints whether the old shape was accepted, p50 times for JEV and Luna, and "Luna read the image: red". Saves four answers under `apps/mobile/app/src/test/resources/decisions/recorded/` | UNVERIFIED |
| 0.2 | Commit the recorded answers | They become the tests' real fixtures | UNVERIFIED |

### 1. Routing still works (Speed → Auto, Decided by → JEV, Triage → Watch only)

| # | Steps | Expected | Result |
|---|---|---|---|
| 1.1 | Say or type "open camera", "louder", "go back" | Instant, as before (the grammar, no model) | UNVERIFIED |
| 1.2 | "pull up the thing I take pictures with" | Decided by JEV in under 2.5 s; the run trace says "decided by decisions in … ms" (not "gave no answer") | UNVERIFIED |
| 1.3 | "open insta", "open music", "open the bank app" | Opens the app or goes on to Flash; never "I don't see … on this phone" | UNVERIFIED |
| 1.4 | "open snapchat" (not installed) | "I don't see Snapchat on this phone." | UNVERIFIED |
| 1.5 | "text mom I'm late" | The Mind, which asks before sending | UNVERIFIED |
| 1.6 | Airplane mode, then "pull up the thing I take pictures with" three times | No stall: each goes to Flash or the Mind; "paused" may appear in the numbers for 2 minutes | UNVERIFIED |

### 2. The watch (Compare beside it → on)

| # | Steps | Expected | Result |
|---|---|---|---|
| 2.1 | Ten Auto requests, then Settings → Speed | "Compare beside it" shows "10 compared · answered …% · agreed …% · triage matched …% · 0 below the rules" | UNVERIFIED |
| 2.2 | Turn Compare off, ten more requests | The count doesn't grow; with Triage on Watch only, JEV still answers Triage (counted under its own name) | UNVERIFIED |
| 2.3 | Glass → phone care details | `decisions.calls`, `decisions.watch` appear; no request text anywhere | UNVERIFIED |

### 3. The decisions Lab

| # | Steps | Expected | Result |
|---|---|---|---|
| 3.1 | Settings → Speed → **Test LUNA** | Within about a minute: "LUNA: …% right · 0 risky too low · p95 … ms · answered … of 286" | UNVERIFIED |
| 3.2 | **Test JEV** | The same line for JEV; both reach Glass | UNVERIFIED |
| 3.3 | Tap Test twice quickly | The second says a test is already running | UNVERIFIED |

### 4. Switching the decider

| # | Steps | Expected | Result |
|---|---|---|---|
| 4.1 | Decided by → GPT-6 Luna Decisions; repeat 1.1–1.5 | Same routes; "decided by decisions" times shown; Compare now runs JEV beside it | UNVERIFIED |
| 4.2 | Fast mode on, route "Decision model"; run a Flash task | The Pilot's decisions answer (not "handed back" at every step) | UNVERIFIED |
| 4.3 | Back to JEV | Everything as in section 1 | UNVERIFIED |

### Gate before Triage → On (plan 58 §11.4)

About a week of normal use with Compare on, then:
- the Lab shows Triage's rung accuracy ≥ JEV's and ≥ 90%;
- 0 risky requests too low;
- "0 below the rules" in the watch.

Record the numbers here before switching.

## alpha.136 · Decision engines (plan 63)

Settings → Speed → Decision engine. Run each row in **Consensus**, then **Sprint**.

| # | Steps | Expected | Result |
|---|---|---|---|
| 5.1 | "pull up the thing I take pictures with" | The camera opens. The run trace says the decision models agree (Consensus) or that one was sure first (Sprint) | UNVERIFIED |
| 5.2 | "turn it up a bit" | Volume up. In Sprint it should feel quicker than in Consensus | UNVERIFIED |
| 5.3 | "tap follow" on a profile | Never Instant on one model's word: Flash or the Mind, which asks before following | UNVERIFIED |
| 5.4 | "text mom I'm late" | The Mind, which asks before sending (the rules come before the engine) | UNVERIFIED |
| 5.5 | Airplane mode, then 5.1 | No stall: Flash or the Mind within about 2 s | UNVERIFIED |
| 5.6 | "take a selfie" on a camera with an icon-only shutter | The photo is taken, or Instant hands up; never a tap outside the camera | UNVERIFIED |
| 5.7 | 20 everyday requests, then Settings → Speed | The engine line shows decisions, "both answered", "agreed" and a median time for JEV and OpenAI Decisions | UNVERIFIED |
| 5.8 | Switch to Classic and repeat 5.1 | Works as in alpha.123 | UNVERIFIED |
| 5.9 | Say "get the picture thing up" three times (in Consensus) | The camera each time. The third time the trace says "you asked this before and it worked" and it opens with no wait | UNVERIFIED |
| 5.10 | Repeat 5.9, but cancel the second run | The third run asks the models again (Replay forgot it) | UNVERIFIED |
| 5.11 | 20 everyday requests, then the health report's `decisions.engine.lanes` | Each model shows `selfAgreement`. Four answers per decision (two per model) in the engine log | UNVERIFIED |

## alpha.137 · Live voice from the Ask bar, decided while you speak (plan 65 R1)

| # | Steps | Expected | Result |
|---|---|---|---|
| 6.1 | Open the Ask bar, type nothing | The send arrow is a voice wave; typing turns it back into Send | UNVERIFIED |
| 6.2 | Tap the wave, say "turn it up a bit" | The Ask bar steps aside, the voice panel rises with Cyclone's smoke glass, volume goes up, the panel closes and the Ask bar comes back as it was | UNVERIFIED |
| 6.3 | Tap the wave, say "open my email and read the newest one" | One-line confirmation; the task runs; Cyclone says when it is done; the voice session then closes by itself | UNVERIFIED |
| 6.4 | Live voice: say "pull up the picture", pause briefly, then "thing please" | Works as usual; the run history shows no head start used for the first half | UNVERIFIED |
| 6.5 | Live voice: say a short request and stop | The run history shows "Transcribed during the pause"; the answer feels quicker than alpha.136 | UNVERIFIED |
| 6.6 | Dictate "pull up the picture thing" with the mic, wait a second, tap Send | The trace says "decided while you spoke" | UNVERIFIED |
| 6.7 | Settings → Live voice → "Voice button for the car" on | The large button is on screen as in Driver mode; the wave in the Ask bar uses the same conversation | UNVERIFIED |
| 6.8 | Settings → Visual quality → Lite, then 6.2 | The orb holds still, the panel fades in without growing | UNVERIFIED |
| 6.9 | Open a task's work island, let the task finish while it is open | The island stays open in its finished state until you close it | UNVERIFIED |

## alpha.138 · OneMind (plan 69)

OneMind is the only engine for Flash and Mind. Watch the task card: milestones, the step line, wakes and cost.

| # | Steps | Expected | Result |
|---|---|---|---|
| 7.1 | Instant: "turn it up a bit" | Volume up at once, as before (Instant is unchanged) | UNVERIFIED |
| 7.2 | Flash: "turn on dark mode" (with dark mode already on) | Finishes at once: "Already on", checked; no steps taken | UNVERIFIED |
| 7.3 | Flash: "open WhatsApp and search for Anna" | The card shows the step line moving; 0–1 wakes; cost under a cent | UNVERIFIED |
| 7.4 | Mind: "find my last invoice email and tell me the amount" | Milestones on the card tick off; the amount in the end report is exactly what the mail says | UNVERIFIED |
| 7.5 | Mind: "text Anna I'm on my way" | A review before the send milestone; the usual approval card for the exact text; nothing sent without it | UNVERIFIED |
| 7.6 | During 7.4, Steer: "use the work account instead" | The card shows a new plan version; the run follows the new goal | UNVERIFIED |
| 7.7 | A task that hits a login screen | A side trip to log in (vault fills passwords), then back to the paused milestone | UNVERIFIED |
| 7.8 | A task that can't succeed (an app that isn't installed, asked three ways) | A check-in card: "This seems stuck on … Keep going, change course, or stop?" | UNVERIFIED |
| 7.9 | Settings → Speed → Compact history at → 50k, then a long Mind task (20+ steps) | The trace shows "History compacted" at a milestone boundary; the run keeps its facts | UNVERIFIED |
| 7.10 | Repeat 7.3 a second time | The trace says "Started from a recipe"; no planning call | UNVERIFIED |
| 7.11 | Pause a Mind task from the card, wait, resume | It holds, then continues where it was | UNVERIFIED |
| 7.12 | Glass → Phone → Ask "open WhatsApp and search for Anna" | Glass shows the milestones, then "Flash · n wakes · $… · <step>" under them | UNVERIFIED |

## alpha.142 · What only broke on the phone

| # | Steps | Expected | Result |
|---|---|---|---|
| 8.1 | "open ad.nl" | Chrome opens ad.nl and the task ends as done (it used to keep going) | UNVERIFIED |
| 8.2 | "open reddit.com on Chrome and login for me", already signed in | Done once the page shows you are signed in | UNVERIFIED |
| 8.3 | A Mind task, e.g. "find Anna in WhatsApp and open her chat" | No step, search or target reads "null" in the run's steps | UNVERIFIED |
| 8.4 | Install over 141 with `adb install -r`, `adb logcat -s PackageManager PackageInstaller` running | Installs; if not, the `INSTALL_FAILED_…` code names why | UNVERIFIED |

## alpha.143 · Prove it did it (plans 71 and 72)

| # | Steps | Expected | Result |
|---|---|---|---|
| 9.1 | Open Instagram, then ask "open my profile" | Your profile opens. Never "I don't see Profile on this phone" | UNVERIFIED |
| 9.2 | From the home screen, ask "open Instagram and then my profile" (typed and spoken) | Ends on your profile page, not Instagram's home page | UNVERIFIED |
| 9.3 | From the home screen, ask "open snapchat" (not installed) | "I don't see Snapchat on this phone." | UNVERIFIED |
| 9.4 | "open WhatsApp" from the home screen | Opens at once (Instant), done only once WhatsApp is in front | UNVERIFIED |
| 9.5 | "scroll down" at the end of a list | Says nothing moved, and isn't counted as a success | UNVERIFIED |
| 9.6 | A Flash task in an app Cyclone hasn't used, e.g. "open Spotify and go to my library" | Done only on the library page; the run trace shows two decision answers per step and one done check | UNVERIFIED |
| 9.7 | Run 9.2 twice | The second run's steps say "Learned in this app before"; `Cyclone Brain/Moves/com.instagram.android.json` holds labels only | UNVERIFIED |

