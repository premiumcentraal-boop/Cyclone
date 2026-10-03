# Native number providers

VMOS Cloud and SMSBot.cc are bundled starters inside the PC runtime, configured in **Command Center → Numbers → Number providers**. No extra executable or public webhook is required. The owner enters their own provider keys, selects whether agents may use the starter, and can restrict Android packages and routine IDs. The connection pins a manifest and runs the real SDK conformance suite. Ports still controls pause, consent and routing; targeted requests cannot override an explicit Port map choice.

Provider keys are write-only in Glass and encrypted for the current Windows user. They are never sent to the phone or a model. On other platforms they are kept in memory only. Provider HTTPS hosts are fixed; redirects and automatic purchase retries are refused.

Both starters serve `value.in` and `code.in`. Guidance is advertised to the paired phone only while enabled and approved for its app/routine. `plugin=vmos-numbers` or `plugin=smsbot-numbers` selects the provider explicitly.

1. `value.in`, `match={ask:"catalogue"}` returns available countries. Repeat with `country` to get a country's SMSBot templates. VMOS US selection also uses `areaCode`.
2. `value.in`, `match={ask:"rent",requestId,country,planId,areaCode?}` for VMOS, or `{ask:"rent",requestId,country,templateId,period}` for SMSBot. Optional `accountId` assigns an existing account. This opens an exact-price owner approval in **Needs you**; no plugin or agent tool can answer it. A request ID identifies one intended purchase and cannot be reused with different parameters.
3. Use a number only when `status=complete`. `unknown`, `processing` and `syncing` may already represent a paid purchase. Repeat the same request to read its outcome; never buy another as a retry. VMOS reconciles the original client token. SMSBot uncertain outcomes need the owner to identify the actual rental in their dashboard.
4. **Before** pressing Send SMS, `value.in`, `{ask:"prepare-code",requestId}`; require `status=prepared`. It records only existing message IDs and arms a five-minute window.
5. Request the SMS, then `code.in`, `{requestId,from:"the exact expected sender",length:6}`. Only a fresh, unambiguous, unused message on that run's rental is delivered. The code is held in memory, sealed to a trusted phone and filled there with `vault_fill what=one_time_code`. The model sees only its length. Message text, inbox contents and codes never enter Numbers, logs or saved history.

VMOS: V2 SHA256 signature, USD cents, one number, explicit auto-renew off and exact-price purchase guard. Binding/restarting cloud phones and automatic renewal/release are outside these starters.

SMSBot: EUR, one existing service template with `expectedPrice` and `quantity=1`. Ordinary rentals have no documented price guard; template purchases have no idempotency and cannot be partially refunded. The runtime never retries an ambiguous purchase, creates templates, changes the account's global webhook, tops up a balance, or rents a batch.

Temporary numbers may expire, be recycled later or be refused by an app. Use these only for accounts the owner may legitimately create and maintain an independent recovery method. Final account creation and terms still follow the phone's normal owner approval.

Contracts: [VMOS](https://cloud.vmoscloud.com/vmoscloud/doc/en/server/OpenAPI.html), [V2 authentication](https://cloud.vmoscloud.com/vmoscloud/doc/en/server/example.html), [SMSBot](https://smsbot.cc/en/api-docs).
