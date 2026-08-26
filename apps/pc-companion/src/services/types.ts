export type DeviceLifecycleState =
  | "READY"
  | "UNPAIRED"
  | "PAIRING"
  | "SLEEPING"
  | "DISCONNECTED"
  | "UNAUTHORIZED"
  | "ATTENTION";

export type DiscoveryState = "ABSENT" | "UNAUTHORIZED" | "OFFLINE" | "ADB_READY";
export type MediaPlaneState =
  | "STOPPED"
  | "STARTING"
  | "WAITING_KEYFRAME"
  | "LIVE"
  | "SLEEPING"
  | "RECONNECTING"
  | "UNAVAILABLE";
export type BridgePlaneState =
  | "APP_MISSING"
  | "APP_STOPPED"
  | "SOCKET_STARTING"
  | "CONNECTED"
  | "AUTH_FAILED"
  | "DEGRADED";
export type AITrustState = "UNPAIRED" | "CONFIRMATION_REQUIRED" | "TRUSTED" | "REVOKED" | "EXPIRED";

export interface DevicePlanes {
  discovery: DiscoveryState;
  media: MediaPlaneState;
  bridge: BridgePlaneState;
  aiTrust: AITrustState;
}

export type ReadinessCardState = "READY" | "ACTION_REQUIRED" | "OFFLINE" | "IDLE" | "RECOVERING" | "SLEEPING" | "LIMITED";

export interface ReadinessCard {
  state: ReadinessCardState;
  ready: boolean;
  message: string;
}

export interface DeviceReadiness {
  phoneConnection: ReadinessCard;
  liveDisplay: ReadinessCard;
  aiCodexAccess: ReadinessCard;
}

export type StreamUiState =
  | "CONNECTING"
  | "LIVE"
  | "RECONNECTING"
  | "SLEEPING"
  | "STREAM_ERROR"
  | "UNAVAILABLE";

export type StreamProfile = "thumbnail" | "focus";
export type StreamBackendMode = "H264" | "MJPEG" | "SCREENSHOT";

export interface StreamDiagnosticEvent {
  stage: string;
  code?: string;
  attempt?: number;
  closeCode?: number;
  retryable?: boolean;
}

export interface ConnectionDiagnosticBundle {
  ok: boolean;
  deviceId: string;
  path: string;
  createdAtEpochMs: number;
}

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

export interface DeviceConnectionHealth {
  bridgeReachable: boolean | null;
  lastHeartbeatEpochMs: number | null;
  reconnectAttempts: number;
  maxReconnectAttempts?: number;
  nextRetryEpochMs: number | null;
  lastError: string | null;
  errorClass: string | null;
}

export interface PairingPreflight {
  appRunning: boolean | null;
  accessibilityEnabled: boolean | null;
  accessibilityServiceConfigured: boolean | null;
}

export interface DesktopDevice {
  id: string;
  name: string;
  model?: string;
  state: DeviceLifecycleState;
  paired: boolean;
  connectionLabel: string;
  lastSafeError?: string | null;
  lastSeenEpochMs: number;
  video: DeviceVideoDescriptor;
  capabilities: DeviceCapabilities;
  connectionHealth?: DeviceConnectionHealth;
  planes?: DevicePlanes;
  readiness?: DeviceReadiness;
  videoDiagnostics?: {
    subscriberCount: number;
    activeProfiles: string[];
    lastEvent: string;
    lastFrameAvailable: boolean;
  };
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
  preflight?: PairingPreflight | null;
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
  | { type: "wake" }
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
  reconnectingDeviceCount?: number;
  attentionDeviceCount?: number;
  maxReconnectAttempts?: number;
  reconnectBackoffSeconds?: number[];
  bridgeErrors?: Record<string, {
    error?: string | null;
    errorClass?: string | null;
    attempts?: number;
    nextRetryEpochMs?: number | null;
  }>;
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
  sessionBinding?: string;
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
  reportStreamDiagnostic(deviceId: string, event: StreamDiagnosticEvent): Promise<void>;
  createConnectionDiagnosticBundle(deviceId: string): Promise<ConnectionDiagnosticBundle>;
  listConnectors(): Promise<ConnectorCard[]>;
  runConnectorAction(connectorId: string, action: "connect" | "install" | "repair"): Promise<ConnectorActionResult>;
  getRuntimeStatus(): Promise<DesktopRuntimeStatus>;
}
