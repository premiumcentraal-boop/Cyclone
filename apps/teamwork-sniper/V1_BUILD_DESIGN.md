# Teamwork Sniper V1 — Android Build Design

Status: **approved V1 visual direction**

This document is the direct implementation contract for the V1 Android UI.

The build should reproduce the approved reference flowboard as closely as practical in native Jetpack Compose. The goal is not to reinterpret the design. The goal is to implement the same product feeling, hierarchy, states and flow.

## 1. Product feeling

Teamwork Sniper should feel:

- extremely simple;
- fast;
- light;
- schedule-first;
- clean and native;
- confident rather than technical;
- visually familiar to a modern workforce scheduling product;
- unmistakably Teamwork Sniper through the orange targeting language.

The user should not need to understand parsers, Accessibility nodes, rule schemas or decision engines to use the normal UI.

## 2. V1 flow

```text
Welcome
  ↓
Quick Setup
  ↓
Choose shifts
  ↓
All Set
  ↓
Schedule ───── Activity ───── Settings
                              │
                              ├ Shift templates
                              ├ Overlay mode
                              └ Diagnostics
```

## 3. Core screens

### Welcome

Visual target:

- large calendar/target hero;
- `Teamwork Sniper`;
- `Never miss the shifts you want`;
- one orange `Get Started` button;
- almost no technical text.

### Quick Setup

Two polished cards:

- Notification Access
- Accessibility Access

Each card has:

- icon;
- plain-language explanation;
- status;
- Enable/Manage action.

Continue only after both required permissions are enabled.

### Choose Shifts / Schedule

This is the heart of the product.

Structure:

```text
This Week
Week 36
31 Aug – 6 Sep

Monday 31 Aug

┌─────────────────────────────┐
│ M1   08:00 – 10:35   Snipe │
└─────────────────────────────┘

███████████████████████████████
█ S2   16:55 – 19:30 Sniping ✓
███████████████████████████████
```

The week is browsable with previous/next controls.

Days are vertically grouped.

Every shift is one large, easy-to-tap row.

## 4. Visual state contract

### Available to Snipe

- white background;
- orange border;
- orange shift code;
- orange `Snipe`;
- dark time;
- hollow visual state.

### Selected for Sniping

- full orange background;
- white text;
- `Sniping ✓`;
- one tap again removes selection.

### Claimed

Only after real post-action verification:

- soft green background;
- green text;
- `Claimed ✓`;
- not presented merely because a click was sent.

### Open now

Only from recent real Teamwork semantic evidence:

- small soft-green badge;
- does not itself imply selected or claimed.

### Unknown time

Never invent an expected time.

Show:

`Time to be confirmed`

## 5. Design tokens

Primary orange:

`#FF6500`

Soft orange:

`#FFF3EB`

App background:

`#F7F8FA`

Primary text:

`#121826`

Secondary text:

`#697386`

Borders:

`#E4E7EC`

Success:

`#159455`

Success soft:

`#EAF8F0`

Watching blue:

`#3478F6`

Corner radii:

- major card: ~22dp;
- normal component: ~16dp;
- small control: ~12dp.

Main CTA height:

- ~56dp.

Shift touch target:

- minimum ~66dp high.

## 6. Navigation

Persistent bottom navigation after onboarding:

- Schedule
- Activity
- Settings

Selected destination uses orange icon/text with a soft orange indicator.

No hamburger menu is required in V1.

## 7. Activity

Activity should read like a calm timeline, not debug logs.

Examples:

- Open shift detected
- Claim attempt checked
- Shift claimed successfully

Statuses:

- Watching = blue
- Checking = orange
- Claimed = green

Detailed engine/failure evidence may be shown as secondary text, but the primary label must stay human-readable.

## 8. Settings

Settings should be organized into large white rounded groups.

Primary controls:

- Sniper enabled
- Armed mode
- Shift templates
- Overlay mode
- Diagnostics
- Notification access
- Accessibility access
- optional AI advisor status

Important:

`Selected != Armed != Claimed`

Keep these concepts visually and behaviorally separate.

## 9. Overlay mode

Overlay mode remains experimental.

If enabled, it may augment Teamwork with Sniper choices. It must never make visual geometry the claim identity.

The native schedule is always the fallback.

The Overlay Preview screen should explain the concept visually with simple day columns and orange candidate blocks.

## 10. Honesty rules

Never fake live Teamwork state for visual polish.

Do not show:

- claimed without post-action verification;
- open now without recent semantic evidence;
- exact times when the template is unconfirmed;
- assigned state unless real Teamwork evidence supports it.

The product may look polished while remaining explicit about uncertainty.

## 11. Build rule

The UI must remain a presentation/controller over the existing deterministic Teamwork Sniper runtime.

Do not create a second:

- claim engine;
- parser;
- rule store;
- AI authority;
- action authority.

## 12. Definition of done

V1 is ready for user testing when:

- onboarding is polished;
- Schedule / Activity / Settings all work;
- shift selection persists through the existing RuleStore;
- hollow ↔ filled selection interaction is immediate;
- claimed state is evidence-backed;
- permissions are understandable;
- overlay is clearly experimental;
- app icon and V1 identity are present;
- unit tests pass;
- release APK builds;
- GitHub release contains the exact V1 APK and checksum;
- remaining physical-device claims are clearly marked unverified until the user tests them.
