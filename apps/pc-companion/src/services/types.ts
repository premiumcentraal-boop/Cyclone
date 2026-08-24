export type DeviceLifecycleState =
  | "READY"
  | "UNPAIRED"
  | "PAIRING"
  | "SLEEPING"
  | "DISCONNECTED"
  | "UNAUTHORIZED"
  | "ATTENTION";

export type StreamUiState =
  | "CONNECTING"
  | "LIVE"
  | "RECONNECTING"
  | "SLEEPING"
  | "STREAM_ERROR"
  | "UNAVAILABLE";

export type StreamProfile = "thumbnail" | "focus";
export type StreamBackendMode = "H264" | "MJPEG" | "SCREENSHOT";

export interface DeviceVideoDescriptor {
  mode: StreamBackendMode;
  width: number;
  height: number;
  rotationDegrees: 0 | 90 | 180 | 270;
  codec?: string;
}

export interface DeviceCapabilities {
  keyboard: boolean;
  clipboard: boolean;
  clipboardSync: boolean;
  reconnect: boolean;
}

export interface DesktopDevice {
  id: string;
  name: string;
  model?: string;
  state: DeviceLifecycleState;
  paired: boolean;
  connectionLabel: string;
  lastSeenEpochMs: number;
  video: DeviceVideoDescriptor;
  capabilities: DeviceCapabilities;
  lastFrameUrl?: string;
}

export interface PairBeginResult {
  pairingId: string;
  expiresAtEpochMs: number;
  qrPayload?: string | null;
  qrAvailable?: boolean;
  diagnosticsActive?: boolean;
  diagnosticsPath?: string | null;
  diagnosticsMode?: string | null;
}

export type PairConfirmResult =
  | { ok: true; device: DesktopDevice }
  | { ok: false; reason: "INVALID_CODE" | "EXPIRED" | "STALE_CODE" | "UNAVAILABLE"; message?: string };

export type PairQrConfirmResult =
  | { ok: true; device: DesktopDevice }
  | { ok: false; pending: boolean; reason?: "EXPIRED" | "STALE_CODE" | "UNAVAILABLE"; message?: string };

export type DeviceControlAction =
  | { type: "tap"; x: number; y: number }
  | { type: "key"; key: "BACK" | "HOME" | "ENTER" | "BACKSPACE" | "TAB" }
  | { type: "scroll"; direction: "UP" | "DOWN" }
  | { type: "text"; text: string }
  | { type: "clipboard_sync"; enabled: boolean }
  | { type: "clipboard_paste"; text: string }
  | { type: "disconnect" }
  | { type: "reconnect" };

export interface ControlResult {
  ok: boolean;
  deviceId: string;
  verification?: string;
}

export type ConnectorState =
  | "CONNECTED"
  | "READY_TO_CONNECT"
  | "NOT_INSTALLED"
  | "NEEDS_ATTENTION";

export interface ConnectorCard {
  id: "codex" | "deepseek-mcp" | "generic-mcp" | string;
  name: string;
  description: string;
  state: ConnectorState;
  actionLabel?: string;
  detected?: boolean;
  configured?: boolean;
  configPath?: string;
  gatewayState?: "READY" | "NO_READY_PHONE" | "OFFLINE" | string;
  gatewayReachable?: boolean;
  readyDeviceCount?: number;
  deviceCount?: number;
  toolCount?: number;
  transport?: string;
  approvalMode?: string;
}

export interface ConnectorActionResult {
  ok: boolean;
  changed?: boolean;
  restartRequired?: boolean;
  message: string;
  path?: string;
  readyDeviceCount?: number;
  toolCount?: number;
}

export interface DesktopDiscoveryStatus {
  adbPath?: string;
  adbAvailable: boolean;
  rawAdbDeviceCount: number;
  authorizedAdbDeviceCount: number;
  fleetDeviceCount: number;
  trackerActive: boolean;
  trackerRestarts?: number;
  fallbackIntervalSeconds?: number;
  lastScanAtEpochMs?: number | null;
  lastScanDurationMs?: number | null;
  lastScanSource?: string;
  lastScanError?: string | null;
}

export interface DeviceLiveDiagnosticsStatus {
  active: boolean;
  sessionPath: string;
  timelinePath?: string;
  latestSnapshotPath?: string | null;
  lastStage?: string;
  appPid?: string | null;
  startedAtEpochMs?: number | null;
  rootRequired?: boolean;
  mode?: string;
}

export interface DesktopLiveDiagnosticsStatus {
  active: boolean;
  activeDeviceCount: number;
  deviceCount: number;
  latestSessionPath?: string | null;
  rootRequired?: boolean;
  mode?: string;
  devices?: Record<string, DeviceLiveDiagnosticsStatus>;
}

export interface DesktopRuntimeStatus {
  backendReachable: boolean;
  runtimeInstanceId?: string;
  runtimePort?: number;
  deviceCount: number;
  pairedDeviceCount: number;
  recoveryActive: boolean;
  message?: string;
  discovery?: DesktopDiscoveryStatus;
  liveDiagnostics?: DesktopLiveDiagnosticsStatus;
}

export interface DesktopService {
  readonly mode: "real" | "mock";
  listDevices(): Promise<DesktopDevice[]>;
  scanDevices(): Promise<DesktopDevice[]>;
  watchFleet(onChange: () => void): () => void;
  pairBegin(deviceId: string): Promise<PairBeginResult>;
  pairConfirm(deviceId: string, pairingId: string, code: string): Promise<PairConfirmResult>;
  pairQrConfirm(deviceId: string, pairingId: string): Promise<PairQrConfirmResult>;
  sendControl(deviceId: string, action: DeviceControlAction): Promise<ControlResult>;
  getVideoUrl(deviceId: string, profile: StreamProfile): string;
  getVideoProtocols(): string[];
  getFallbackFrameUrl(deviceId: string, profile: StreamProfile): string;
  listConnectors(): Promise<ConnectorCard[]>;
  runConnectorAction(connectorId: string, action: "connect" | "install" | "repair"): Promise<ConnectorActionResult>;
  getRuntimeStatus(): Promise<DesktopRuntimeStatus>;
}
