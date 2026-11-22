# Cyclone documentation

The live documents describe Cyclone as it is today. Release notes and history are kept in their own folders.

## How Cyclone works

- [`ARCHITECTURE.md`](ARCHITECTURE.md): the system's shape and runtime boundaries.
- [`DEVELOPMENT.md`](DEVELOPMENT.md): local development and validation.
- [`OBSERVATION_CONTRACT.md`](OBSERVATION_CONTRACT.md) and [`SESSION_CONTRACT.md`](SESSION_CONTRACT.md): what the phone
  observes and how a session is identified.
- [`contracts/HUMAN_GESTURE_CONTROL_V1.md`](contracts/HUMAN_GESTURE_CONTROL_V1.md) and the `HUMAN_GESTURE_*` documents:
  human-like gestures, their calibration and diagnostics.

## Fleet and testing

- [`FLEET.md`](FLEET.md), [`FLEET_OPS.md`](FLEET_OPS.md), [`FLEET_PRODUCTION.md`](FLEET_PRODUCTION.md),
  [`FLEET_ACCEPTANCE.md`](FLEET_ACCEPTANCE.md) and [`FLEET-PHASES.md`](FLEET-PHASES.md): the multi-phone fleet.
- [`LAB_AGENT_BRIEF.md`](LAB_AGENT_BRIEF.md): the Cyclone Lab, for the testing agent.
- The device matrices: [`PROFILES_DEVICE_MATRIX.md`](PROFILES_DEVICE_MATRIX.md),
  [`HUMAN_HANDS_DEVICE_MATRIX.md`](HUMAN_HANDS_DEVICE_MATRIX.md), [`LUNA_DEVICE_MATRIX.md`](LUNA_DEVICE_MATRIX.md) and
  [`PROFILE_APP_INVENTORY_AUDIT.md`](PROFILE_APP_INVENTORY_AUDIT.md).
- [`ROOT_QUICK_SETUP.md`](ROOT_QUICK_SETUP.md): root setup on a test phone.

## Releases

- [`RELEASES.md`](RELEASES.md): versioning, artifacts and release expectations.
- [`releases/`](releases/): one `RELEASE_<version>.md` per release. The release lane (`v5-publish.yml`) publishes a
  release only when its notes are here.

## Design and reference

- [`design/`](design/) and [`ux/`](ux/): design studies and decisions.
- [`OPEN_SOURCE_COMPONENTS.md`](OPEN_SOURCE_COMPONENTS.md): third-party component notices.
- `evidence/` and `fixtures/`: data that tests and documents cite.

## History

[`archive/`](archive/) holds plans, sprint checkpoints, handoffs and operator notes for retired versions (3.x, 4.x,
V4, Cyclone One). They describe how Cyclone got here, not how it works now. The V5 plan's own history is in
`Cyclone V5 plan/archive/`.
