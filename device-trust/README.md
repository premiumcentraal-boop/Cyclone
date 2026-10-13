# Device Trust

Standalone, bank-grade Android device verification. Not part of the Cyclone
product or its release identity.

- [`service/`](service/README.md): server-side verifier (hardware key attestation, Google revocation list, Play Integrity, attested device identity). It makes the decision.
- [`android/`](android/README.md): Kotlin client library that collects the evidence.

Phase 1 (this folder): enrollment with hard-fail gates. Next: device binding
(Phase 2), client RASP signals + risk score (Phase 3), policy tuning and
operations (Phase 4).
