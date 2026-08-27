# VMOS API and protocol map

## Connection and discovery

| Plane | Endpoint shape | Evidence | Cyclone mapping |
| --- | --- | --- | --- |
| Host health | `GET http://{host_ip}:18182/v1/heartbeat`, `/v1/systeminfo`, `/v1/net_info` | skills `vmos-edge-container-api/references/connection-and-host.md` | provider health/capabilities; keep local/LAN allow-list |
| Container inventory | `POST /container_api/v1/get_db`, `GET /list_names`, `GET /get_android_detail/{db_id}` | `references/instances.md` | `VirtualDeviceProvider.list_instances/status` |
| Container lifecycle | `POST /create`, `/run`, `/stop`, `/reboot`, `/reset`, `/delete`, `/clone`; poll `/rom_status/{db_id}` and `/clone_status` | `references/instances.md` | typed lifecycle adapter; verify terminal state |
| Guest control via host | `http://{host_ip}:18182/android_api/v2/{db_id}/{path}` | `connection-and-discovery.md` | DeviceBackend adapter after endpoint authorization |
| Direct guest control | `http://{cloud_ip}:18185/api/{path}` | `connection-and-discovery.md` | not required for Cyclone PC loopback; only explicit approved LAN |

## Android Control API families

Capability discovery starts with `GET /base/version_info`, then `POST /base/list_action`. Common families include:

- observation: `/screenshot/format`, `/screenshot/raw`, `/screenshot/data_url`, `/accessibility/dump_compact`, `/accessibility/node`;
- input: `POST /input/click`, `/input/multi_click`, `/input/text`, `/input/keyevent`, `/input/swipe`, `/input/scroll_bezier`;
- activity/package: `/activity/start`, `/activity/launch_app`, `/activity/start_activity`, `/activity/stop`, `/activity/top_activity`, `/package/install_sync`, `/package/install_uri_sync`, `/package/uninstall`, `GET /package/list`;
- system: clipboard, sleep, bounded shell (must be denied to Cyclone AI), timezone/country/language and device info.

The documented generic response is `{code,data,msg}`. API support is capability-driven, not inferred from version names.

## File/app and fleet operations

Host Android APIs provide `app_get/{db_id}`, batch app start/stop, batch APK/file upload (`multipart/form-data`) and URL-based distribution. `db_ids` is often a comma-separated string. These operations require explicit selected targets and per-device result verification in Cyclone.

## Workflow/CLI protocols

- CLI output is JSON `{ok:true,data}` or `{ok:false,error,code}`; 47 actions are exposed by schema. Direct, batch (`action` dotted names with saved variables) and YAML playbook modes are separate.
- Workflow runtime submits `POST workflow/execute`, polls `workflow/execution_get`, returns per-step status, and compiles execution logs into a blind child script. Static lint and a quality score gate (target ≥90) precede execution.
- Desktop renderer/main communication is typed Electron IPC (`src/shared/ipc/channels.ts`, `workflow.types.ts`, `agent.types.ts`); persistence and scheduling stay in main process.

## Media protocol evidence

The Qt MIT client's `DeviceParams` declares ADB reverse (forward fallback), scrcpy server path, a per-connection `scid`, local ports beginning at 27183, and optional direct TCP video/audio/control ports 9999/9998/9997. The Edge Desktop resource tree includes scrcpy and MediaMTX. Cyclone should keep its existing pinned scrcpy media plane and loopback gateway; no new unauthenticated media listener is justified by this evidence.

## Security constraints

VMOS docs describe LAN/cloud IP modes; they do not grant Cyclone permission to expose ADB or shell publicly. Cyclone must bind loopback by default, require bearer/session trust, validate target IDs, redact secrets, and expose only typed `phone.*`/fleet tools.
