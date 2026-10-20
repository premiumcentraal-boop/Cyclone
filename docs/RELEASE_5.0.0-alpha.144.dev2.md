# Cyclone alpha.144 dev2 — owner test candidate

Not published. Prepared on `codex/connector-capacity-reliability` for the existing
protected signed-owner-test workflow. Mobile code 299 preserves update ordering;
PC/Glass components remain unchanged.

- Raise the bounded per-connector-installation configuration quota from 256 to
  8,192 records. The old limit prevented a 327-app Cloak inventory from syncing.
- Retain existing atomic files, namespace isolation, request validation, 4 KiB
  value limit and BAD_REQUEST error envelope. No migration or record deletion.
- Fail closed when a new-record inventory cannot be read. Existing records can
  still be updated at quota.
- Make one existing overlay source guard insensitive to Windows CRLF; the
  checked behavior is unchanged.

Five new capacity tests cover 327 apps, twenty such profiles, the new boundary,
updates at/above quota and malformed counts. The full mobile suite passed 2,909
tests before the candidate-only identity bump. CI and physical verification of
the resulting signed artifact must be recorded separately. No account,
permission, bootstrap, binding-identity or platform-verdict change.
