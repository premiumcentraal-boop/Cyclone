import type { VideoRenderer, VideoRendererFactoryInput } from "./decoder.js";

const HEADER_BYTES = 16;
export const MAX_STREAM_RECONNECT_ATTEMPTS = 6;

export function streamCloseIsTerminal(code: number): boolean {
  return code === 4400 || code === 4401 || code === 4404;
}

/**
 * Renderer for `cyclone.desktop.video.v1`.
 *
 * Desktop V1 release builds use JPEG image payloads for the reliable multi-phone path. Each binary
 * WebSocket message is: u64be timestamp_ms + u32be sequence + u32be payload_len + payload.
 * Experimental AVC remains a backend concern and is intentionally not required for the V1 UI.
 */
export class WebCodecsH264Renderer implements VideoRenderer {
  private socket: WebSocket | null = null;
  private stopped = false;
  private reconnectTimer: number | null = null;
  private reconnectAttempt = 0;
  private codec = "image/jpeg";
  private drawGeneration = 0;

  constructor(private readonly input: VideoRendererFactoryInput) {}

  start(): void {
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.drawGeneration += 1;
    if (this.reconnectTimer != null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.socket?.close();
    this.socket = null;
  }

  private open(): void {
    if (this.stopped) return;
    this.input.callbacks.onState(this.reconnectAttempt === 0 ? "CONNECTING" : "RECONNECTING");
    try {
      const socket = new WebSocket(this.input.streamUrl, this.input.streamProtocols);
      socket.binaryType = "arraybuffer";
      this.socket = socket;
      // A TCP/WebSocket open is not proof of a healthy phone stream. Reset backoff only after the
      // server sends valid stream state or a frame; otherwise immediate close/open loops never end.
      socket.onopen = () => undefined;
      socket.onmessage = (event) => this.handleMessage(event.data);
      socket.onerror = () => this.fail(new Error("Phone stream unavailable"));
      socket.onclose = (event) => {
        if (this.stopped) return;
        if (streamCloseIsTerminal(event.code)) {
          this.input.callbacks.onState("UNAVAILABLE");
          return;
        }
        this.scheduleReconnect();
      };
    } catch (error) {
      this.fail(error);
      this.scheduleReconnect();
    }
  }

  private handleMessage(data: string | ArrayBuffer | Blob): void {
    try {
      if (typeof data === "string") {
        const message = JSON.parse(data) as Record<string, unknown>;
        if (message.type === "stream.init" && typeof message.codec === "string") {
          this.codec = message.codec;
        } else if (message.type === "screen.state" && message.state === "SLEEPING") {
          this.reconnectAttempt = 0;
          this.input.callbacks.onState("SLEEPING");
        } else if (message.type === "stream.error") {
          this.input.callbacks.onState("STREAM_ERROR");
        }
        return;
      }
      if (data instanceof Blob) {
        void data.arrayBuffer().then((buffer) => this.drawPacket(buffer)).catch((error) => this.fail(error));
        return;
      }
      void this.drawPacket(data);
    } catch (error) {
      this.fail(error);
    }
  }

  private async drawPacket(buffer: ArrayBuffer): Promise<void> {
    if (this.stopped || buffer.byteLength < HEADER_BYTES) return;
    const view = new DataView(buffer);
    const payloadLength = view.getUint32(12, false);
    if (payloadLength <= 0 || HEADER_BYTES + payloadLength > buffer.byteLength) {
      throw new Error("Invalid Cyclone video frame");
    }
    if (!this.codec.startsWith("image/")) {
      this.input.callbacks.onState("UNAVAILABLE");
      return;
    }
    const generation = ++this.drawGeneration;
    const payload = buffer.slice(HEADER_BYTES, HEADER_BYTES + payloadLength);
    const bitmap = await createImageBitmap(new Blob([payload], { type: this.codec }));
    try {
      if (this.stopped || generation !== this.drawGeneration) return;
      const canvas = this.input.target.canvas;
      if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
        canvas.width = bitmap.width;
        canvas.height = bitmap.height;
      }
      const context = canvas.getContext("2d", { alpha: false });
      context?.drawImage(bitmap, 0, 0, bitmap.width, bitmap.height);
      canvas.hidden = false;
      this.input.target.fallbackImage.hidden = true;
      this.input.callbacks.onState("LIVE");
      this.reconnectAttempt = 0;
    } finally {
      bitmap.close();
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer != null) return;
    if (this.reconnectAttempt >= MAX_STREAM_RECONNECT_ATTEMPTS) {
      this.input.callbacks.onState("UNAVAILABLE");
      return;
    }
    this.input.callbacks.onState("RECONNECTING");
    const delay = Math.min(5000, 500 * 2 ** Math.min(this.reconnectAttempt, 3));
    this.reconnectAttempt += 1;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.open();
    }, delay);
  }

  private fail(error: unknown): void {
    this.input.callbacks.onError(error);
    this.input.callbacks.onState("STREAM_ERROR");
  }
}
