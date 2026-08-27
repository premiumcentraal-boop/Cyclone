# VMOS-to-Cyclone feature matrix

| VMOS feature | Product | Evidence | Category | Cyclone equivalent today | Gap / 3.5 action | ROI | Difficulty | License risk | Owner |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Device inventory + reconnect cache | Edge Desktop | `DeviceDao`, `DeviceStateCache`, status poller | CLEAN-ROOM | PC gateway discovery/state | durable identity, reconnect backoff, independent diagnostics | P0 | M | low | Agent 2 |
| Device wall/groups/multi-select | Cast/Edge Desktop | `GroupManager`, `GroupControlManager`, DeviceRow/HostGroup | ADAPT | gateway APIs, PC Companion views | live thumbnails, explicit groups, per-device outcomes | P0 | M | low | Agent 2 |
| scrcpy media + ADB reverse/forward | Edge/Qt client | QtScrcpyCore `AdbProcess`, `DeviceManage`, resource scrcpy | DIRECT-REUSE compatible upstream / CLEAN-ROOM wiring | pinned scrcpy 4.0 media plane | conformance/reconnect and thumbnail throttling | P0 | M | Apache notice | Agent 2 |
| Host/container lifecycle | Edge skills | `:18182` instances refs | CLEAN-ROOM adapter | new provider contracts | create/start/stop/reset/delete with truthful unavailable state | P0 | M | VMOS API docs only | Agent 2 |
| Clone/snapshot-like workflows | Container API | `/clone`, `/clone_status`, image operations | CLEAN-ROOM | provider seam | implement only when backend proves persistence; experimental otherwise | P1 | H | API/proprietary | Agent 2 |
| Capability discovery | Control API | `/base/version_info`, `/base/list_action` | ADAPT | gateway capabilities | preflight tool support before action | P0 | S | low | Agents 2/3 |
| Accessibility compact dump + semantic input | Control API | `/accessibility/dump_compact`, input refs | ADAPT | semantic observe + PhoneToolExecutor | preserve observe→act→verify and observation-scoped IDs | P0 | M | low | Agent 3 |
| Evidence-first workflow recorder | workflow skill | recording-guide, dumps/diffs, walk rules | CLEAN-ROOM | Teach/Follow Me | compile execution log to routine, selector quality gate and repair | P0 | M | MIT docs/package | Agent 3 |
| Bounded retries/timeouts/convergence | workflow SDK | README defaults: obs 3, action 2, timeout 12s, max 120 | CLEAN-ROOM/ADAPT | AI runtime policies | bounded per-tool retries, pause/resume, repeated-call detection | P0 | M | MIT package (adapter) | Agent 3 |
| JSON CLI batch/YAML flows | Edge CLI/FlowSmith | CLI README, command-patterns, syntax/lint | CLEAN-ROOM | MCP + routines | typed batch actions with reports; no arbitrary shell | P1 | M | CLI license blank | Agent 3 |
| WebCodecs/WebGL channels | web SDK | package README | CLEAN-ROOM | PC WebSocket/WebCodecs | retain existing Cyclone media; use channel readiness states | P1 | M | ISC declared; code not reused | Agent 2 |
| Sensor/GPS/locale injection | SDK/Container API | sensor package README; device-control refs | ADAPT | limited provider capabilities | explicit policy-gated typed features, never silent mutation | P2 | M | ISC / API-specific | Agents 2/3 |
| Proxy chain | proxy SDK | package README, bundled platform binaries | DO-NOT-COPY | none | do not ship VMOS proxy binaries; separate future licensed integration | reject | H | bundled binary/license | Agent 3 |
| Guest virtual HAL/isolation | VMOS Pro AOSP | README architecture + restricted notice | CLEAN-ROOM | provider abstraction only | use ReDroid/AVD/Cuttlefish adapters; no VMOS guest code | P1 | H | noncommercial blocker | Agent 2 |
