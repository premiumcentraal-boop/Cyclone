import type { DesktopDevice } from "../services/types.js";
export interface LivePhoneStatus { connected: boolean; vision: boolean; control: boolean; enabled: boolean; stopped: boolean; }
export function livePhoneLabels(state: LivePhoneStatus, devices: DesktopDevice[]) {
 const physical = devices.filter(d => d.source === "USB" || d.source === "LAN");
 return {
 phone: physical.length ? physical.map(d => `${d.name} · ${d.connectionLabel}`).join(" / ") : "Connect and pair a physical Android phone",
 cloud: state.connected ? "PC connector · Recent request" : "PC connector · Waiting for a request",
 vision: state.vision ? "Vision · Ready" : "Vision · Observe the phone first",
 control: state.stopped ? "Control · Stopped" : !state.enabled ? "Control · Paused" : state.control ? "Control · Ready" : "Control · Enabled; waiting for a verified phone response",
 };
}
