import type {
  ConnectorCard,
  ControlResult,
  DesktopDevice,
  DesktopRuntimeStatus,
  DesktopService,
  DeviceControlAction,
  PairBeginResult,
  PairConfirmResult,
  StreamProfile,
} from "./types.js";

const MOCK_CODE = "NOVA";

export class MockDesktopService implements DesktopService {
  readonly mode = "mock" as const;
  private devices: DesktopDevice[];
  private pairings = new Map<string, PairBeginResult>();

  constructor(deviceCount = 4) {
    this.devices = createMockDevices(deviceCount);
  }

  async listDevices(): Promise<DesktopDevice[]> {
    return this.devices.map(copyDevice);
  }

  async pairBegin(deviceId: string): Promise<PairBeginResult> {
    const device = this.requireDevice(deviceId);
    device.state = "PAIRING";
    const result = {
      pairingId: `mock-${deviceId}-${Date.now()}`,
      expiresAtEpochMs: Date.now() + 60_000,
    };
    this.pairings.set(deviceId, result);
    return result;
  }

  async pairConfirm(deviceId: string, pairingId: string, code: string): Promise<PairConfirmResult> {
    const pairing = this.pairings.get(deviceId);
    if (!pairing || pairing.pairingId !== pairingId) return { ok: false, reason: "UNAVAILABLE" };
    if (Date.now() >= pairing.expiresAtEpochMs) return { ok: false, reason: "EXPIRED" };
    if (code.toUpperCase() !== MOCK_CODE) return { ok: false, reason: "INVALID_CODE" };
    const device = this.requireDevice(deviceId);
    device.paired = true;
    device.state = "READY";
    device.connectionLabel = "Ready";
    this.pairings.delete(deviceId);
    return { ok: true, device: copyDevice(device) };
  }

  async sendControl(deviceId: string, action: DeviceControlAction): Promise<ControlResult> {
    const device = this.requireDevice(deviceId);
    if (!device.paired || device.state === "DISCONNECTED") return { ok: false, deviceId };
    if (action.type === "disconnect") {
      device.state = "DISCONNECTED";
      device.connectionLabel = "Reconnecting";
    } else if (action.type === "reconnect") {
      device.state = "READY";
      device.connectionLabel = "Ready";
    } else if (action.type === "clipboard_sync") {
      device.capabilities.clipboardSync = action.enabled;
    }
    return { ok: true, deviceId, verification: `mock-${action.type}` };
  }

  getVideoUrl(deviceId: string, profile: StreamProfile): string {
    return `mock://video/${encodeURIComponent(deviceId)}?profile=${profile}`;
  }

  getFallbackFrameUrl(deviceId: string, profile: StreamProfile): string {
    return mockFrameDataUrl(deviceId, profile);
  }

  async listConnectors(): Promise<ConnectorCard[]> {
    return [
      { id: "codex", name: "Codex", description: "Use Cyclone phones from Codex.", state: "READY_TO_CONNECT", actionLabel: "Connect" },
      { id: "deepseek-mcp", name: "DeepSeek / MCP harness", description: "Connect an MCP-capable reasoning harness.", state: "CONNECTED" },
      { id: "generic-mcp", name: "Generic MCP", description: "Use a compatible MCP client.", state: "NOT_INSTALLED", actionLabel: "Set up" },
    ];
  }

  async runConnectorAction(_connectorId: string, _action: "connect" | "install" | "repair"): Promise<void> {
    return;
  }

  async getRuntimeStatus(): Promise<DesktopRuntimeStatus> {
    return {
      backendReachable: true,
      deviceCount: this.devices.length,
      pairedDeviceCount: this.devices.filter((device) => device.paired).length,
      recoveryActive: this.devices.some((device) => device.state === "DISCONNECTED"),
      message: "Mock development backend",
    };
  }

  private requireDevice(deviceId: string): DesktopDevice {
    const device = this.devices.find((candidate) => candidate.id === deviceId);
    if (!device) throw new Error("Mock device not found");
    return device;
  }
}

export function createMockDevices(count: number): DesktopDevice[] {
  const states: DesktopDevice["state"][] = ["READY", "READY", "SLEEPING", "UNPAIRED", "READY", "DISCONNECTED"];
  return Array.from({ length: Math.max(0, count) }, (_, index) => {
    const state = states[index % states.length];
    const paired = state !== "UNPAIRED" && state !== "PAIRING";
    return {
      id: `phone-${index + 1}`,
      name: index === 0 ? "My phone" : `Phone ${index + 1}`,
      model: `Cyclone device ${index + 1}`,
      state,
      paired,
      connectionLabel: state === "READY" ? "Ready" : state === "SLEEPING" ? "Sleeping" : state === "UNPAIRED" ? "Not paired" : "Reconnecting",
      lastSeenEpochMs: Date.now() - index * 1000,
      video: {
        mode: "SCREENSHOT",
        width: 1080,
        height: 2400,
        rotationDegrees: 0,
      },
      capabilities: {
        keyboard: paired,
        clipboard: paired && index % 4 !== 3,
        clipboardSync: false,
        reconnect: true,
      },
      lastFrameUrl: mockFrameDataUrl(`phone-${index + 1}`, "thumbnail"),
    };
  });
}

function copyDevice(device: DesktopDevice): DesktopDevice {
  return {
    ...device,
    video: { ...device.video },
    capabilities: { ...device.capabilities },
  };
}

function mockFrameDataUrl(deviceId: string, profile: StreamProfile): string {
  const seed = Number(deviceId.replace(/\D/g, "")) || 1;
  const hueA = 248 + (seed * 11) % 35;
  const hueB = 270 + (seed * 7) % 45;
  const label = profile === "focus" ? "Cyclone live phone" : "Cyclone";
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="540" height="1200" viewBox="0 0 540 1200">
    <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="hsl(${hueA} 42% 16%)"/><stop offset="1" stop-color="hsl(${hueB} 52% 8%)"/></linearGradient></defs>
    <rect width="540" height="1200" fill="url(#g)"/>
    <rect x="28" y="70" width="484" height="120" rx="28" fill="rgba(255,255,255,.08)"/>
    <rect x="28" y="218" width="228" height="228" rx="36" fill="rgba(255,255,255,.07)"/>
    <rect x="284" y="218" width="228" height="228" rx="36" fill="rgba(255,255,255,.05)"/>
    <rect x="28" y="474" width="484" height="190" rx="36" fill="rgba(255,255,255,.06)"/>
    <rect x="28" y="692" width="484" height="320" rx="36" fill="rgba(255,255,255,.045)"/>
    <circle cx="54" cy="1136" r="22" fill="#8b5cf6"/>
    <text x="88" y="1146" fill="white" opacity=".82" font-family="system-ui" font-size="30">${label}</text>
  </svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}
