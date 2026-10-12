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
