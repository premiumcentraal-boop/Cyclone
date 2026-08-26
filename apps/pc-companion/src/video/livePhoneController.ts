import type { DesktopDevice, StreamDiagnosticEvent, StreamProfile, StreamUiState } from "../services/types.js";
import type { DesktopService } from "../services/types.js";
import type { VideoRendererFactoryInput, VideoRenderTarget, VideoRenderer } from "./decoder.js";
import { FallbackFrameRenderer } from "./fallbackFrameDecoder.js";
import { WebCodecsH264Renderer } from "./webcodecsH264Decoder.js";

export class LivePhoneController {
  private renderer: VideoRenderer | null = null;
  private state: StreamUiState = "CONNECTING";
  private stopped = false;

  constructor(
    private readonly service: DesktopService,
    private readonly device: DesktopDevice,
    private readonly profile: StreamProfile,
    private readonly target: VideoRenderTarget,
    private readonly onState: (state: StreamUiState) => void,
    private readonly onDiagnostic: (event: StreamDiagnosticEvent) => void = () => undefined,
    private readonly realRendererFactory: (input: VideoRendererFactoryInput) => VideoRenderer = (input) => new WebCodecsH264Renderer(input),
  ) {}

  start(): void {
    this.stopped = false;
    if (this.device.state === "SLEEPING" && this.service.mode === "mock") {
      this.setState("SLEEPING");
      this.startFallback();
      return;
    }
    if (this.device.state === "DISCONNECTED" && this.service.mode === "mock") {
      this.setState("RECONNECTING");
      this.startFallback();
      return;
    }
    if (this.service.mode === "real") {
      // Stream and wake are deliberately independent. A failed/slow phone health request must not
      // prevent the WebSocket from reporting SLEEPING, AUTH_REJECTED, or another exact stream state.
      const renderer = this.realRendererFactory(this.input());
      this.renderer = renderer;
      this.report({ stage: "client.controller.start", code: `DEVICE_${this.device.state}` });
      renderer.start();

      // Opening a paired phone is an explicit user action. Wake the display but never unlock it.
      void this.service.sendControl(this.device.id, { type: "wake" })
        .then((result) => this.report({
          stage: result.ok ? "client.wake.ok" : "client.wake.rejected",
          code: result.verification || (result.ok ? "WAKE_OK" : "WAKE_REJECTED"),
          retryable: !result.ok,
        }))
        .catch(() => this.report({ stage: "client.wake.failed", code: "WAKE_REQUEST_FAILED", retryable: true }));
      return;
    }
    this.startFallback();
  }

  stop(): void {
    this.stopped = true;
    this.renderer?.stop();
    this.renderer = null;
  }

  currentState(): StreamUiState { return this.state; }

  restart(): void {
    this.report({ stage: "client.manual.retry", code: "USER_RETRY", retryable: true });
    this.renderer?.stop();
    this.renderer = null;
    this.stopped = false;
    this.start();
  }

  private startFallback(): void {
    const renderer = new FallbackFrameRenderer(this.input());
    this.renderer = renderer;
    renderer.start();
  }

  private input() {
    return {
      device: this.device,
      profile: this.profile,
      streamUrl: this.service.getVideoUrl(this.device.id, this.profile),
      streamProtocols: this.service.getVideoProtocols(),
      fallbackUrl: this.device.lastFrameUrl ?? this.service.getFallbackFrameUrl(this.device.id, this.profile),
      target: this.target,
      callbacks: {
        onState: (state: StreamUiState) => {
          if (state === "LIVE" && this.device.state === "SLEEPING") this.setState("SLEEPING");
          else if (state === "LIVE" && this.device.state === "DISCONNECTED") this.setState("RECONNECTING");
          else this.setState(state);
        },
        onError: (_error: unknown) => this.report({ stage: "client.render.error", code: "FRAME_RENDER_ERROR", retryable: true }),
        onDiagnostic: (event: StreamDiagnosticEvent) => this.report(event),
      },
    };
  }

  private setState(state: StreamUiState): void {
    this.state = state;
    this.onState(state);
  }

  private report(event: StreamDiagnosticEvent): void {
    this.onDiagnostic(event);
    void this.service.reportStreamDiagnostic(this.device.id, event);
  }
}
