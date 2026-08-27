import { invoke } from "@tauri-apps/api/core";
import type {
  ConnectionDiagnosticBundle,
  ConnectorActionResult,
  ConnectorCard,
  ControlResult,
  DesktopDevice,
  DesktopRuntimeStatus,
  DesktopService,
  DeviceControlAction,
  PairBeginResult,
  PairConfirmResult,
  PairQrConfirmResult,
  StreamDiagnosticEvent,
  StreamProfile,
  TrustStatusResult,
  FleetBatchOperation,
  FleetBatchTask,
  FleetGroup,
  FleetWorkspace,
} from "./types.js";

export interface HttpDesktopServiceOptions {
  httpBaseUrl?: string;
  wsBaseUrl?: string;
  token: string;
}

type ConnectorStatusPayload = {
  codex?: string;
  deepseek_harness?: string;
  generic_mcp?: string;
  details?: {
    codex?: {
      state?: string;
      detected?: boolean;
      configured?: boolean;
      config_path?: string;
      server_ready?: boolean;
      approval_mode?: string;
    };
    gateway?: {
      state?: string;
      reachable?: boolean;
      ready_device_count?: number;
      device_count?: number;
    };
    mcp?: {
      server?: string;
      tool_count?: number;
      transport?: string;
    };
  };
};

type ConnectorActionPayload = {
  changed?: boolean;
  restart_required?: boolean;
  message?: string;
  path?: string;
  verification?: {
    ok?: boolean;
    tools?: string[];
    gateway?: {
      reachable?: boolean;
      ready_device_count?: number;
    };
  };
};

type RuntimeSelfTest = {
  ok: boolean;
  runtimeInstanceId: string;
  runtimePort: number;
  sessionBinding: string;
};

/** The single real-backend adapter used by the Cyclone PC Companion UI. */
export class HttpDesktopService implements DesktopService {
  readonly mode = "real" as const;
  private readonly httpBase: string;
  private readonly wsBase: string;
  private readonly token: string;

  constructor(options: HttpDesktopServiceOptions) {
    this.httpBase = stripSlash(options.httpBaseUrl ?? "http://127.0.0.1:8765");
    this.wsBase = stripSlash(options.wsBaseUrl ?? this.httpBase.replace(/^http/, "ws"));
    this.token = options.token;
  }

  async waitUntilReady(timeoutMs = 12_000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    let lastError: unknown = null;
    while (Date.now() < deadline) {
      try {
        const status = await this.getRuntimeStatus();
        if (status.backendReachable) {
          await this.verifySessionBinding(Math.min(3_000, Math.max(500, deadline - Date.now())));
          return;
        }
      } catch (error) {
        lastError = error;
      }
      await sleep(180);
    }
    if (lastError instanceof Error) throw lastError;
    throw new Error("Cyclone local Gateway did not become ready in time");
  }

  listDevices(): Promise<DesktopDevice[]> {
    return this.request<{ devices: DesktopDevice[] }>("/v1/fleet").then((value) => value.devices);
  }

  scanDevices(): Promise<DesktopDevice[]> {
    return this.request<{ devices: DesktopDevice[] }>("/v1/fleet/scan", { method: "POST" }).then((value) => value.devices);
  }

  getFleetWorkspace(): Promise<FleetWorkspace> {
    return this.request("/v1/fleet/workspace");
  }

  saveFleetGroup(groupId: string, name: string, deviceIds: string[]): Promise<FleetGroup> {
    return this.request(`/v1/fleet/groups/${encodeURIComponent(groupId)}`, {
      method: "POST", body: JSON.stringify({ name, device_ids: deviceIds }),
    });
  }

  async deleteFleetGroup(groupId: string): Promise<void> {
    await this.request(`/v1/fleet/groups/${encodeURIComponent(groupId)}/delete`, { method: "POST" });
  }

  setFleetSelection(deviceIds: string[]): Promise<string[]> {
    return this.request<{ selectedDeviceIds: string[] }>("/v1/fleet/selection", {
      method: "POST", body: JSON.stringify({ device_ids: deviceIds }),
    }).then((value) => value.selectedDeviceIds);
  }

  submitFleetBatch(deviceIds: string[], operation: FleetBatchOperation, params: Record<string, unknown> = {}): Promise<FleetBatchTask> {
    return this.request("/v1/fleet/batches", {
      method: "POST", body: JSON.stringify({ device_ids: deviceIds, operation, params }),
    });
  }

  getFleetBatch(batchId: string): Promise<FleetBatchTask> {
    return this.request(`/v1/fleet/batches/${encodeURIComponent(batchId)}`);
  }

  cancelFleetBatch(batchId: string): Promise<FleetBatchTask> {
    return this.request(`/v1/fleet/batches/${encodeURIComponent(batchId)}/cancel`, { method: "POST" });
  }

  watchFleet(onChange: () => void): () => void {
    let disposed = false;
    let authRejected = false;
    let socket: WebSocket | null = null;
    let reconnectTimer: number | null = null;

    const connect = () => {
      if (disposed || authRejected) return;
      try {
        socket = new WebSocket(`${this.wsBase}/v1/fleet/events`, this.getVideoProtocols());
      } catch {
        scheduleReconnect();
        return;
      }
      socket.addEventListener("message", () => {
        if (!disposed) onChange();
      });
      socket.addEventListener("error", () => {
        try { socket?.close(); } catch { /* noop */ }
      });
      socket.addEventListener("close", (event) => {
        if (event.code === 4401) {
          authRejected = true;
          return;
        }
        scheduleReconnect();
      });
    };

    const scheduleReconnect = () => {
      if (disposed || authRejected || reconnectTimer != null) return;
      reconnectTimer = window.setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, 2000);
    };

    connect();
    return () => {
      disposed = true;
      if (reconnectTimer != null) window.clearTimeout(reconnectTimer);
      reconnectTimer = null;
      try { socket?.close(); } catch { /* noop */ }
      socket = null;
    };
  }

  trustStatus(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust`);
  }

  trustBegin(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust/begin`, { method: "POST" });
  }

  trustComplete(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust/complete`, { method: "POST" });
  }

  trustRotate(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust/rotate`, { method: "POST" });
  }

  trustRevoke(deviceId: string): Promise<TrustStatusResult> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/trust/revoke`, { method: "POST" });
  }

  pairBegin(deviceId: string): Promise<PairBeginResult> {
    return this.request<Record<string, unknown>>(`/v1/devices/${encodeURIComponent(deviceId)}/pair/begin`, { method: "POST" })
      .then((value) => ({
        pairingId: String(value.pairingId ?? ""),
        expiresAtEpochMs: Number(value.expiresAtEpochMs ?? value.expiresAtMs ?? Date.now() + 60_000),
        qrPayload: typeof value.qrPayload === "string" ? value.qrPayload : null,
        qrAvailable: value.qrAvailable === true,
        diagnosticsActive: value.diagnosticsActive === true,
        diagnosticsPath: typeof value.diagnosticsPath === "string" ? value.diagnosticsPath : null,
        diagnosticsMode: typeof value.diagnosticsMode === "string" ? value.diagnosticsMode : null,
      }));
  }

  async pairQrConfirm(deviceId: string, pairingId: string): Promise<PairQrConfirmResult> {
    try {
      const value = await this.request<{ paired?: boolean; pending?: boolean; device?: DesktopDevice }>(
        `/v1/devices/${encodeURIComponent(deviceId)}/pair/qr/complete`,
        { method: "POST", body: JSON.stringify({ pairing_id: pairingId }) },
      );
      if (value.pending === true) return { ok: false, pending: true };
      const device = value.device ?? (await this.listDevices()).find((candidate) => candidate.id === deviceId);
      return value.paired === true && device
        ? { ok: true, device }
        : { ok: false, pending: false, reason: "UNAVAILABLE" };
    } catch (error) {
      const codeValue = error instanceof DesktopHttpError ? error.code : "";
      if (codeValue === "PAIRING_EXPIRED") return { ok: false, pending: false, reason: "EXPIRED" };
      if (codeValue === "PAIRING_REPLAY" || codeValue === "PAIRING_SESSION_MISMATCH") {
        return { ok: false, pending: false, reason: "STALE_CODE" };
      }
      const message = error instanceof DesktopHttpError ? error.message : undefined;
      return { ok: false, pending: false, reason: "UNAVAILABLE", message };
    }
  }

  async pairConfirm(deviceId: string, pairingId: string, code: string): Promise<PairConfirmResult> {
    try {
      const value = await this.request<{ device?: DesktopDevice }>(`/v1/devices/${encodeURIComponent(deviceId)}/pair/complete`, {
        method: "POST",
        body: JSON.stringify({ pairing_id: pairingId, code: code.trim().toUpperCase() }),
      });
      const device = value.device ?? (await this.listDevices()).find((candidate) => candidate.id === deviceId);
      return device ? { ok: true, device } : { ok: false, reason: "UNAVAILABLE" };
    } catch (error) {
      const codeValue = error instanceof DesktopHttpError ? error.code : "";
      if (codeValue === "PAIRING_EXPIRED") return { ok: false, reason: "EXPIRED" };
      if (codeValue === "PAIRING_REPLAY" || codeValue === "PAIRING_SESSION_MISMATCH") return { ok: false, reason: "STALE_CODE" };
      if (codeValue === "PAIRING_CODE_REJECTED" || codeValue === "PAIRING_ATTEMPTS_EXCEEDED") return { ok: false, reason: "INVALID_CODE" };
      const message = error instanceof DesktopHttpError ? error.message : undefined;
      return { ok: false, reason: "UNAVAILABLE", message };
    }
  }

  async sendControl(deviceId: string, action: DeviceControlAction): Promise<ControlResult> {
    if (action.type === "clipboard_sync") {
      return { ok: !action.enabled, deviceId, verification: action.enabled ? "PC_TO_PHONE_ONLY" : "DISABLED" };
    }
    if (action.type === "clipboard_paste") {
      const result = await this.request<{ updated?: boolean }>(`/v1/devices/${encodeURIComponent(deviceId)}/clipboard`, {
        method: "POST",
        body: JSON.stringify({ text: action.text }),
      });
      return { ok: result.updated === true, deviceId, verification: "clipboard-redacted" };
    }
    if (action.type === "disconnect") {
      await this.trustRevoke(deviceId);
      return { ok: true, deviceId, verification: "trust-revoked" };
    }
    if (action.type === "reconnect") {
      return { ok: true, deviceId, verification: "automatic-usb-reconnect" };
    }

    let body: Record<string, unknown>;
    if (action.type === "wake") body = { kind: "wake" };
    else if (action.type === "tap") body = { kind: "tap", x: action.x, y: action.y };
    else if (action.type === "scroll") body = { kind: action.direction === "UP" ? "scroll_up" : "scroll_down" };
    else if (action.type === "text") body = { kind: "text", text: action.text };
    else if (action.type === "key" && action.key === "BACK") body = { kind: "back" };
    else if (action.type === "key" && action.key === "HOME") body = { kind: "home" };
    else if (action.type === "key" && action.key === "ENTER") body = { kind: "text", text: "\n" };
    else return { ok: false, deviceId, verification: "KEY_UNAVAILABLE" };

    const result = await this.request<{ ok?: boolean; status?: string }>(`/v1/devices/${encodeURIComponent(deviceId)}/control`, {
      method: "POST",
      body: JSON.stringify(body),
    });
    return { ok: result.ok === true, deviceId, verification: result.status ?? "android-result" };
  }

  getVideoUrl(deviceId: string, profile: StreamProfile): string {
    return `${this.wsBase}/v1/devices/${encodeURIComponent(deviceId)}/video?profile=${profile}`;
  }

  getVideoProtocols(): string[] {
    return ["cyclone-v1", `cyclone-token.${this.token}`];
  }

  getFallbackFrameUrl(deviceId: string, profile: StreamProfile): string {
    return `${this.httpBase}/v1/devices/${encodeURIComponent(deviceId)}/stream/snapshot?profile=${profile}`;
  }

  async reportStreamDiagnostic(deviceId: string, event: StreamDiagnosticEvent): Promise<void> {
    try {
      await this.request(`/v1/devices/${encodeURIComponent(deviceId)}/diagnostics/stream-event`, {
        method: "POST",
        body: JSON.stringify({
          stage: event.stage,
          code: event.code,
          attempt: event.attempt,
          close_code: event.closeCode,
          retryable: event.retryable,
        }),
      });
    } catch {
      // Diagnostics must never destabilize the stream they observe.
    }
  }

  createConnectionDiagnosticBundle(deviceId: string): Promise<ConnectionDiagnosticBundle> {
    return this.request(`/v1/devices/${encodeURIComponent(deviceId)}/diagnostics/bundle`, { method: "POST" });
  }

  async listConnectors(): Promise<ConnectorCard[]> {
    try {
      const status = await invoke<ConnectorStatusPayload>("connector_status");
      const codexDetails = status.details?.codex;
      const gatewayDetails = status.details?.gateway;
      const mcpDetails = status.details?.mcp;
      const codexConnector = connector("codex", "Codex", "Give Codex instant, typed access to every trusted Cyclone phone.", status.codex);
      if (codexConnector.state === "CONNECTED" && gatewayDetails?.reachable === false) {
        codexConnector.state = "NEEDS_ATTENTION";
        codexConnector.actionLabel = "Recheck";
      }
      return [
        {
          ...codexConnector,
          detected: codexDetails?.detected,
          configured: codexDetails?.configured,
          configPath: codexDetails?.config_path,
          gatewayState: gatewayDetails?.state,
          gatewayReachable: gatewayDetails?.reachable,
          readyDeviceCount: gatewayDetails?.ready_device_count,
          deviceCount: gatewayDetails?.device_count,
          toolCount: mcpDetails?.tool_count,
          transport: mcpDetails?.transport,
          approvalMode: codexDetails?.approval_mode,
        },
        connector("deepseek-mcp", "DeepSeek / MCP harness", "Use Cyclone from OpenCode or another DeepSeek-powered MCP harness.", status.deepseek_harness),
        connector("generic-mcp", "Generic MCP", "Connect any compatible local MCP client.", status.generic_mcp),
      ];
    } catch {
      return [
        connector("codex", "Codex", "Use your Cyclone phones directly from Codex.", "ATTENTION"),
        connector("deepseek-mcp", "DeepSeek / MCP harness", "Use Cyclone from a DeepSeek-powered MCP harness.", "ATTENTION"),
        connector("generic-mcp", "Generic MCP", "Connect any compatible local MCP client.", "READY"),
      ];
    }
  }

  async runConnectorAction(connectorId: string, action: "connect" | "install" | "repair"): Promise<ConnectorActionResult> {
    const value = await invoke<ConnectorActionPayload>("connector_action", { connectorId, action });
    const gateway = value.verification?.gateway;
    return {
      ok: value.verification?.ok !== false,
      changed: value.changed,
      restartRequired: value.restart_required,
      message: value.message ?? "Cyclone connection updated.",
      path: value.path,
      readyDeviceCount: gateway?.ready_device_count,
      toolCount: value.verification?.tools?.length,
    };
  }

  getRuntimeStatus(): Promise<DesktopRuntimeStatus> {
    return this.request("/v1/diagnostics/status");
  }

  private async verifySessionBinding(timeoutMs: number): Promise<void> {
    const httpValue = await this.request<RuntimeSelfTest>("/v1/runtime/self-test");
    const wsValue = await new Promise<RuntimeSelfTest>((resolve, reject) => {
      let settled = false;
      const socket = new WebSocket(`${this.wsBase}/v1/runtime/self-test/ws`, this.getVideoProtocols());
      const timer = window.setTimeout(() => {
        if (settled) return;
        settled = true;
        try { socket.close(); } catch { /* noop */ }
        reject(new Error("Cyclone WebSocket session self-test timed out"));
      }, Math.max(250, timeoutMs));
      const finish = (value: RuntimeSelfTest | Error) => {
        if (settled) return;
        settled = true;
        window.clearTimeout(timer);
        try { socket.close(); } catch { /* noop */ }
        if (value instanceof Error) reject(value); else resolve(value);
      };
      socket.addEventListener("message", (event) => {
        try {
          finish(JSON.parse(String(event.data)) as RuntimeSelfTest);
        } catch {
          finish(new Error("Cyclone WebSocket session self-test returned invalid data"));
        }
      });
      socket.addEventListener("close", (event) => {
        if (!settled && event.code === 4401) finish(new Error("Cyclone WebSocket session authentication was rejected"));
      });
      socket.addEventListener("error", () => finish(new Error("Cyclone WebSocket session self-test failed")));
    });
    if (
      !httpValue.ok
      || !wsValue.ok
      || httpValue.sessionBinding !== wsValue.sessionBinding
      || httpValue.runtimeInstanceId !== wsValue.runtimeInstanceId
      || httpValue.runtimePort !== wsValue.runtimePort
    ) {
      throw new Error("Cyclone local HTTP and WebSocket clients are attached to different runtime sessions");
    }
  }

  private async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await fetch(`${this.httpBase}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: `Bearer ${this.token}`,
        ...(init.headers ?? {}),
      },
      cache: "no-store",
    });
    if (!response.ok) {
      let code = "HTTP_ERROR";
      let detail = "Cyclone backend request failed";
      try {
        const body = await response.json() as { detail?: { code?: string; message?: string } };
        code = body.detail?.code ?? code;
        detail = body.detail?.message ?? detail;
      } catch { /* safe fallback */ }
      throw new DesktopHttpError(response.status, code, detail);
    }
    return (await response.json()) as T;
  }
}

class DesktopHttpError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message);
  }
}

function connector(id: ConnectorCard["id"], name: string, description: string, raw?: string): ConnectorCard {
  const state: ConnectorCard["state"] = raw === "READY"
    ? "CONNECTED"
    : raw === "NOT_INSTALLED"
      ? "NOT_INSTALLED"
      : raw === "CONNECTED"
        ? "NEEDS_ATTENTION"
        : "READY_TO_CONNECT";
  return {
    id,
    name,
    description,
    state,
    actionLabel: state === "CONNECTED" ? "Recheck" : state === "NOT_INSTALLED" ? "Prepare connection" : "Connect",
  };
}

function stripSlash(value: string): string {
  return value.replace(/\/$/, "");
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
