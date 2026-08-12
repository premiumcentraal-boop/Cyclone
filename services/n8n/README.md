# Cyclone n8n integration

n8n is the deterministic automation engine, not the agent brain. It communicates with Cyclone through exactly one Core endpoint:

```text
POST /api/v1/internal/automation/events
X-Cyclone-Internal-Key: <CYCLONE_INTERNAL_API_KEY>
```

## Example routine workflow

`workflows/cyclone-routine-event.json` is importable into the local n8n instance. It uses a Manual Trigger then calls Cyclone Core with a unique `external_event_id` based on `$execution.id`.

### Import and test after the stack is running

1. Open `http://127.0.0.1:5678` and create the local n8n owner account.
2. Import the JSON file.
3. Execute it manually.
4. Open Cyclone’s Welcome conversation through Core and verify an `automation` message appears.

Cyclone Core makes event ingress idempotent by `external_event_id`, emits a conversation activity record, and writes an audit event. n8n cannot bypass Cyclone approval policy.

## Security

- n8n is bound to localhost in Compose.
- The integration key is an untracked local secret.
- The workflow reads credentials only from environment variables.
- Do not use arbitrary frontend webhooks as an integration path.
