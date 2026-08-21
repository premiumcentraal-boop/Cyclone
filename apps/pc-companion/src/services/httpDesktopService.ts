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

export interface HttpDesktopServiceOptions {
  httpBaseUrl?: string;
  wsBaseUrl?: string;
}

/**
 * The only real-backend HTTP adapter used by UI code.
 * Endpoint shapes are intentionally centralized so Agent 3 can align implementation without
 * changing Fleet/Focused/Pairing components.
 */
export class HttpDesktopService implements DesktopService {
  readonly mode = "real" as const;
  private readonly httpBase: string;
  private readonly wsBase: string;

  constructor(options: HttpDesktopServiceOptions = {}) {
    this.httpBase = stripSlash(options.httpBaseUrl ?? "http://127.0.0.1:8765");
    this.wsBase = stripSlash(options.wsBaseUrl ?? this.httpBase.replace(/^http/, "ws"));
  }

  listDevices(): Promise<DesktopDevice[]> {
    return this.request<{ devices: DesktopDevice[] }>("/v1/devices").then((value) => value.devices);
  }

  pairBegin(deviceId: string): Promise<PairBeginResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/pair/begin`, { method: "POST" });
  }

  pairConfirm(deviceId: string, pairingId: string, code: string): Promise<PairConfirmResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/pair/confirm`, {
      method: "POST",
      body: JSON.stringify({ pairing_id: pairingId, code }),
    });
  }

  sendControl(deviceId: string, action: DeviceControlAction): Promise<ControlResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/control`, {
      method: "POST",
      body: JSON.stringify({ action }),
    });
  }

  getVideoUrl(deviceId: string, profile: StreamProfile): string {
    return `${this.wsBase}/v1/devices/${encodeURIComponent(deviceId)}/video?profile=${profile}`;
  }

  getFallbackFrameUrl(deviceId: string, profile: StreamProfile): string {
    return `${this.httpBase}/v1/devices/${encodeURIComponent(deviceId)}/video/fallback?profile=${profile}`;
  }

  listConnectors(): Promise<ConnectorCard[]> {
    return this.request<{ connectors: ConnectorCard[] }>("/v1/connectors").then((value) => value.connectors);
  }

  runConnectorAction(connectorId: string, action: "connect" | "install" | "repair"): Promise<void> {
    return this.request(`/v1/connectors/${encodeURIComponent(connectorId)}/action`, {
      method: "POST",
      body: JSON.stringify({ action }),
    }).then(() => undefined);
  }

  getRuntimeStatus(): Promise<DesktopRuntimeStatus> {
    return this.request("/v1/diagnostics/status");
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await fetch(`${this.httpBase}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        ...(init.headers ?? {}),
      },
      cache: "no-store",
    });
    if (!response.ok) throw new Error(`Cyclone backend request failed (${response.status})`);
    return (await response.json()) as T;
  }
}

function stripSlash(value: string): string {
  return value.replace(/\/$/, "");
}
