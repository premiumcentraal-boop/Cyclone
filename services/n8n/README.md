# Cyclone n8n integration

n8n is the deterministic automation engine, not the agent brain. It communicates with Cyclone through exactly one Core endpoint:

```text
POST /api/v1/internal/automation/events
X-Cyclone-Internal-Key: <CYCLONE_INTERNAL_API_KEY>
```

## Example routine workflows

Two importable variants ship in `workflows/`:

- `cyclone-routine-event.json` — Manual Trigger → POST to Cyclone Core. For operators who run the workflow from the n8n editor UI.
- `cyclone-routine-event-webhook.json` — Webhook trigger (`POST /webhook/cyclone-routine`) → the same Core call. This variant can be verified headlessly: activate it, then trigger it with curl.

Both include a stable workflow `id` (required by n8n 2.x `import:workflow`) and use a unique `external_event_id` based on `$execution.id`.

### Import and verify against a running stack

```bash
# 1. Import (no owner account needed for CLI imports)
docker exec cyclone-n8n-1 n8n import:workflow --input=/cyclone-n8n/workflows/cyclone-routine-event.json
docker exec cyclone-n8n-1 n8n import:workflow --input=/cyclone-n8n/workflows/cyclone-routine-event-webhook.json

# 2. Activate the webhook variant (n8n registers active webhooks at boot)
docker exec cyclone-postgres-1 psql -U cyclone -d n8n \
  -c "UPDATE workflow_entity SET active = true WHERE id = '7c1a0d44-9b2e-4f3c-8d6a-2b8e7f6a5c4d';"
docker compose -f docker/docker-compose.yml --env-file .env restart n8n

# 3. Trigger the routine
curl -X POST http://127.0.0.1:5678/webhook/cyclone-routine -H 'Content-Type: application/json' -d '{}'

# 4. Verify the automation message landed in Cyclone's Welcome conversation
curl -s http://127.0.0.1:8787/api/v1/conversations | python -m json.tool
```

Alternative editor path: open `http://127.0.0.1:5678`, create the local n8n owner account, import the JSON, and execute it manually.

Notes:
- `n8n execute --id …` cannot run while the n8n server is up (the CLI instance tries to bind the task-broker port 5679 already held by the server).
- Cyclone Core makes event ingress idempotent by `external_event_id`, emits a conversation activity record, and writes an audit event. n8n cannot bypass Cyclone approval policy.

## Security

- n8n is bound to localhost in Compose.
- The integration key is an untracked local secret.
- The workflow reads credentials only from environment variables.
- Do not use arbitrary frontend webhooks as an integration path.
