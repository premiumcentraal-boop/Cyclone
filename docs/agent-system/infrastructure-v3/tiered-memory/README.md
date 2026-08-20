# Cyclone V3 Tiered Memory

Tiered Memory is the first local `MemoryStoreProvider` behind the frozen
`CycloneMemoryService` seam. It does not replace existing Brain databases or expose a producer
write API. AI, learners, imports and automations must continue to write only through the service.

This is a Cyclone-native implementation of the sprint's tiered-memory architecture. No external
runtime or source code was copied, and no cloud service is required.

## Deterministic tiers

Tier is derived from the frozen memory class; callers cannot pick a cheaper or less constrained
store independently:

| Memory class | Tier | Purpose |
|---|---|---|
| `RUNTIME_HINT` | Mission Hot Memory | Tiny current mission facts, observations and user constraints. |
| `DOCUMENT_REFERENCE` | Knowledge Documents | Teaching reports, explanations and postmortems retrieved explicitly. |
| `STRUCTURAL_KNOWLEDGE` | Structural/Durable | References and compact projections of operational knowledge. |

Each tier has independent per-scope record, total-content-byte and single-record-byte budgets.
Document and structural tiers reject over-budget mutations. Mission Hot Memory deterministically
keeps the highest-priority bounded set and evicts lower-priority records; if the proposed record
itself would be evicted, the mutation fails instead of reporting a false success.

## Retrieval and precedence

Normal `recall` requests include `RUNTIME_HINT` in the frozen request defaults. The provider treats
that as mission recall and returns hot memory only. Knowledge Documents and Structural/Durable
records require a separate request that explicitly excludes runtime hints and names those classes.
Diagnostic `query` can still list all requested tiers.

Read-time staleness uses the record's provenance observation time and a class-specific freshness
window. Explicitly stale and dynamically aged-out records are surfaced as `STALE`; the stored
record is not silently rewritten. Ordering is deterministic:

1. current non-stale user instructions;
2. fresh observed/verified runtime facts;
3. other verified or observed knowledge;
4. unverified knowledge;
5. stale history;
6. observation time, update time, confidence and record ID tie-breakers.

The provider applies that order before the requested limit, so stale history cannot displace a
fresh observation in the service-visible bounded result set.

### Integration ordering delta

The frozen `DefaultCycloneMemoryService` currently re-sorts provider results by update time,
confidence and ID. This can change order *within* the fresh-first set selected by the provider.
Agent 15 should preserve provider order or add verification/provenance-aware ordering to the
service. This branch does not modify the frozen Agent 6 contract. Until integration resolves it,
the provider's pre-limit selection preserves the safety property that stale records cannot eject
fresh records, but consumers should not infer that the first returned item always reflects the
provider's complete precedence order.

## Local provider

`LocalTieredMemoryProvider` stores one versioned binary snapshot below its supplied local root and
writes it through staging plus atomic replacement when the filesystem supports it. The snapshot
contains only records already validated and redacted by `CycloneMemoryService`. Startup validates
the complete snapshot; corruption enters an explicit fail-closed state and new mutations do not
overwrite the damaged evidence.

The provider preserves the frozen mutation contract:

- one `apply(AuthorizedMemoryMutation)` boundary;
- atomic `scope + recordId` uniqueness;
- atomic scope-local content-fingerprint deduplication, including two-service races;
- optimistic replace/remove/archive versions;
- explicit applied, stale, duplicate, missing and provider-failure results.

Knowledge Documents also receive a local human-readable `knowledge-documents.md` mirror. Sensitive
records remain in the policy-filtered binary store but their fields are omitted from the mirror.
The binary snapshot remains authoritative; a mirror failure is visible in provider diagnostics.

## App Graph boundary

App Graph remains authoritative for graph structure. Structural records whose provenance or
authority identifies App Graph must contain:

- `authority = app_graph`;
- an opaque `reference`;
- an optional compact projection type/summary.

Raw graphs, node/edge JSON, snapshots and database dumps are rejected. The
`AppGraphMemoryReference` helper creates the minimal allowed projection. Integration should adapt
real Graph V2 identities into these references rather than copying graph tables into memory.

## Integration

Agent 15 should instantiate one private local provider, pass it to `DefaultCycloneMemoryService`,
and expose only `CycloneMemoryService` to producers. Existing Brain remains a compatibility source
until explicit adapters and migrations are verified. The provider root should be app-private local
storage and included in backup/recovery policy deliberately.

The focused JVM suite combines all 12 frozen Memory Protocol tests with Tiered Memory tests for
deterministic selection, hard eviction/budgets, explicit-only document/structural recall, stale and
current precedence, service-visible limits, scope isolation, fingerprint races, optimistic
versions, restart durability, corruption handling, human-readable documents and App Graph
reference-only storage. These tests do not claim physical Android verification.
