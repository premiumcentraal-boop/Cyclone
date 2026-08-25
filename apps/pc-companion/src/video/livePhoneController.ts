import type { DesktopDevice, StreamProfile, StreamUiState } from "../services/types.js";
import type { DesktopService } from "../services/types.js";
import type { VideoRenderTarget, VideoRenderer } from "./decoder.js";
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
  ) {}

  start(): void {
    this.stopped = false;
    if (this.device.state === "SLEEPING" && this.service.mode === "mock") {
      this.setState("SLEEPING");
      this.startFallback();
      return;
    }
    if (this.device.state === "DISCONNECTED") {
      this.setState("RECONNECTING");
      if (this.service.mode === "mock") this.startFallback();
      return;
    }
    if (this.service.mode === "real") {
      // Opening a paired phone is an explicit user action. Wake the display (never unlock it), then
      // start capture so an asleep phone cannot leave the UI waiting forever without a first frame.
      void this.service.sendControl(this.device.id, { type: "wake" })
        .catch(() => undefined)
        .finally(() => {
          if (this.stopped || this.renderer || this.state === "UNAVAILABLE") return;
          const renderer = new WebCodecsH264Renderer(this.input());
          this.renderer = renderer;
          renderer.start();
        });
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
        onError: (_error: unknown) => undefined,
      },
    };
  }

  private setState(state: StreamUiState): void {
    this.state = state;
    this.onState(state);
  }
}
