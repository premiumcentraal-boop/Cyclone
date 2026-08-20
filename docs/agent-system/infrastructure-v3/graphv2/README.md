# Cyclone App Graph V2

Graph V2 is an additive, typed, temporal knowledge layer beside the current App Graph. It is a
clean-room Cyclone implementation; no external source code was copied. This branch does not change
the legacy SQLite schema, Page Awareness semantics, action execution, AI providers or UI.

## Typed graph

The graph has explicit nodes for App, Activity, Page, Element, Selector, Transition, Routine and
Capability, and explicit relations for:

```text
CONTAINS         NAVIGATES_TO     OPENS             SUBMITS
REQUIRES         SCROLL_REVEALS   SELECTOR_MATCHES  RECOVERED_BY
USED_BY_ROUTINE  SUPERSEDES
```

`GraphV2Schema` rejects structurally invalid node/relation combinations. Nodes and current edges
are returned in stable order. Traversals use visited sets and deterministic neighbor ordering so
cycles cannot cause unbounded or order-dependent results.

## Temporal evidence

The store is append-only per `GraphEdgeKey`. A newer observation does not overwrite prior evidence.
Every edge observation records:

- evidence kind, ID and producer;
- confidence and observation time;
- last success/failure times and counts;
- app version evidence;
- verification state and scope;
- current, suspect, stale or superseded status.

`history(key)` returns every observation. `currentEdges()` selects the newest deterministic view and
excludes stale relationships by default. App-version queries can surface relationships learned on
an older version for re-observation after an update.

## Evidence promotion safety

Knowledge is evidence, never authority. The store enforces these rules rather than relying on every
caller to remember them:

- model inference cannot create a structural edge by itself;
- it may only annotate/corroborate an edge which already has deterministic evidence;
- model inference can never promote an edge to verified;
- verified evidence requires a recorded success and explicit verification scope;
- `PHYSICAL_DEVICE` verification requires a source that explicitly proves physical-device evidence;
- CI fixtures cannot declare physical-device evidence;
- a duplicate evidence ID is idempotent only when the entire observation is identical.

These rules do not authorize a phone action. Existing policy and `PhoneToolExecutor` remain the
only paths for deciding and performing mutations.

## Queries

`GraphV2Queries` provides bounded deterministic queries for:

- pages that can reach a target page;
- current selectors valid on a page;
- routines depending on a selector/capability/page;
- relationships whose app-version evidence differs after an app update;
- transitive blast radius for page/element/selector/routine dependencies.

Fresh observations should be recorded as new evidence. They outrank older observations by time;
old records remain inspectable for debugging and regression analysis.

## Non-destructive migration

`LegacyAppGraphV2Adapter.project(AppGraphSnapshot)` is a read-only projection. It never opens or
changes the legacy database. It maps current apps/screens/actions/transitions into V2 nodes and
edges, hashes selector JSON instead of persisting raw selector contents, and retains existing
confidence, timing, counts and app-version evidence where available.

Legacy `VERIFIED` values are deliberately imported as `OBSERVED` with no verification scope because
the legacy snapshot alone cannot prove whether that status came from CI, local runtime activity or
a physical acceptance run. Integration may promote evidence only after recording a new qualifying
V2 observation.

Suggested integration sequence:

1. read a normal immutable `AppGraphSnapshot` from the existing store;
2. create a V2 projection;
3. import into a new V2 persistence adapter in one transaction;
4. compare counts/diagnostics without changing existing reads or execution;
5. dual-write new evidence only after integration tests establish parity;
6. never delete the legacy database as part of this migration.
