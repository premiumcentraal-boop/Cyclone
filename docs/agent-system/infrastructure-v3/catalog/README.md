# Cyclone Module Catalog

The Module Catalog is an offline-first, Cyclone-native presentation and management layer over the
frozen Module Supervisor at SHA `72c9c0711c6023a8c421054edcbc521b7c3851bd`.

It does not own module inventory, lifecycle state, installation, update activation, rollback, or
phone actions. `ModuleSupervisorSnapshot` is the authoritative inventory input. Every supported
mutation in `ModuleCatalogController` delegates to a public `ModuleSupervisor` method and then
rebuilds the UI state from a fresh snapshot.

## Architecture

```text
trusted local presentation metadata
                  +
ModuleSupervisorSnapshot
                  |
                  v
      ModuleCatalogPresenter
                  |
                  v
       ModuleCatalogViewState
                  |
                  v
       CycloneModulesScreen

UI command -> ModuleCatalogController -> public ModuleSupervisor API -> fresh snapshot
```

The layers are deliberately separable:

- `ModulePresentationMetadata` supplies a Cyclone name, description, provider, and stable/beta/
  experimental label. It contains no runtime hooks.
- `ModuleCatalogPresenter` converts typed descriptors, permissions, capability IDs, compatibility,
  restart requirements, health, updates, and diagnostics into stable plain-language view state.
- `ModuleCatalogController` is a narrow delegation adapter. It owns no mutable lifecycle state.
- `CycloneModulesScreen` is a stateless Compose surface under `ui/modules`. It is not wired into
  navigation on this branch.

Built-in modules sort first, followed by a locale-independent display-name and module-ID order.
Metadata input order does not affect output. Missing or throwing metadata sources fall back to the
local supervisor inventory, so the Catalog remains useful without a network.

## Zero-authority boundary

The Catalog cannot call `TrustedModuleRuntime`, construct supervisor state, edit module descriptors,
or activate code. It exposes only these delegated management operations:

- enable/disable -> `ModuleSupervisor.enable` / `disable`;
- update metadata preflight -> `ModuleSupervisor.prepareUpdate`;
- quarantine clearing -> `ModuleSupervisor.clearQuarantine`;
- rollback -> `ModuleSupervisor.rollback`.

There is intentionally no Catalog installation endpoint because the current trusted Supervisor has
no installation contract. Adding a local install button would create a lifecycle bypass. Future
installation may appear only after the Supervisor owns an explicit trusted/signed installation API;
the Catalog must delegate to it.

The Catalog cannot disable critical built-ins or a dependency that an active module needs. Those
decisions remain enforced by the Supervisor even if a caller invokes the Catalog controller
directly.

## What users see

Each card can show:

- Cyclone name and description;
- version and provider;
- Built in, Required, Beta, or Experimental labels;
- current health/lifecycle state;
- provided capabilities and requested permissions;
- supported Cyclone API range;
- restart requirement and update state;
- retry timing, quarantine state, and plain-language diagnostics.

Diagnostics translate internal codes into explanations such as “Required module is missing,”
“Capability conflict,” and “Automatic retries exhausted.” User-facing code does not mention donor
project runtimes, package managers, patch files, or foreign product branding.

## Future signed metadata

`SignedCatalogMetadata` models a future signed descriptive record using a module ID, descriptor
SHA-256, signing key ID, signature, and verification result. It deliberately has no download URL,
executable payload, install callback, or activation method. It is not connected to the initial
Catalog.

A future remote index must be verified by an integration-owned trust service before its metadata is
shown. Listing metadata must never imply that arbitrary Kotlin/Dex, native libraries, APKs, or
scripts can be loaded outside Android's normal trusted application update path.

## Integration proposal for Agent 15

If the feature is useful in the integrated build, place “Cyclone Modules” under Settings rather
than adding a primary navigation destination. Integration should:

1. assemble trusted declarations and one `ModuleSupervisor` in shared composition code;
2. provide curated `ModulePresentationMetadata` for the declarations included in the APK;
3. create a `ModuleCatalogController` using that same supervisor instance;
4. collect `controller.state()` into screen state and show command rejections as a normal message;
5. pass only controller-backed callbacks into `CycloneModulesScreen`;
6. keep update activation and recovery in their owning services.

Do not give the Compose screen a module runtime, installer, filesystem, network client, or second
inventory store.

## Provenance and verification

The MIT-licensed `dsh-market/dsh-market` repository was inspected for presentation patterns such as
clear module identity, health, compatibility, restart messaging, offline behavior, and readable
diagnostics. This is a clean-room Kotlin/Compose implementation; no source or product branding was
copied or vendored.

Focused JVM tests cover empty/offline state, stable ordering, built-in and experimental labels,
typed details, critical/dependency protections, delegated update preflight, readable retry and
failure diagnostics, metadata failure fallback, and the absence of a Catalog install endpoint.
Compose/Android compilation still requires the repository's Android build environment.
