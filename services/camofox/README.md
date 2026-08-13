# Camofox browser boundary

Cyclone can use [Camofox Browser](https://github.com/jo-inc/camofox-browser),
an MIT-licensed upstream browser service, as a **private, internal** browser
runtime.  Cyclone Core calls it through `app.camofox_client.CamofoxBrowserClient`.
The adapter deliberately exposes a smaller surface than upstream.

## Security model

An agent never chooses Camofox's `userId`, storage profile, proxy, or cookie
source.  Core must create a durable `BrowserAccessGrant` after an explicit
resource grant.  That grant carries:

- the real Cyclone agent, conversation, and resource IDs;
- a deterministic private profile ID, `cyclone-agent-<agent UUID>`;
- a conversation-scoped Camofox session key; and
- an exact allow-list of HTTP(S) origins plus optional expiry.

The adapter rejects ungranted origins locally before a request is made.  It
also closes the tab if an upstream redirect lands outside the grant.  Every
returned value includes non-secret audit metadata: request ID, agent,
conversation, resource, profile, session, operation, timestamp, timeout, and
content limits.  Core should persist that metadata through its normal audit
event path before exposing a result to Hermes.

The initial adapter supports only:

- open or navigate an already-authorized tab;
- token-bounded accessibility snapshots (screenshots disabled);
- a single, bounded scalar extraction based on prior snapshot refs; and
- captured-download metadata, with the configured size limit identified.

It intentionally does **not** provide cookie import/export, persisted-session
transfer, proxy controls, anti-detection/bypass controls, raw page evaluation,
file upload, form submission, arbitrary scraping loops, inline download data,
or browser tracing.  Those operations need separate product policy and a
human-approved capability before they can be safely added.

## Compose integration boundary

This repository does not add Camofox to `docker-compose.yml` yet.  The
Environment Manager workstream must own that change after it can persist
`BrowserAccessGrant` records and enforce them at the Core/MCP boundary.

When that workstream wires the service:

1. Pin an upstream Camofox image/version after its release and license review;
   do not use an unpinned `latest` image.
2. Attach it only to Cyclone's internal Compose network. Do not publish port
   `9377` to the host or internet.
3. Configure a distinct high-entropy `CAMOFOX_ACCESS_KEY` on the service and
   pass the same secret to Core as `CAMOFOX_API_KEY`; upstream's access key
   gates normal browser routes.
4. Mount one service-owned profile volume at Camofox's profile directory. Do
   not mount host browser profiles, cookie exports, Docker credentials, or the
   Cyclone source checkout into the service.
5. Disable upstream crash telemetry with
   `CAMOFOX_CRASH_REPORT_ENABLED=false`; leave third-party Sentry configuration
   unset unless Cyclone explicitly approves and documents it.
6. Set a conservative Camofox tab/session limit and use Core's configuration
   limits: `CAMOFOX_TIMEOUT_SECONDS`, `CAMOFOX_MAX_SNAPSHOT_CHARACTERS`, and
   `CAMOFOX_MAX_DOWNLOAD_BYTES`.
7. Add a Core health dependency only after browser restart/recovery tests are
   in place. Camofox availability must not block messaging or Hermes runs.

Upstream currently documents a REST API on port `9377`, accessibility
snapshots, separate Camofox session profiles, and optional C++-level
fingerprint modification.  Cyclone uses the standard browsing/context API
only; it does not expose evasion, cookie, or proxy features to its agents.

## Required follow-up before enabling agent tools

1. Persist browser access grants and resource grants in Core.
2. Register read-only `open`, `navigate`, `snapshot`, and `extract` MCP tools
   that resolve the current agent identity server-side, never from tool input.
3. Send every audit record to the durable event/outbox system.
4. Add private-profile, cross-agent isolation, expiry, redirect, crash, and
   restart-recovery integration tests against a real Camofox container.
