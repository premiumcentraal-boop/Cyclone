/** Renders the fleet snapshot. No planning and no device selection live here. */
import { el } from "../ui/dom.js";
import type { FleetSnapshot } from "../services/fleet.js";

export function renderFleetBoard(snapshot: FleetSnapshot): HTMLElement {
  const board = el("section", "card fleet-board");
  board.append(el("h2", "card-title", "Fleet"));
  const counts = snapshot.counts;
  if (counts) {
    board.append(el(
      "p",
      "muted",
      `${counts.devices} devices · ${counts.online} online · ${counts.running} running · ${counts.waitingForOwner} waiting for you`,
    ));
  }
  const list = el("ul", "fleet-device-list");
  for (const device of snapshot.devices) {
    const item = el("li", "fleet-device");
    const mission = device.currentMission;
    item.append(el("strong", undefined, device.name));
    item.append(el("span", "muted", device.online ? device.trust : "Offline"));
    if (mission?.objective) {
      item.append(el("span", undefined, `${mission.status ?? "Running"}: ${mission.objective}`));
    }
    list.append(item);
  }
  board.append(list);
  return board;
}
