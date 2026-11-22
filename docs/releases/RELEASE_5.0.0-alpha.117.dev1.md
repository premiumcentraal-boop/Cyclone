# Cyclone V5 Alpha 117: VMOS phones, rented from Glass

Developer alpha for owner testing. It builds on alpha.116 dev1 (Glass Cloak identities) and includes it.

- **Mobile:** `5.0.0-alpha.117.dev1` (version code 270). No app changes; the version moves with the release.
- **PC runtime:** gateway and MCP `5.0.0-alpha.117.dev1`.
- **Glass:** `1.0.0-alpha.64`.

This alpha starts plan 56 (`Cyclone V5 plan/56-vmos-fleet.md`): VMOS Cloud phones that are easy to add, arrive with
Cyclone and our skills, and stay connected over ADB.

## Rent VMOS phones from Glass

Glass → Devices → Cloud phones → a VMOS account → **Rent phones**.

| Choice | What it is |
| --- | --- |
| **By the period** | A phone that is yours for the period VMOS offers (a day, a week, a month…), always on. Optional automatic renewal. |
| **Pay-for-time** | A phone you pay for only while it is powered on. Power it off in Glass when it isn't working; everything on it is kept. |

- Pick Android 13, 14 or 15 (Cyclone's floor is 13), a plan and period, and 1–5 phones.
- Glass shows the total, then asks **"Confirm and pay $X"**. Nothing is paid before that.
- The gateway re-reads VMOS's prices and refuses if VMOS now asks anything other than the total the owner confirmed.
- **New phones join by themselves.** They are kept connected, so Cyclone and the starter skills arrive on them without
  another click. A period rental appears once VMOS has made it (Glass says it is being made). A pay-for-time phone
  appears at once.

## On each VMOS phone

| Button | What it does |
| --- | --- |
| **Power off / Power on** (pay-for-time) | Off always keeps the phone's data; Cyclone lets go of its link first and doesn't keep retrying. On never asks VMOS for a "new device". The card shows "on for 42 min". |
| **Renew** (rentals) | Pick a period; Glass asks "Confirm and pay $X" first. |
| **Auto-renew: on/off** (rentals) | Switches VMOS's automatic renewal for that phone. |
| **Back up** | Only when the owner presses it. Cyclone asks VMOS for the size first (as VMOS asks), then follows the backup to done or says why it failed (for example, cloud storage is full). One backup at a time per account, as VMOS allows. |
| **Restore…** | Lists this phone's own backups. Restoring asks first: "It replaces everything on the phone now." |
| **Pay-for-time phone?** | For a phone rented in VMOS's console: mark it pay-for-time so it can be powered here. |

The card also shows "Paid until 15 Oct", or "Rental ends in 36 h" when less than two days are left.

## Also in this alpha: the VMOS connectors

- **V2 signing.** VMOS now documents only its V2 scheme (three headers, SHA-256), so this release switches to it. The
  old HMAC scheme stays as a one-try fallback, and the scheme that works is remembered per account.
- **Sign-in problems in plain words:** this PC's clock is off; an unknown key; this PC's IP missing from VMOS's API
  allow list.
- **ADB switched on by API** (`openOnlineAdb`) when VMOS answers with an incomplete link.
- **A moved phone is followed.** When VMOS gives a phone a new padCode, it keeps its local port, so it is the same
  fleet phone.
- **Keep-alive.** VMOS is asked to keep Cyclone's service running.
- **Owner names, Android versions and paid-until dates** from VMOS's phone list.
- **Status 16** (backing up) reads as "starting".

## Safety

- **Owner-only routes.** Every button is an owner route under `/v1/cloud`. No MCP, agent or model tool reaches
  them; a CI guard checks this.
- **Money moves only for the confirmed total**, 1–5 phones per order. The CI guard checks the price check is on both
  rent and renew.
- **Nothing is destroyed:**
  - power-off always keeps the environment;
  - power-on is never a "new device";
  - Cyclone has no call that deletes a phone.
- **A phone is restored only from a backup Cyclone made of that same phone.** Cloning one phone's backup onto another
  would copy its Cyclone pairing; that waits for plan 56 V3 (the golden phone, with re-enrollment).
- **Excluded VMOS features.** VMOS's own taps, SIM and device-identity changes, app hiding, cloud numbers and social
  accounts stay out (plan 56 §2.4).

## Tests

- **Gateway:**
  - `test_cloud_rent.py` (9 tests) covers offers as a signed GET; rent, renew and pay-for-time payloads; power on and
    off; backup steps; the price check; new phones being kept; power-off without retries; backup and restore rules;
    and the route bodies.
  - `test_cloud_fleet.py` covers V2 signing against VMOS's own example, the fallback, sign-in errors,
    `openOnlineAdb`, padCode moves and keep-alive.
- **Glass:** renting with confirm and total, pay-for-time power and pricing, renew with confirm, auto-renew, backup
  and restore with confirm.
- **CI guard:** the VMOS endpoint allowlist, the money, power and backup rules, and the excluded endpoints by name.

## Limits

- **UNVERIFIED against a real VMOS account.** Every call follows VMOS's own OpenAPI pages (2026-10-08) and is tested
  against their documented answers, but none has been made on the owner's account yet. In particular:
  - which id VMOS wants as `goodId` when renting by the period (Cyclone sends the period's SKU id);
  - that prices are in cents;
  - whether a new pay-for-time phone starts powered on.
- **Physical: UNVERIFIED.** No phone was available for this build.
- **Not yet built (plan 56 V1–V4):**
  - provisioning without a tap;
  - fleet skill sync;
  - golden-phone cloning;
  - groups and scheduled power for pay-for-time phones.
