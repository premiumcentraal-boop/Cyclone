import { invoke } from "@tauri-apps/api/core";
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
  token: string;
}

type ConnectorStatusPayload = {
  codex?: string;
  deepseek_harness?: string;
  generic_mcp?: string;
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
        if (status.backendReachable) return;
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

  watchFleet(onChange: () => void): () => void {
    let disposed = false;
    let socket: WebSocket | null = null;
    let reconnectTimer: number | null = null;

    const connect = () => {
      if (disposed) return;
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
      socket.addEventListener("close", () => scheduleReconnect());
    };

    const scheduleReconnect = () => {
      if (disposed || reconnectTimer != null) return;
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

  pairBegin(deviceId: string): Promise<PairBeginResult> {
    return this.request<Record<string, unknown>>(`/v1/devices/${encodeURIComponent(deviceId)}/pair/begin`, { method: "POST" })
      .then((value) => ({
        pairingId: String(value.pairingId ?? ""),
        expiresAtEpochMs: Number(value.expiresAtEpochMs ?? value.expiresAtMs ?? Date.now() + 60_000),
        diagnosticsActive: value.diagnosticsActive === true,
        diagnosticsPath: typeof value.diagnosticsPath === "string" ? value.diagnosticsPath : null,
        diagnosticsMode: typeof value.diagnosticsMode === "string" ? value.diagnosticsMode : null,
      }));
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
      await this.request(`/v1/devices/${encodeURIComponent(deviceId)}/pair/revoke`, { method: "POST" });
      return { ok: true, deviceId, verification: "pairing-revoked" };
    }
    if (action.type === "reconnect") {
      return { ok: true, deviceId, verification: "automatic-usb-reconnect" };
    }

    let body: Record<string, unknown>;
    if (action.type === "tap") body = { kind: "tap", x: action.x, y: action.y };
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
    const stableProfile = profile === "focus" ? "thumbnail" : profile;
    return `${this.wsBase}/v1/devices/${encodeURIComponent(deviceId)}/video?profile=${stableProfile}`;
  }

  getVideoProtocols(): string[] {
    return ["cyclone-v1", `cyclone-token.${this.token}`];
  }

  getFallbackFrameUrl(_deviceId: string, _profile: StreamProfile): string {
    return "";
  }

  async listConnectors(): Promise<ConnectorCard[]> {
    try {
      const status = await invoke<ConnectorStatusPayload>("connector_status");
      return [
        connector("codex", "Codex", "Use your Cyclone phones directly from Codex.", status.codex),
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

  async runConnectorAction(connectorId: string, action: "connect" | "install" | "repair"): Promise<void> {
    await invoke("connector_action", { connectorId, action });
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
    actionLabel: state === "CONNECTED" ? undefined : state === "NOT_INSTALLED" ? "Install harness" : "Connect",
  };
}

function stripSlash(value: string): string {
  return value.replace(/\/$/, "");
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
