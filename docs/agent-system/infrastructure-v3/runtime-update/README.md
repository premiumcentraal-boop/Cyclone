# Cyclone Runtime Update

This package stages **signed non-native resources** so Cyclone can improve selected runtime data
without rebuilding the APK. It is intentionally not wired into the running app in this feature
branch. Integration owns the Android persistence, download transport and Recovery Manager wiring.

## Compatibility identity

`RuntimeApiVersion` is the compatibility identity for these resources. It is independent of
`BuildConfig.VERSION_NAME`, `versionCode`, module versions and resource schema versions. A marketing
release does not imply a runtime API change.

## Update allowlist

Only typed `RuntimeResourceKind` values may enter a verified manifest:

- workflow definitions;
- policy data;
- model-routing configuration;
- prompt/templates;
- skill metadata;
- app-learning rules;
- signed static assets;
- signed runtime assets that remain data, never executable code.

The wire decoder must use `RuntimeResourceKind.fromWireName` and reject unknown values. The updater
also rejects traversal/absolute paths and executable extensions. It must never be expanded to
deliver Android permissions, Accessibility implementation, native libraries, APK/AAB/JAR/Dex,
Kotlin/classes, WebAssembly, JavaScript or shell scripts. Any such change requires a normal reviewed
APK release.

## Fail-closed pipeline

```text
opaque signed envelope
  -> trusted signature + decoding policy
  -> runtime API / manifest / allowlist / path preflight
  -> bounded payload read
  -> exact size + SHA-256
  -> candidate B staging
  -> resource schema validation
  -> complete candidate marker
  -> side-effect-free health preflight
  -> durable activation request for Recovery Manager
```

The typed manifest is produced only by `RuntimeManifestVerifier`. A hash inside a manifest is an
integrity expectation, not proof that the manifest is trusted. Signature rejection happens before
the source or slot store is used.

Slot A remains `ACTIVE_KNOWN_GOOD`. Failed, partial, incompatible, corrupt or unhealthy content can
only leave slot B failed; it cannot replace or delete A. The updater serializes preparation and a
repeat of the same signed update returns its existing activation request instead of downloading or
requesting activation twice.

`RuntimeUpdateAuditRecord` deliberately excludes payloads, signatures, URLs and exception text.
Production audit and error adapters must preserve that property.

## Android integration checklist

Integration should provide:

1. a cryptographic verifier which verifies canonical bytes against a protected allowlist of keys,
   then decodes and returns the exact verified typed manifest;
2. a bounded HTTPS or local payload source which does not infer trust from the manifest URL;
3. an Android-local atomic slot store implementing `RuntimeSlotStore`, with partial files confined
   below candidate B and fsync/rename behavior appropriate to the filesystem;
4. schema validators for every supported `schemaId` and kind;
5. a health preflight that reads candidate data without activating it;
6. a durable, idempotent Agent 9 activation sink keyed by `updateId + manifestSha256`;
7. a redacted audit sink.

Do not wire this package to `PhoneToolExecutor`, Accessibility, a script engine, a Dex/class loader,
root/shell, `MainActivity`, or a new launcher.

## Agent 9 Recovery Manager handoff

`RuntimeActivationRequestSink` is the only activation boundary. It receives a
`RuntimeActivationRequest` containing the update ID, runtime API version, signed-manifest SHA-256,
sorted staged-resource metadata, `activeKnownGoodSlot = A`, `candidateSlot = B` and request time.

The Recovery Manager owns every action after accepting that request:

- validate that B still exactly matches the request;
- durably observe boot/health and attribute failures;
- decide when B becomes active;
- keep A until the observation window succeeds;
- roll back to A or enter safe mode when required.

The updater has no watchdog, rollback, safe-mode or “mark active” API. The production activation
sink must be durable and idempotent because it is the cross-service commit boundary.
