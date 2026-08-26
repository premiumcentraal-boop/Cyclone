import test from "node:test";
import assert from "node:assert/strict";
import {
  KEEPALIVE_TYPE,
  STALE_FRAME_TIMEOUT_MS,
  STREAM_RECOVERY_TIMEOUT_MS,
  streamCloseIsTerminal,
  streamErrorIsRecoverable,
} from "../.test-dist/video/webcodecsH264Decoder.js";
import { FALLBACK_WS_RETRY_MS, LivePhoneController } from "../.test-dist/video/livePhoneController.js";

test("recoverable stream errors keep the socket contract while terminal closes stay terminal", () => {
  assert.equal(streamErrorIsRecoverable({ type: "stream.error", retryable: true }), true);
  assert.equal(streamErrorIsRecoverable({ type: "stream.error", retryable: false }), false);
  assert.equal(streamErrorIsRecoverable({ type: "stream.init" }), false);
  assert.equal(streamCloseIsTerminal(4401), true);
  assert.equal(streamCloseIsTerminal(1006), false);
});

test("stream health deadlines bound recoveries and stale live frames", () => {
  assert.equal(KEEPALIVE_TYPE, "stream.keepalive");
  assert.equal(STREAM_RECOVERY_TIMEOUT_MS, 20000);
  assert.equal(STALE_FRAME_TIMEOUT_MS, 6000);
});

test("unavailable websocket switches to the snapshot fallback and later retries the ws", async () => {
  const originalWindow = globalThis.window;
  globalThis.window = { setTimeout, clearTimeout };
  try {
    const events = [];
    const image = { hidden: false, src: "", onload: null, onerror: null };
    const target = { container: {}, canvas: { hidden: false }, fallbackImage: image };
    const service = {
      mode: "real",
      sendControl: async () => ({ ok: true, deviceId: "phone" }),
      reportStreamDiagnostic: async () => {},
      getVideoUrl: () => "ws://127.0.0.1/video",
      getVideoProtocols: () => [],
      getFallbackFrameUrl: () => "http://127.0.0.1:8765/v1/devices/phone/stream/snapshot?profile=focus",
    };
    const device = {
      id: "phone",
      name: "Pixel",
      state: "READY",
      paired: true,
      connectionLabel: "Ready",
      lastSeenEpochMs: 0,
      video: { mode: "SCREENSHOT", width: 1080, height: 2400, rotationDegrees: 0 },
      capabilities: { keyboard: true, clipboard: true, clipboardSync: false, reconnect: true },
    };
    const realFactory = (input) => ({
      start() { input.callbacks.onState("UNAVAILABLE"); },
      stop() {},
    });
    const controller = new LivePhoneController(service, device, "focus", target, () => {}, (event) => events.push(event), realFactory);
    controller.start();
    await Promise.resolve();
    assert.ok(events.some((event) => event.code === "FALLBACK_PREVIEW"), "fallback preview should activate");
    assert.match(image.src, /stream\/snapshot\?profile=focus/);
    assert.equal(FALLBACK_WS_RETRY_MS, 30000);
    controller.stop();
  } finally {
    globalThis.window = originalWindow;
  }
});
