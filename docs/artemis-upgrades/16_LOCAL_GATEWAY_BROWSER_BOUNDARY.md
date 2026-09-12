# Harden the local gateway browser boundary

**Priority: P1 audit, implementation conditional on exposure. Scope: browser-facing host services. Implementation size: small to medium. Status: proposal, not implemented.**

## Finding

Artemis's admin console has an ASGI boundary that checks Host and browser Origin for both HTTP and WebSocket requests. It adds baseline browser security headers and no-store behavior for API responses.[^1] The code documents DNS rebinding and cross-origin requests as concerns even for a loopback service.

The actual policy permits IP-literal hosts, configured hostnames, and some configured-origin exceptions. It also permits non-browser requests without Origin and has a disable switch. Artemis's console assumes whoever can reach its TCP port is the operator. Cyclone should not adopt that authentication model or copy every exception.

## Cyclone comparison

The inspected Cyclone Device Gateway factory attaches bearer authentication to its versioned device/action routes and uses configured bind settings. No corresponding Host/Origin middleware appears in that factory.[^2][^3] This is a defense-in-depth review opportunity, not a demonstrated authentication bypass or a claim that every Cyclone service is vulnerable. Existing bearer verification is a valuable protection to retain.

## Proposed change

Inventory all host HTTP/WebSocket listeners, bind defaults, browser UIs, tunnel support, credential transport, and authentication dependencies. Determine which routes a browser can reach and which share the same app factory. Scope the implementation to actual exposed surfaces rather than adding conflicting middleware indiscriminately.

For browser-facing services, define explicit allowed hosts and origins, including scheme and port where relevant. Reject malformed, null, or unapproved browser origins. Require existing bearer/session authentication independently of origin checks. Non-browser native clients may omit Origin but must still authenticate. Do not trust forwarded host headers from an unconfigured proxy.

Add appropriate no-store responses for task data and diagnostics, and baseline framing/content-type/referrer protections consistent with the real UI. Apply the boundary before WebSocket upgrade as well as HTTP routing. Keep configured tunnel support explicit; do not introduce wildcard origins with credentials or a routine production bypass switch.

Use typed configuration validation and actionable errors so a legitimate local client is not mistaken for a device failure. A browser-boundary failure must never trigger a model retry or weaken authentication automatically.

## Acceptance and rollout

Test expected localhost and IPv6 access, an allowed tunnel, attacker-controlled Host, cross-origin browser requests, null Origin, absent Origin with valid/invalid bearer, and WebSocket upgrades. Verify that existing native MCP/companion clients continue to work and sensitive responses are not cacheable.

First document the listener inventory and assess whether the current authentication and exposure already contain the scenario. Add enforcement only where justified, with compatibility tests for each supported client. Rollback a faulty origin rule while retaining bearer authentication and loopback defaults. This proposal addresses host-service resilience; it does not explain the Reddit cookie stall and should not displace P0 execution work.

## Sources

[^1]: Artemis, [`apps/admin_console/core/security.py`](https://github.com/google/artemis/blob/371aa6df56880643da57b30da936e9812fb0ec66/apps/admin_console/core/security.py#L100), `class SameOriginBoundaryMiddleware`.

[^2]: Cyclone, [`apps/device-gateway/cyclone_device_gateway/server.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/device-gateway/cyclone_device_gateway/server.py#L349), `def create_app`.

[^3]: Cyclone, [`apps/device-gateway/cyclone_device_gateway/auth.py`](https://github.com/premiumcentraal-boop/Cyclone/blob/c90dcfa3b047b638ab35400bbac7c9e09840d325/apps/device-gateway/cyclone_device_gateway/auth.py#L16), `def verify_bearer`.
