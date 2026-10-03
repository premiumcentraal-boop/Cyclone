# Number providers — grounded build and acceptance

Baseline: Alpha 102 / Glass Alpha 57, source `079085ce`. This builds on the existing Numbers inventory, Ports routing, Command Center owner approvals and encrypted delivery to the paired phone. It does not create a second phone engine.

| Provider | Account connection | Catalogue / quote | Safe first purchase | SMS |
| --- | --- | --- | --- | --- |
| VMOS Cloud Numbers | Owner's access key and secret key; current V2 signature | Live countries, area codes, plans; USD cents | One number, `clientToken`, `expectedTotalCents`, explicit `autoRenew=0`; reconcile with purchase/status | Exact owned number, fresh message, GMT+8 timestamps |
| SMSBot.cc | Owner's bearer API key | Live countries and templates, their included services and period prices; EUR | One template number, `quantity=1`, `expectedPrice`. No purchase retries: this endpoint explicitly lacks idempotency | Exact rental ID, fresh message; never query the whole account inbox |

Primary contracts: [VMOS API](https://cloud.vmoscloud.com/vmoscloud/doc/en/server/OpenAPI.html), [VMOS V2 signing](https://cloud.vmoscloud.com/vmoscloud/doc/en/server/example.html), [SMSBot API](https://smsbot.cc/en/api-docs). Reviewed 2026-10-03. Documentation examples are not live prices or stock promises. SMSBot's expandable Request/Response sections are part of the contract.

## Checkpoints

1. Provider adapters and safety tests. Fixed HTTPS hosts; bounded responses and timeouts; no redirects, credential logging or automatic mutation retry. VMOS uses SHA256(SK + timestamp + path + exact body), not the existing cloud-phone HMAC signer. Parse HTTP-200 business errors as errors. Money uses integer cents, not float comparisons.
2. Durable quote/order service and native Ports starters. Credentials encrypted with Windows DPAPI, separate from the inventory. Owner consent pins the provider, country, plan/template and every included service, period, amount, currency and quantity. Quotes expire; approval cannot authorize a changed price. Save the submission state before the network call. Restart/timeout cannot buy twice. SMSBot uncertain purchases stay uncertain until the owner identifies the actual rental; VMOS polls the original token. No automatic renew, refund, release or device bind/restart.
3. Glass Numbers provider cards, catalogue, quote, owner approval and status. Existing approval UI remains the purchase authority; the agent-facing tools can request approval and read the result, never answer approval. Developer can disable agent use or restrict apps/routines. Keys are write-only from Glass. Track expiry and one number per account.
4. Agent skill guidance and private code delivery. A targeted `value.in` request can ask for a number; waits remain bound to the run/request. The owner approves the exact quote. Before clicking Send SMS, `value.in` with `ask=prepare-code` records a baseline of message IDs and arms a five-minute window. Then `code.in` polls only that rental and new messages, with an explicit sender filter. This covers fast SMS arriving before the next model turn; ambiguous or stale codes fail closed. Code enters the existing memory-only Ports hold and is sealed to a trusted phone. No inbox, message text or code in inventory, SQLite, Glass history, diagnostics or model-visible values. Stop, pause and permission changes withdraw delivery.
5. Acceptance: adapter fixtures, denied/expired/repriced approvals, duplicate request and restart, uncertain purchase, credential redaction, cross-country number identity, expiry/assignment, signature/replay checks, scoped and cancelled waits, private code path, Glass interactions and existing regression suites. Checkpoint commits after backend and UI/acceptance.

## Product limits

SMSBot's ordinary `POST /rentals` does not document an exact-price guard; this build uses the documented template price guard instead. A template can include several services; all are shown before approval. Templates cannot be partially refunded. Unknown SMSBot outcomes are never retried or inferred from similar numbers. No provider promises that a particular website will accept a rental, or that a temporary number remains available for account recovery. Keep recovery details independent and extend deliberately in the provider dashboard.

No provider credentials or spending approval were supplied for this development task. Automated acceptance uses fake transports and isolated data. Live account authentication, paid allocation and a physical account-creation mission require owner credentials and a separate exact-price approval; do not label those verified without evidence.
