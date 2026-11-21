# 76 · Decision records

Plan 76 §3 lists twelve decisions that change the design. Each one below has the default the plan uses, why, what it
would change, and its status. **None is answered by the owner yet.** Stage 0's exit gate needs every row answered,
whether the answer is "keep the default" or something else.

How to answer: change the **Owner's answer** line of a decision and its status to `Decided (date)`. A decision can be
reopened later. When it is, a new entry goes below the old one; the old one is never edited.

---

## D1 · Storage engine

- **Default:** keep one SQLite file (WAL) for the single-owner PC platform. All new code goes through the store layer
  (`command/store`) so a hosted engine can replace it later.
- **Why:** the platform runs on the owner's PC. SQLite is fast enough for the D8 target as far as stage 0 can measure,
  needs no server, and backs up as one file. A hosted engine only pays off for a hosted, multi-owner product.
- **Would change:** stages 1, 14 and 16 (store implementation, scale tests, backups).
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D2 · One owner or a team

- **Default:** one owner. Every record carries an actor and a scope, so roles can be added without a rewrite.
- **Why:** the product today has one owner per PC. Designing the fields now costs little; building roles now costs a lot.
- **Would change:** stages 1 (actor fields), 14 (comments and mentions of people) and 15 (who the AI may answer).
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D3 · Pacing caps and block lists for account creation

- **Default:** reinstate pacing caps and an app block list as defaults the owner can change. Lowering a cap shows a
  warning that says what it risks.
- **Why:** on 2026-09-30 the owner removed the pre-send checks, the block list and the pacing caps "for now" (plan 43
  §10, T7). Without them, account creation at volume carries ban and legal risk with nothing to slow it down. This
  default keeps the owner in charge and makes the risk visible.
- **Would change:** stages 5 and 8.
- **Owner's answer:** —
- **Status:** default, awaiting the owner. It reverses the 2026-09-30 relaxation as a default only.

## D4 · Which services accounts may be made on

- **Default:** an owner-kept allow list. The platform blocks nothing by default, and shows the list on every
  account-creation screen.
- **Why:** each service's terms are the owner's to accept. The platform should make the choice visible, not make it.
- **Would change:** stage 5.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D5 · Analysis model

- **Default:** the owner's OpenRouter model, set where Ask AI is set today.
- **Why:** one key and one place to set it. The fleet layer stays model-free (`AGENTS.md`).
- **Would change:** stage 15.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D6 · Cloud provider order

- **Default:** VMOS first, DuoPlus second (plan 56).
- **Would change:** stages 9 and 16.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D7 · Lab screen-copy retention

- **Default:** Lab missions' local screen copies (alpha 153) are deleted after 7 days, and each deletion is recorded.
  Owner missions never save screens, as today.
- **Why:** the copies are scrubbed of secrets but still show the owner's apps. A week covers a night loop and its
  analysis.
- **Would change:** stage 12 and §11 of the plan.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D8 · Scale target

- **Default:** 1,000 phones; 5,000 accounts; 100,000 rows per database; 1,000,000 events a day.
- **Why:** an order of magnitude above today's use, so the design does not need replacing as the fleet grows.
- **Note from stage 0:** today's tables are capped at 10,000 rows (`command/tables.py`, `MAX_ROWS`). The benchmark seeds
  to that cap. Stage 3 raises it.
- **Would change:** stages 3, 7, 10 and 16.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D9 · Vault unlock on Windows

- **Default:** Windows Hello, with the passphrase as the fallback.
- **Would change:** stage 12.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D10 · Signed Windows installer

- **Default:** the owner obtains a code-signing certificate before stage 16. Until then, releases stay `CI_UNSIGNED`.
- **Would change:** stage 16.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D11 · Editor foundation

- **Default:** a hand-built editor, inside the Glass rules (no runtime packages, no `innerHTML`).
- **The alternative:** vendor a reviewed, MIT-licensed editor core into the repository, with an `Origin:` header on
  every file and a narrow exception in `scripts/ci/glass_guard.py`.
- **Trade-off:** hand-built keeps the supply-chain rule whole but is the slowest path to Notion-grade editing. Vendoring
  is faster and better tested, but adds a large body of outside code that Cyclone must keep patched.
- **Rule in the plan:** if stage 2's undo-and-selection run misses its gate twice, this decision is reopened with the
  evidence.
- **Would change:** stage 2.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

## D12 · Where preferences are kept

- **Default:** on the gateway, per owner: favourites, recents, theme, column widths and sidebar state.
- **Why:** Glass may not use `localStorage` (`scripts/ci/glass_guard.py`), and `sessionStorage` lasts one tab only.
- **Would change:** stage 2.
- **Owner's answer:** —
- **Status:** default, awaiting the owner.

---

## Decisions taken during stage 0 (by the build, within its scope)

These are implementation decisions inside the stage. They are recorded so they can be questioned.

| # | Decision | Why |
|---|---|---|
| S0-1 | The SQL guard is a **ratchet**: each module's direct SQL count is recorded and may only go down. It is not a ban on existing code. | Moving 615 direct SQL calls in 18 modules behind repositories in stage 0 would rewrite the whole Command Center with no feature to test it against. Each later stage moves the modules it touches. |
| S0-2 | Migrations are **plain SQL**, checksummed over their text. | The Windows package is a frozen build, where Python source is not available to checksum. SQL text is stable everywhere. |
| S0-3 | A file written by a newer Cyclone **stops the runtime** with a clear message, and nothing is written to it. | Opening a newer file with older code risks silent damage. Cyclone does not support downgrades on the phone either. |
| S0-4 | Migration 1 **adopts** the alpha 155 schema without changing it. The stores still create their own tables; every later schema change is a numbered migration. | It records a starting point without touching data the owner already has. |
| S0-5 | Before a migration that changes anything, the file is **copied** next to itself (`command/backups/`) and the newest five copies are kept. | A failed upgrade can be restored by hand. Encrypted, verified backups are stage 12. |
| S0-6 | Stage 0 works on the designated branch `claude/vigilant-goodall-dy6z4y`, not `platform/s00-…` as plan 76 §8 names it. | This session may push to that branch only. |
