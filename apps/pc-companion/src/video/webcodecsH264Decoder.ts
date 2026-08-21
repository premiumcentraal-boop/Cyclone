import type { VideoRenderer, VideoRendererFactoryInput } from "./decoder.js";

interface PendingFrameMetadata {
  key: boolean;
  timestampUs: number;
  durationUs?: number;
}

/**
 * H.264 decoder for the Desktop V1 websocket stream.
 * Framing assumption is isolated here: JSON config/frame metadata messages precede binary access
 * units. If Agent 3's documented framing changes, only this module needs to change.
 */
export class WebCodecsH264Renderer implements VideoRenderer {
  private socket: WebSocket | null = null;
  private decoder: VideoDecoder | null = null;
  private stopped = false;
  private pending: PendingFrameMetadata | null = null;
  private reconnectTimer: number | null = null;
  private reconnectAttempt = 0;

  constructor(private readonly input: VideoRendererFactoryInput) {}

  start(): void {
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer != null) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.socket?.close();
    this.socket = null;
    try {
      this.decoder?.close();
    } catch {
      // Decoder may already be closed after a browser-level failure.
    }
    this.decoder = null;
  }

  private open(): void {
    if (this.stopped) return;
    if (!("VideoDecoder" in window)) {
      this.input.callbacks.onError(new Error("WebCodecs unavailable"));
      this.input.callbacks.onState("UNAVAILABLE");
      return;
    }
    this.input.callbacks.onState(this.reconnectAttempt === 0 ? "CONNECTING" : "RECONNECTING");
    try {
      const socket = new WebSocket(this.input.streamUrl);
      socket.binaryType = "arraybuffer";
      this.socket = socket;
      socket.onopen = () => {
        this.reconnectAttempt = 0;
      };
      socket.onmessage = (event) => this.handleMessage(event.data);
      socket.onerror = () => this.fail(new Error("Phone stream unavailable"));
      socket.onclose = () => {
        if (!this.stopped) this.scheduleReconnect();
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
        if (message.type === "config") {
          this.configureDecoder(message);
        } else if (message.type === "frame") {
          this.pending = {
            key: message.key === true || message.key_frame === true,
            timestampUs: numeric(message.timestamp_us, performance.now() * 1000),
            durationUs: optionalNumeric(message.duration_us),
          };
        } else if (message.type === "sleeping") {
          this.input.callbacks.onState("SLEEPING");
        }
        return;
      }
      if (data instanceof Blob) {
        void data.arrayBuffer().then((buffer) => this.decodeAccessUnit(buffer)).catch((error) => this.fail(error));
        return;
      }
      this.decodeAccessUnit(data);
    } catch (error) {
      this.fail(error);
    }
  }

  private configureDecoder(message: Record<string, unknown>): void {
    const codec = typeof message.codec === "string" ? message.codec : this.input.device.video.codec ?? "avc1.42E01E";
    const description = typeof message.description_base64 === "string" ? decodeBase64(message.description_base64) : undefined;
    this.decoder?.close();
    this.decoder = new VideoDecoder({
      output: (frame) => this.drawFrame(frame),
      error: (error) => this.fail(error),
    });
    this.decoder.configure({ codec, description, optimizeForLatency: true, hardwareAcceleration: "prefer-hardware" });
  }

  private decodeAccessUnit(buffer: ArrayBuffer): void {
    if (!this.decoder || this.decoder.state !== "configured") {
      this.configureDecoder({ type: "config", codec: this.input.device.video.codec ?? "avc1.42E01E" });
    }
    const metadata = this.pending ?? {
      key: true,
      timestampUs: Math.floor(performance.now() * 1000),
    };
    this.pending = null;
    this.decoder?.decode(new EncodedVideoChunk({
      type: metadata.key ? "key" : "delta",
      timestamp: metadata.timestampUs,
      duration: metadata.durationUs,
      data: new Uint8Array(buffer),
    }));
  }

  private drawFrame(frame: VideoFrame): void {
    try {
      const canvas = this.input.target.canvas;
      const width = frame.displayWidth || frame.codedWidth;
      const height = frame.displayHeight || frame.codedHeight;
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
      const context = canvas.getContext("2d", { alpha: false });
      context?.drawImage(frame, 0, 0, width, height);
      canvas.hidden = false;
      this.input.target.fallbackImage.hidden = true;
      this.input.callbacks.onState("LIVE");
    } finally {
      frame.close();
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.reconnectTimer != null) return;
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

function decodeBase64(value: string): Uint8Array {
  const raw = atob(value);
  return Uint8Array.from(raw, (char) => char.charCodeAt(0));
}

function numeric(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function optionalNumeric(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}
