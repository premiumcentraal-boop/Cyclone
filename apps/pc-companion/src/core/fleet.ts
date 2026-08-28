import type { DesktopDevice } from "../services/types.js";

export type AppRoute = "fleet" | "focused" | "connections" | "settings";

export interface CompanionState {
  route: AppRoute;
  devices: DesktopDevice[];
  focusedDeviceId: string | null;
}

export type CompanionAction =
  | { type: "devices_updated"; devices: DesktopDevice[] }
  | { type: "focus_device"; deviceId: string }
  | { type: "back_to_fleet" }
  | { type: "navigate"; route: Exclude<AppRoute, "focused"> };

export function initialCompanionState(devices: DesktopDevice[] = []): CompanionState {
  return { route: "fleet", devices, focusedDeviceId: null };
}

export function reduceCompanionState(state: CompanionState, action: CompanionAction): CompanionState {
  switch (action.type) {
    case "devices_updated": {
      const focusedStillExists = state.focusedDeviceId == null || action.devices.some((d) => d.id === state.focusedDeviceId);
      return {
        ...state,
        devices: [...action.devices],
        ...(focusedStillExists ? {} : { route: "fleet" as const, focusedDeviceId: null }),
      };
    }
    case "focus_device":
      if (!state.devices.some((device) => device.id === action.deviceId)) return state;
      return { ...state, route: "focused", focusedDeviceId: action.deviceId };
    case "back_to_fleet":
      return { ...state, route: "fleet", focusedDeviceId: null };
    case "navigate":
      return { ...state, route: action.route, focusedDeviceId: null };
  }
}

export function canPreserveFocusedPage(state: CompanionState, devices: DesktopDevice[]): boolean {
  return state.route === "focused"
    && state.focusedDeviceId != null
    && devices.some((device) => device.id === state.focusedDeviceId);
}
