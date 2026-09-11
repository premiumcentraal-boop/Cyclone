# Cyclone 4.3.4 task glass correction

Candidate 4.3.4 / 95. Published base v4.3.3 9774d539. Incorporates the full frontend 3c423473 with backend capability checks, retaining Profiles and grounded harness changes.

Checkpoint 1: remove legacy top trace HUD entirely. AgentTraceStore remains available for diagnostics; no independent trace WindowManager/controller survives. Working, Action needed, Done and the human-control ribbon come from the shared task state. Publication disabled until integration CI. Physical UI acceptance UNVERIFIED.
