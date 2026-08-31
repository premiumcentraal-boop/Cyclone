# V4 slice 5 — golden locate (fixture-green / Pixel-unverified)

**Status:** contract tests + 12 synthetic page-card fixtures landed on `agent/golden-locate-v38`.  
**Physical Pixel 8:** skipped. Do not claim VERIFIED.

Slice 5 asked for 12 golden pages from Pixel 8 where `phone_locate(goal)` finds the labelled control. This patch ships the **contract** against synthetic JSON page cards:

| Fixture | Goal | Target signal |
|---|---|---|
| Settings home | Network & internet | label |
| Settings apps | See all apps | label |
| Messages thread | Send SMS | label + contentDescription |
| Chrome blank | Search or type web address | label |
| Play Store | Search Google Play | label |
| Clock | Alarm | contentDescription only |
| Calculator | Equals | label |
| Phone dialer | Voice call | resource-id |
| Files | Downloads | label |
| Chrome search results | Wikipedia | label |
| Food-shop cart | Go to checkout | label |
| Pay confirmation | Place order | label (no secrets) |

`compact_observation()` must keep `pageText` / `pageSummary`. Silent drop is `AGENT_CONTEXT_TRUNCATION` and fails the test.

Do not merge until Agents 1 and 2 land. Do not bump `versionName`. Do not tag 4.0.0.
