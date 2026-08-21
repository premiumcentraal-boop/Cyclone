export type DeviceLifecycleState =
  | "READY"
  | "UNPAIRED"
  | "PAIRING"
  | "SLEEPING"
  | "DISCONNECTED";

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
}

export type PairConfirmResult =
  | { ok: true; device: DesktopDevice }
  | { ok: false; reason: "INVALID_CODE" | "EXPIRED" | "UNAVAILABLE" };

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
}

export interface DesktopRuntimeStatus {
  backendReachable: boolean;
  deviceCount: number;
  pairedDeviceCount: number;
  recoveryActive: boolean;
  message?: string;
}

export interface DesktopService {
  readonly mode: "real" | "mock";
  listDevices(): Promise<DesktopDevice[]>;
  pairBegin(deviceId: string): Promise<PairBeginResult>;
  pairConfirm(deviceId: string, pairingId: string, code: string): Promise<PairConfirmResult>;
  sendControl(deviceId: string, action: DeviceControlAction): Promise<ControlResult>;
  getVideoUrl(deviceId: string, profile: StreamProfile): string;
  getFallbackFrameUrl(deviceId: string, profile: StreamProfile): string;
  listConnectors(): Promise<ConnectorCard[]>;
  runConnectorAction(connectorId: string, action: "connect" | "install" | "repair"): Promise<void>;
  getRuntimeStatus(): Promise<DesktopRuntimeStatus>;
}
