# Cyclone V5 Alpha 106: Profile config and startup providers

Based on published mobile `5.0.0-alpha.105.dev1`, source `eefa4e869040cb7074c80d0b9c6607d51adcb772`.
Mobile: `5.0.0-alpha.106.dev1`, version code 251. PC, gateway, MCP and Glass components are unchanged.

Adds two independent approved connector scopes, with no new screens or feature behavior:

- `profiles.config`: get/set a 4 KiB opaque JSON object and generic status per profile/user/app tuple.
  Storage is atomic and private, separated by connector installation, with a stable tuple key.
- `profiles.startup`: register a versioned Binder provider to receive an event before Cyclone dispatches a scoped
  app launch. It may return no reference or an opaque reference. Cyclone never opens or executes the reference.
  Full Android UID identifies registrations; an owner-user provider is never reused in another user.
- Callback timeout, death, late/duplicate replies and unsupported versions are handled without vetoing launch.
  Total callback deadline is 250 ms. Status can be read over Binder without UI.
- The SDK includes registration/config helpers, a neutral provider base class, and stable connector-owned private
  state directories. Updated schemas and test vectors accompany the connector kit.

This extension covers Cyclone-initiated launches, not system-wide app startup. Event categories are Cyclone dispatch
hints rather than proof of OS process state. Registration is ephemeral and must be repeated after process death.
Cross-user callback delivery requires registration with a Cyclone instance in that Android user; no cross-user
privilege or new background persistence mechanism is introduced.

Contract and examples: [Connector SDK specification](../tools/cyclone-connector-sdk/SPEC.md#10-profile-config-provider-minor-1-alpha106).

Physical Android acceptance, including two-user installations and update compatibility: **UNVERIFIED**.
Build/test results are recorded in the implementation PR; this source version is an alpha candidate until CI verifies it.
