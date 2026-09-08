# Cyclone 4.2.1 — current tasks and new requests stay separate

VersionCode 82. Based on published v4.2.0, source 011de009ff6871be64a3df1e63308a3ac027e282.

Both the overlay and in-app Ask have distinct Current task, Up next and New request areas. Send never doubles as Stop. Existing task controls retain exact execution identity and ViewProgressRouter. Type and save new phone tasks without replacing or cancelling the running task.

Up next holds up to eight requests with their own attachments. It refuses overflow without consuming the new attachment. Requests are in-memory for this app session, visibly removable, and started explicitly through existing app selection when the current phone task has been closed. Cancelling selection leaves the request intact. This is not parallel phone execution or an automatic queue runner. New starts recheck the current phone task and GATE.

In-app Chat answers without phone observation, Accessibility setup or screen-sharing permission. Phone task is a separate explicit composer choice. Conversation can run alongside a phone task; Stop reply only cancels the provider reply. The existing model registry, provider compatibility/privacy contract and API-key store are reused. Provider-native reasoning parameters remain at compatible defaults; chat depth follows the chosen intelligence preference through response guidance. Attachments and conversation stay in process memory.

Opening the keyboard compacts current-task details and pending summaries. The global 800ms database refresh loop is replaced by task/store revisions, screen changes and app resume. Follow Me retains its localized progress observation. Existing background setup, execution planes, PhoneToolExecutor, GATE and overlay drag/touch/teardown remain.

Checkpoint source tests cover pending-request preservation, capacity without attachment loss, chat without phone-action capability, separate Send/Stop controls and guarded starts. Publication uses exact-source green Mobile CI, the historical update-compatible development signer and all checksum/provenance sidecars.

NO DIRECT PHONE / PIXEL / USB / ADB TESTING WAS PERFORMED. Physical Pixel 8 and UI acceptance remain UNVERIFIED. Device checks still needed: keyboard/large fonts, overlay drag/touch, multiple queued requests, attachments, model replies, GATE and exact-task handoff.
