import type {
  ConnectionDiagnosticBundle,
  ConnectorActionResult,
  ConnectorCard,
  ControlResult,
  DesktopDevice,
  DesktopRuntimeStatus,
  DesktopService,
  DeviceControlAction,
  DeviceSessionDescriptor,
  DeviceSessionList,
  DeviceSessionResult,
  FleetWsEvent,
  PairBeginResult,
  PairConfirmResult,
  PairQrConfirmResult,
  StreamDiagnosticEvent,
  StreamProfile,
} from "./types.js";
import { DEFAULT_FOREGROUND_SESSION_ID, isDefaultForegroundSession, readExactSessionSnapshotHeaders } from "../core/sessionTiles.js";

const MOCK_CODE = "NOVA";

export class MockDesktopService implements DesktopService {
  readonly mode = "mock" as const;
  private devices: DesktopDevice[];
  private pairings = new Map<string, PairBeginResult>();
  private pairingSequence = 0;
  private sessionSequence = 0;
  private sessions = new Map<string, DeviceSessionDescriptor[]>();
  private fleetListeners: Array<(event?: FleetWsEvent) => void> = [];

  constructor(deviceCount = 4) {
    this.devices = createMockDevices(deviceCount);
    for (const device of this.devices) {
      if (!device.paired) continue;
      this.sessions.set(device.id, seedMockSessions(device.id));
    }
  }

  async listDevices(): Promise<DesktopDevice[]> { return this.devices.map(copyDevice); }
  async scanDevices(): Promise<DesktopDevice[]> { return this.listDevices(); }
  watchFleet(onChange: (event?: FleetWsEvent) => void): () => void {
    this.fleetListeners.push(onChange);
    return () => {
      this.fleetListeners = this.fleetListeners.filter((listener) => listener !== onChange);
    };
  }

  emitFleetEvent(event: FleetWsEvent): void {
    for (const listener of this.fleetListeners) listener(event);
  }

  async pairBegin(deviceId: string): Promise<PairBeginResult> {
    const device = this.requireDevice(deviceId);
    device.state = "PAIRING";
    const pairingId = `mock-${deviceId}-${++this.pairingSequence}`;
    const result = {
      pairingId,
      expiresAtEpochMs: Date.now() + 60_000,
      qrAvailable: true,
      qrPayload: `cyclone://pair?challenge=${encodeURIComponent(pairingId)}&nonce=mock-nonce-abcdefghijklmnop`,
      preflight: {
        appRunning: true,
        accessibilityEnabled: true,
        accessibilityServiceConfigured: true,
      },
    };
    this.pairings.set(deviceId, result);
    return result;
  }

  async pairConfirm(deviceId: string, pairingId: string, code: string): Promise<PairConfirmResult> {
    const pairing = this.pairings.get(deviceId);
    if (!pairing || pairing.pairingId !== pairingId) return { ok: false, reason: "STALE_CODE" };
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
    if (!device.paired) return { ok: false, deviceId };
    if (device.state === "DISCONNECTED" && action.type !== "reconnect") return { ok: false, deviceId };
    if (action.type === "wake") { device.state = "READY"; device.connectionLabel = "Ready"; }
    if (action.type === "disconnect") { device.state = "DISCONNECTED"; device.connectionLabel = "Reconnecting"; }
    else if (action.type === "reconnect") {
      device.state = "READY";
      device.connectionLabel = "Ready";
      device.connectionHealth = healthyConnectionHealth();
    }
    else if (action.type === "clipboard_sync") device.capabilities.clipboardSync = action.enabled;
    else if (action.type === "yield_ai") {
      if (device.state === "SLEEPING") return { ok: false, deviceId, verification: "PHONE_LOCKED" };
      device.inputOwner = "AI";
      return { ok: true, deviceId, verification: "AI_HAS_CONTROL", inputOwner: "AI" };
    }
    else if (action.type === "take_human") {
      device.inputOwner = "HUMAN";
      return { ok: true, deviceId, verification: "HUMAN_HAS_CONTROL", inputOwner: "HUMAN" };
    }
    return { ok: true, deviceId, verification: `mock-${action.type}` };
  }

  async listDeviceSessions(deviceId: string): Promise<DeviceSessionList> {
    this.requireDevice(deviceId);
    return { protocol: "cyclone-one-session/1", deviceId, sessions: this.deviceSessions(deviceId).map(copySession) };
  }

  async startDeviceSession(deviceId: string, packageName: string): Promise<DeviceSessionResult> {
    this.requireDevice(deviceId);
    const session: DeviceSessionDescriptor = {
      sessionId: `workspace-${++this.sessionSequence}`,
      displayId: 2 + this.sessionSequence,
      targetPackage: packageName,
      backend: "Android workspace",
      inputOwner: "AI",
      state: "RUNNING",
      executable: true,
      frameHealthy: true,
    };
    this.deviceSessions(deviceId).push(session);
    this.emitFleetEvent({ event: "session.added", deviceId, sessionId: session.sessionId, displayId: session.displayId });
    return { protocol: "cyclone-one-session/1", deviceId, session: copySession(session) };
  }

  async pauseDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.mutateSession(deviceId, sessionId, (session) => { session.state = "PAUSED"; });
  }

  async resumeDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.mutateSession(deviceId, sessionId, (session) => { session.state = "RUNNING"; });
  }

  async handoffDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    return this.mutateSession(deviceId, sessionId, (session) => { session.inputOwner = "HUMAN"; });
  }

  async stopDeviceSession(deviceId: string, sessionId: string): Promise<DeviceSessionResult> {
    const result = this.mutateSession(deviceId, sessionId, (session) => { session.state = "STOPPED"; });
    this.sessions.set(deviceId, this.deviceSessions(deviceId).filter((session) => session.sessionId !== sessionId));
    this.emitFleetEvent({ event: "session.removed", deviceId, sessionId, displayId: result.session.displayId });
    return result;
  }

  async snapshotDeviceSession(deviceId: string, sessionId: string): Promise<{ url: string; displayId: number }> {
    const session = this.deviceSessions(deviceId).find((item) => item.sessionId === sessionId);
    if (!session) throw new Error("Mock session not found");
    if (isDefaultForegroundSession(session.sessionId) || session.displayId == null || session.displayId <= 0) {
      throw new Error("Cyclone refused an unproven background preview");
    }
    const headers = {
      get(name: string): string | null {
        if (name === "X-Cyclone-Foreground-Substitution") return "false";
        if (name === "X-Cyclone-Display-Id") return String(session.displayId);
        return null;
      },
    };
    const { displayId } = readExactSessionSnapshotHeaders(headers);
    return { url: mockFrameDataUrl(deviceId, "focus"), displayId };
  }

  addMockSession(deviceId: string, session: DeviceSessionDescriptor): DeviceSessionDescriptor {
    this.requireDevice(deviceId);
    const next = copySession(session);
    this.deviceSessions(deviceId).push(next);
    this.emitFleetEvent({ event: "session.added", deviceId, sessionId: next.sessionId, displayId: next.displayId });
    return copySession(next);
  }

  removeMockSession(deviceId: string, sessionId: string): void {
    const existing = this.deviceSessions(deviceId).find((item) => item.sessionId === sessionId);
    this.sessions.set(deviceId, this.deviceSessions(deviceId).filter((item) => item.sessionId !== sessionId));
    this.emitFleetEvent({ event: "session.removed", deviceId, sessionId, displayId: existing?.displayId });
  }

  getVideoUrl(deviceId: string, profile: StreamProfile): string { return `mock://video/${encodeURIComponent(deviceId)}?profile=${profile}`; }
  getVideoProtocols(): string[] { return []; }
  getFallbackFrameUrl(deviceId: string, profile: StreamProfile): string { return mockFrameDataUrl(deviceId, profile); }
  async reportStreamDiagnostic(_deviceId: string, _event: StreamDiagnosticEvent): Promise<void> {}
  async createConnectionDiagnosticBundle(deviceId: string): Promise<ConnectionDiagnosticBundle> {
    return { ok: true, deviceId, path: "mock-connection-diagnostics.zip", createdAtEpochMs: Date.now() };
  }

  async listConnectors(): Promise<ConnectorCard[]> {
    return [
      {
        id: "codex", name: "Codex", description: "Use Cyclone phones from Codex.", state: "READY_TO_CONNECT", actionLabel: "Connect",
        detected: true, configured: false, gatewayState: "READY", gatewayReachable: true,
        readyDeviceCount: 3, deviceCount: 4, toolCount: 14, transport: "stdio", approvalMode: "writes",
      },
      { id: "deepseek-mcp", name: "DeepSeek / MCP harness", description: "Connect an MCP-capable reasoning harness.", state: "CONNECTED" },
      { id: "generic-mcp", name: "Generic MCP", description: "Use a compatible MCP client.", state: "NOT_INSTALLED", actionLabel: "Set up" },
    ];
  }
  async runConnectorAction(_connectorId: string, _action: "connect" | "install" | "repair"): Promise<ConnectorActionResult> {
    return {
      ok: true,
      changed: true,
      restartRequired: true,
      message: "Codex is connected to Cyclone with 3 ready phones. Restart Codex once, then use the Cyclone phone tools.",
      readyDeviceCount: 3,
      toolCount: 14,
    };
  }

  async pairQrConfirm(deviceId: string, pairingId: string): Promise<PairQrConfirmResult> {
    const pairing = this.pairings.get(deviceId);
    if (!pairing || pairing.pairingId !== pairingId) return { ok: false, pending: false, reason: "STALE_CODE" };
    return { ok: false, pending: true };
  }
  async getRuntimeStatus(): Promise<DesktopRuntimeStatus> {
    return {
      backendReachable: true,
      deviceCount: this.devices.length,
      pairedDeviceCount: this.devices.filter((device) => device.paired).length,
      recoveryActive: this.devices.some((device) => device.state === "DISCONNECTED"),
      message: "Mock development backend",
      discovery: {
        adbPath: "mock-adb",
        adbAvailable: true,
        rawAdbDeviceCount: this.devices.length,
        authorizedAdbDeviceCount: this.devices.length,
        fleetDeviceCount: this.devices.length,
        reconnectingDeviceCount: this.devices.filter((device) => device.state === "DISCONNECTED").length,
        attentionDeviceCount: this.devices.filter((device) => device.state === "ATTENTION").length,
        maxReconnectAttempts: 5,
        reconnectBackoffSeconds: [1, 2, 4, 8, 15],
        trackerActive: true,
        lastScanSource: "mock",
      },
    };
  }
  private requireDevice(deviceId: string): DesktopDevice {
    const device = this.devices.find((candidate) => candidate.id === deviceId);
    if (!device) throw new Error("Mock device not found");
    return device;
  }

  private deviceSessions(deviceId: string): DeviceSessionDescriptor[] {
    const existing = this.sessions.get(deviceId);
    if (existing) return existing;
    const created: DeviceSessionDescriptor[] = [];
    this.sessions.set(deviceId, created);
    return created;
  }

  private mutateSession(deviceId: string, sessionId: string, mutate: (session: DeviceSessionDescriptor) => void): DeviceSessionResult {
    this.requireDevice(deviceId);
    if (isDefaultForegroundSession(sessionId)) throw new Error("Foreground session lifecycle stays on the human display");
    const session = this.deviceSessions(deviceId).find((item) => item.sessionId === sessionId);
    if (!session) throw new Error("Mock session not found");
    mutate(session);
    return { protocol: "cyclone-one-session/1", deviceId, session: copySession(session) };
  }
}

export function createMockDevices(count: number): DesktopDevice[] {
  const states: DesktopDevice["state"][] = ["READY", "READY", "SLEEPING", "UNPAIRED", "READY", "DISCONNECTED"];
  return Array.from({ length: Math.max(0, count) }, (_, index) => {
    const state = states[index % states.length];
    const paired = state !== "UNPAIRED" && state !== "PAIRING";
    return {
      id: `phone-${index + 1}`, name: index === 0 ? "My phone" : `Phone ${index + 1}`, model: `Cyclone device ${index + 1}`,
      state, paired,
      connectionLabel: state === "READY" ? "Ready" : state === "SLEEPING" ? "Sleeping" : state === "UNPAIRED" ? "Not paired" : "Reconnecting",
      lastSeenEpochMs: Date.now() - index * 1000,
      video: { mode: "SCREENSHOT", width: 1080, height: 2400, rotationDegrees: 0 },
      capabilities: { keyboard: paired, clipboard: paired && index % 4 !== 3, clipboardSync: false, reconnect: true },
      connectionHealth: state === "DISCONNECTED"
        ? {
            bridgeReachable: false,
            lastHeartbeatEpochMs: Date.now() - 40_000,
            reconnectAttempts: 2,
            maxReconnectAttempts: 5,
            nextRetryEpochMs: Date.now() + 4_000,
            lastError: "Simulated USB bridge drop",
            errorClass: "BridgeDisconnectedError",
          }
        : state === "ATTENTION"
          ? {
              bridgeReachable: false,
              lastHeartbeatEpochMs: Date.now() - 120_000,
              reconnectAttempts: 5,
              maxReconnectAttempts: 5,
              nextRetryEpochMs: null,
              lastError: "Bridge retries exhausted",
              errorClass: "BridgeDisconnectedError",
            }
          : healthyConnectionHealth(),
      lastFrameUrl: mockFrameDataUrl(`phone-${index + 1}`, "thumbnail"),
      inputOwner: "AI",
    };
  });
}

function healthyConnectionHealth() {
  return {
    bridgeReachable: true,
    lastHeartbeatEpochMs: Date.now(),
    reconnectAttempts: 0,
    maxReconnectAttempts: 5,
    nextRetryEpochMs: null,
    lastError: null,
    errorClass: null,
  };
}

function copyDevice(device: DesktopDevice): DesktopDevice { return { ...device, video: { ...device.video }, capabilities: { ...device.capabilities } }; }

function copySession(session: DeviceSessionDescriptor): DeviceSessionDescriptor { return { ...session }; }

function seedMockSessions(deviceId: string): DeviceSessionDescriptor[] {
  return [
    {
      sessionId: DEFAULT_FOREGROUND_SESSION_ID,
      displayId: 0,
      backend: "default-foreground",
      inputOwner: "HUMAN",
      state: "FOREGROUND",
      executable: true,
      frameHealthy: true,
    },
    {
      sessionId: `workspace-${deviceId}`,
      displayId: 2,
      targetPackage: "com.android.chrome",
      backend: "Android workspace",
      inputOwner: "AI",
      state: "RUNNING",
      executable: true,
      frameHealthy: true,
    },
  ];
}

function mockFrameDataUrl(deviceId: string, profile: StreamProfile): string {
  const seed = Number(deviceId.replace(/\D/g, "")) || 1;
  const hueA = 248 + (seed * 11) % 35;
  const hueB = 270 + (seed * 7) % 45;
  const label = profile === "focus" ? "Cyclone live phone" : "Cyclone";
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="540" height="1200" viewBox="0 0 540 1200"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="hsl(${hueA} 42% 16%)"/><stop offset="1" stop-color="hsl(${hueB} 52% 8%)"/></linearGradient></defs><rect width="540" height="1200" fill="url(#g)"/><rect x="28" y="70" width="484" height="120" rx="28" fill="rgba(255,255,255,.08)"/><rect x="28" y="218" width="228" height="228" rx="36" fill="rgba(255,255,255,.07)"/><rect x="284" y="218" width="228" height="228" rx="36" fill="rgba(255,255,255,.05)"/><rect x="28" y="474" width="484" height="190" rx="36" fill="rgba(255,255,255,.06)"/><rect x="28" y="692" width="484" height="320" rx="36" fill="rgba(255,255,255,.045)"/><circle cx="54" cy="1136" r="22" fill="#8b5cf6"/><text x="88" y="1146" fill="white" opacity=".82" font-family="system-ui" font-size="30">${label}</text></svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}
