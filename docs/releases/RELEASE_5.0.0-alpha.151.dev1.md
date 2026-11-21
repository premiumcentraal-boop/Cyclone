# Cyclone Alpha 151: the Lab measures the owner's features

Developer alpha for owner testing. It is built on Alpha 150 and installs over it as a normal update. Nothing here has
run on a phone: the Lab needs the Pixel, and every number the research produces is still to be measured.

## Versions

- **Mobile:** `5.0.0-alpha.151.dev1` (version code 305).
- **Cyclone for Windows, gateway and MCP:** `5.0.0-alpha.151.dev1`.
- **Cyclone testbench:** `1.3.0`.
- **Glass:** unchanged, `1.0.0-alpha.68`.

## Why this release

The Lab could already run an arm without the owner's app map (`useMap`) or without the owner's memory (`freshMemory`).
It could not run an arm without Cyclone's built-in tested Android skills: the reviewed Instagram routes. Those routes
reached the model in two places (the goal's guidance and `abilities_find`), and neither was switched off by `useMap`.
This release adds the missing switch, and the testbench uses the three switches for the Instagram research.

## What changed

**A `skills` Lab knob (phone, gateway, MCP):**
- `skills: false` withholds the built-in tested Android route guidance from the model: the guide in the prompt, and the
  route advice `abilities_find` returns for Instagram.
- The goal still runs. A goal worded as a skill is still given to the model; only the reviewed route is withheld.
- The default is `true`, so every owner run is unchanged. A variant that leaves the knob out is not sent to older
  phones, which refuse unknown keys.

**Instagram app-map missions (testbench, `missions/testbench-instagram.json`):**
- `ig-maps`: 8 plain-language goals (Reels, message requests, Primary and General messages, Tagged posts, followers,
  Close friends, your own profile). They carry no skill marker, so the built-in skill is not in the goal.
- `ig-skills`: 7 goals carrying the built-in skill's own wording, copied exactly from the Kotlin listing. They are the
  read-only skills: profile, profile editor, account search, saved items, profile QR, settings, message requests.
- Every mission is read-only, and every check reads the app's own labels, never a name, a message or a photo.

**Research (testbench `cyclone-testbench research plan | start | report`):**
- `no-skills`: the app map on or off, by owner memory or fresh memory, with the built-in skill off on every arm.
- `skills`: the built-in skill on or off, by the app map on or off, with fresh memory on every arm.
- Every arm sets `useMap`, `freshMemory` and `skills` explicitly. The generator refuses an arm that leaves one out,
  because the phone defaults `freshMemory` to fresh when a variant omits it.
- The report gives, per factor, each side's pass rate with a Wilson 95 % interval, the difference with a Newcombe
  interval, and a two-sided Fisher exact p. It also gives median time and turns, the repeat effect (the first run
  against later runs of the same mission), safety failures and false successes. Infra and skipped runs are counted
  apart and never in a rate.

## What did not change

- Approvals, the gates on sending, paying, deleting and permissions, and the secret rules. The Lab still declines every
  approval, and the fleet layer still never answers one.
- `useMap` and `freshMemory` keep their meaning and their defaults.
- Task Kit for every stop, and the Lab's own safety rules.

## Left out on purpose

- **The post flow (prepare an Instagram post).** The reviewed skill uses the owner's newest gallery photo. A Lab run
  sends screens to a model, so the research would send a private photo. It needs a test photo first.
- **Comments and account setup.** Comments need an open post; account setup creates accounts.
- **The stock automations** (cold direct messages, reels following, reels warm-up). They message or follow people.
- **Other consumer apps.** Only Instagram has reviewed skills today. Other apps can be added with the same knobs once
  their maps and skills exist.
- **Marketplace skills in the Lab.** The Lab still never uses a marketplace skill (`lab == null`), so the research
  covers only the built-in Instagram skills.

## Validation

- **Android:** `:app:testDebugUnitTest` passed (2,912 tests). New tests in `MindLabTest`: `skills` defaults to true,
  `skills: false` round-trips, and a non-boolean `skills` is refused.
- **Gateway:** `apps/device-gateway/tests/test_lab.py` and `scripts/ci/tests/test_skills_knob_guard.py` passed (51
  tests). The guard checks that the phone's variant keys and the gateway's match, and that the MCP tools accept `skills`.
- **MCP:** `tools/codex-phone-mcp` (192 run, 1 skipped) and `tools/cyclone-agent-mcp` (93 passed).
- **Testbench:** `tools/cyclone-testbench/tests` passed (76 tests). They check that every mission parses with the
  gateway's own parser, that no goal asks for an action that changes anything, that no goal reads a photo or the
  gallery, that no goal keeps a placeholder, and that every research arm sets all three knobs.

## What is UNVERIFIED on a real phone

- Whether the built-in skill helps or hurts on Instagram: the research has not been run.
- Whether the app map helps, and whether owner memory helps repeated runs.
- The Instagram labels the checks rely on. They come from the app map sample (Instagram 402.0, September 2026) and from
  the reviewed skills (Instagram 449.0.0.52.84). The report should record the version on the phone.
- Every Lab number, and the Lab gate table in plan 75 (NOT MEASURED).

## Before running it on the owner's account

- Run the research on a test Instagram account where you can. The missions are read-only, but the Lab still opens
  the owner's account on the owner's phone.
- The map learns each mission as it ends, so repeats of an arm with the map on are not independent.
