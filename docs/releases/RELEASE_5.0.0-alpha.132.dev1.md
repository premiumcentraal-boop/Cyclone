# Cyclone Alpha 132 — bootstrap cross-user mount visibility

Android version code: **286**, based on Alpha 131.

Bootstrap shells now use `su --mount-master -c` so cross-user file reads and writes use the global mount namespace instead of inheriting the long-lived owner's app namespace. Owner-supplied device evidence showed that the receiver wrote its public key promptly while the owner's bootstrap reads repeatedly returned “No such file or directory.” Increasing the poll budget alone did not address that visibility problem.

The sole runtime change is the bootstrap executor command prefix. Poll budgets, acknowledgement repair, bootstrap sequence, wire contract, bindings and permissions are unchanged. Gateway/MCP and Glass versions are unchanged.

The current mobile harness has no Robolectric or process-launch interception seam. A focused source guard asserts the exact bootstrap executor prefix without restructuring the flow or adding dependencies; it does not prove Android mount behavior. Physical-device verification of this artifact remains **UNVERIFIED**. No device work, reboot or data clearing was performed in this run. Update normally while retaining app data and retry the profile switch. No platform verdict is claimed.

The standard release pipeline validates the exact phone commit and established signing key/lineage before publishing the combined developer prerelease.
