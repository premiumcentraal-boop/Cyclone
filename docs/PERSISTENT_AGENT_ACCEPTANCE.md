# Persistent-agent vertical-slice acceptance

This is the release gate for the first persistent-agent vertical slice:

```text
real agent -> private environment -> explicit shared resource ->
private browser grant -> durable handoff snapshot -> reviewer decision -> restart reconcile
```

Use real Cyclone agents and a real conversation.  Do not seed showcase agents,
messages, groups, resources, browser profiles, or handoffs.  Run this against
the local Compose stack after the migration that introduces the environment and
resource tables has been applied.

## Preconditions

1. Start the actual stack from the repository root:

   ```powershell
   docker compose -f docker/docker-compose.yml --env-file .env up -d --build
   Invoke-RestMethod http://127.0.0.1:8787/health
   ```

2. Use the existing real `Research`, `Developer`, and `Reviewer` agents.  Get
   their IDs from Core; do not copy IDs from a test or documentation example:

   ```powershell
   $agents = Invoke-RestMethod http://127.0.0.1:8787/api/v1/agents
   $research = $agents | Where-Object slug -eq 'research'
   $developer = $agents | Where-Object slug -eq 'developer'
   $reviewer = $agents | Where-Object slug -eq 'reviewer'
   if (!$research -or !$developer -or !$reviewer) { throw 'Create the real team first.' }
   ```

3. Create a genuine group conversation through the desktop UI with those three
   agents.  Copy its conversation ID from the Core conversations response.  It
   is the one conversation used for all evidence below.

## 1. Provision each real private environment

Core provisions a private environment for every existing real agent at startup
and for every newly created or duplicated agent. The one-shot Compose
initializer owns the volume permissions, so Core remains unprivileged while
each layout remains private. Restart Core once after applying migration 008,
then verify the durable inventory below. This is a real provision into the
named `agent_environments_data` Docker volume, not a test fixture.

Verify the three durable inventory rows and that no layout points to `/workspace`
or a host path:

```powershell
docker compose -f docker/docker-compose.yml --env-file .env exec -T postgres psql -U $env:POSTGRES_USER -d $env:POSTGRES_DB -c "SELECT a.slug, e.template_key, e.relative_root_path, e.lifecycle_state, e.health_state FROM agent_environments e JOIN agents a ON a.id=e.agent_id ORDER BY a.slug;"
```

Expected: one `agents/<32 lowercase hex>` root per agent, `ready` and
`healthy`.  Inspect the named volume only from the Core container.  Each root
contains exactly `workspace`, `browser-profile`, and `state`; no shared folder
or another agent's files may appear there.

## 2. Create and grant one real shared resource

Have `Research` produce a real brief in its private workspace.  Register the
brief as a resource and its SHA-256 as version 1 through the resource-fabric
repository/API once that adapter lands.  Grant `Developer` **only** `handoff`
access to that resource; do not grant an entire workspace or use a host path.

The persistence/API adapter is not published at this point, so its live
acceptance is blocked until it exists.  The required persisted records are:

- one `resources` row owned by Research with an opaque
  `workspace://research/<brief>` canonical URI;
- one immutable `resource_versions` row with the actual brief checksum;
- one active `resource_grants` row for Developer with `access = handoff`.

Before calling the future adapter, the enforceable public semantics are covered
by `tests/test_persistent_agent_foundations.py` and
`tests/test_resource_fabric.py`; they reject a reviewer reading the live brief
without an explicit access grant.

## 3. Verify real Camofox browser-grant behavior

Do not give an agent Camofox `userId`, cookie, proxy, profile, or arbitrary
browser controls.  Core must issue a persisted `BrowserAccessGrant` linked to
the real Research agent, group conversation, registered web resource, exact
allowed origin, expiry, and audit event.  The current Camofox adapter is
intentionally not registered as an MCP tool until that persistence adapter is
available.

When the adapter is wired to the real Camofox service, verify all of the
following with the actual grant record and Core audit log:

1. Opening `https://<granted-origin>/...` uses
   `cyclone-agent-<research UUID without hyphens>` and a conversation-bounded
   session key.
2. Opening a different origin is rejected by Core before Camofox receives a
   request.
3. Another agent receives a different profile ID.
4. Redirecting outside the granted origin closes the tab and records a policy
   failure.
5. The expiry is enforced and no cookie/profile value is returned to chat,
   Hermes, logs, or the API response.

Run `python -m pytest tests/test_camofox_client.py tests/test_persistent_agent_foundations.py`
from `apps/cyclone-core` before attempting this live exercise.  Those checks
cover the currently published grant semantics without pretending that an
unwired Camofox container is live.

## 4. Make a durable handoff snapshot and obtain reviewer acceptance

1. Have Research use the actual group conversation to hand the work to
   Developer, citing the registered resource version.  The durable handoff
   must have a source task, receiver task, summary, and acceptance criteria.
2. Developer creates a recipient-scoped snapshot of that exact version for
   Reviewer.  The snapshot is immutable and must remain readable after the
   live grant changes; Reviewer must not receive the Research workspace itself.
3. Reviewer inspects the snapshot and sends a real evidence-bearing review
   result through the dedicated Core review endpoint, not a chat-only
   statement or a generic task-status patch:

   ```powershell
   Invoke-RestMethod -Method Post -ContentType 'application/json' -Uri "http://127.0.0.1:8787/api/v1/tasks/<developer-task-id>/review" -Body '{"reviewer_agent_id":"<real-reviewer-id>","reviewed_run_id":"<actual-hermes-run-id>","decision":"accepted","evidence_summary":"Reviewer inspected the immutable snapshot and ran the stated checks.","evidence":{"checks":"passed"}}'
   ```

   If evidence is insufficient, set `decision` to `changes_requested`. Record the
   resulting task status, `handoffs` row, `resource_handoff_snapshots` row,
   and review message in the release evidence.

4. Verify that the normal chat timeline contains a natural handoff/review
   event and contains no raw `@HANDOFF`, queue envelope, run ID, grant token,
   Camofox profile ID, cookie, or local host path.

## 5. Restart and reconcile recovery

1. Record the real resource version, snapshot ID, task status, and a checksum
   of the private brief.
2. Restart only Core:

   ```powershell
   docker compose -f docker/docker-compose.yml --env-file .env restart cyclone-core
   docker compose -f docker/docker-compose.yml --env-file .env ps cyclone-core
   Invoke-RestMethod http://127.0.0.1:8787/health
   ```

3. Verify the `agent_environments` rows return to `ready`/`healthy`, private
   workspace files retain their checksum, and missing recoverable layout
   directories are recreated without touching workspace content.  Verify the
   resource version and handoff snapshot IDs still refer to the same immutable
   records, then re-open the conversation and task from Core.
4. Confirm an expired browser grant remains expired after restart.  It may not
   be silently renewed or mapped to another agent's Camofox profile.

## Pass criteria

Pass only when all six links are backed by real Cyclone state and the recorded
IDs/checksums survive the Core restart.  A passing unit test or an attractive UI
alone is not sufficient.  If any HTTP/MCP persistence adapter is not yet
implemented, mark the corresponding live step **blocked** and retain the pure
service test evidence; do not replace it with demo data or a simulated agent.
