/**
 * Cyber's orb (plan 52 §5.1): the Manager's face. Every pose means something — ready, listening, thinking, a tool
 * running, speaking, something needs you, offline — and nothing moves when nothing is happening.
 *
 * Origin: original Cyclone code. The idea of an assistant orb with listen / think / speak poses and spring transitions
 * is after Space UI's Bloop Orb (MIT, https://github.com/adrielzimbril/space-ui); no Space UI source is copied.
 *
 * Three renderers, best first: a WebGL2 shader, a Canvas 2D drawing, and a still CSS orb (also under
 * prefers-reduced-motion). The loop runs only while the pose moves and the page is visible: idle drops to 20 frames a
 * second, offline draws once, and a hidden tab draws nothing.
 */
import { el } from "../dom.js";

export type OrbPose = "idle" | "listen" | "think" | "working" | "speak" | "attention" | "offline";
export const ORB_POSES: readonly OrbPose[] = ["idle", "listen", "think", "working", "speak", "attention", "offline"];
export type OrbRenderer = "webgl2" | "canvas" | "still";

/** What a pose looks like: how fast the inside swirls, how much it glows, how much the edge sweeps or haloes. */
export interface PoseParams {
  swirl: number;
  glow: number;
  amp: number;
  sat: number;
  ring: number;
  halo: number;
  bright: number;
}

const POSES: Record<OrbPose, PoseParams> = {
  idle: { swirl: 0.35, glow: 0.35, amp: 0, sat: 1, ring: 0, halo: 0, bright: 1 },
  listen: { swirl: 0.55, glow: 0.75, amp: 0.15, sat: 1, ring: 0, halo: 0, bright: 1.2 },
  think: { swirl: 1.5, glow: 0.5, amp: 0.05, sat: 1, ring: 0, halo: 0, bright: 1 },
  working: { swirl: 0.9, glow: 0.45, amp: 0, sat: 1, ring: 1, halo: 0, bright: 1 },
  speak: { swirl: 0.7, glow: 0.6, amp: 1, sat: 1, ring: 0, halo: 0, bright: 1.1 },
  attention: { swirl: 0.4, glow: 0.3, amp: 0, sat: 1, ring: 0, halo: 1, bright: 1 },
  offline: { swirl: 0, glow: 0.08, amp: 0, sat: 0.15, ring: 0, halo: 0, bright: 0.65 },
};
const KEYS = ["swirl", "glow", "amp", "sat", "ring", "halo", "bright"] as const;

export const poseParams = (pose: OrbPose): PoseParams => ({ ...POSES[pose] });

/** Frames per second a pose needs: 0 means draw once and stop. */
export const poseFps = (pose: OrbPose): number => (pose === "offline" ? 0 : pose === "idle" ? 20 : 60);

/** What the edge glows in: teal while a tool runs, amber when something needs the owner. */
export const ringColorVar = (pose: OrbPose): string => (pose === "attention" ? "--mgr-attention" : "--mgr-working");

export function chooseRenderer(can: { webgl2: boolean; canvas: boolean; reducedMotion: boolean }): OrbRenderer {
  if (can.reducedMotion) return "still";
  if (can.webgl2) return "webgl2";
  return can.canvas ? "canvas" : "still";
}

/** One step of a critically-damped-ish spring (k 170, d 22), per value. dt is clamped so a slow frame never jumps. */
export function springStep(x: PoseParams, v: PoseParams, target: PoseParams, dtSeconds: number): void {
  const dt = Math.min(Math.max(dtSeconds, 0), 1 / 30);
  for (const k of KEYS) {
    const a = 170 * (target[k] - x[k]) - 22 * v[k];
    v[k] += a * dt;
    x[k] += v[k] * dt;
  }
}

export function settled(x: PoseParams, v: PoseParams, target: PoseParams): boolean {
  return KEYS.every((k) => Math.abs(target[k] - x[k]) < 0.002 && Math.abs(v[k]) < 0.002);
}

/** "#5b5bd6", "#fff" or "rgb(…)"/"rgba(…)" as 0..1 channels; null when unreadable. */
export function parseColor(value: string): [number, number, number] | null {
  const v = value.trim();
  let m = /^#([0-9a-f]{3})$/i.exec(v);
  if (m) return [...m[1]].map((c) => parseInt(c + c, 16) / 255) as [number, number, number];
  m = /^#([0-9a-f]{6})$/i.exec(v);
  if (m) return [0, 2, 4].map((i) => parseInt(m![1].slice(i, i + 2), 16) / 255) as [number, number, number];
  m = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)/i.exec(v);
  if (m) return [m[1], m[2], m[3]].map((c) => Math.min(255, Number(c)) / 255) as [number, number, number];
  return null;
}

const FALLBACK = { a: [0.36, 0.36, 0.84], b: [0.22, 0.74, 0.97], c: [0.75, 0.52, 0.99], working: [0.05, 0.58, 0.53], attention: [0.85, 0.47, 0.02] } as const;

export interface OrbOptions {
  size: number;
  pose?: OrbPose;
  label?: string;
}

/** Everything the orb touches outside itself, so tests can drive it frame by frame. */
export interface OrbDeps {
  raf?: (fn: (t: number) => void) => unknown;
  cancelRaf?: (handle: unknown) => void;
  reducedMotion?: () => boolean;
  hidden?: () => boolean;
  onVisibility?: (fn: () => void) => () => void;
  /** Read a CSS custom property from the orb's element (theme colours). */
  cssVar?: (name: string) => string;
}

export interface Orb {
  element: HTMLElement;
  renderer: OrbRenderer;
  pose(): OrbPose;
  setPose(pose: OrbPose): void;
  /** Re-read the theme colours (after a light/dark switch). */
  refreshColors(): void;
  /** For tests and diagnostics: frames drawn so far. */
  frames(): number;
  destroy(): void;
}

const VERTEX = `#version 300 es
in vec2 p;
void main() { gl_Position = vec4(p, 0.0, 1.0); }`;

const FRAGMENT = `#version 300 es
precision mediump float;
uniform vec2 uRes;
uniform float uTime;
uniform vec3 uA, uB, uC, uRing;
uniform float uSwirl, uGlow, uAmp, uSat, uRingAmt, uHalo, uBright;
out vec4 o;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
float fbm(vec2 p) {
  float v = 0.0, a = 0.5;
  for (int i = 0; i < 4; i++) { v += a * noise(p); p *= 2.03; a *= 0.5; }
  return v;
}
void main() {
  vec2 uv = (gl_FragCoord.xy - 0.5 * uRes) / (0.5 * min(uRes.x, uRes.y));
  float r = length(uv);
  float radius = 0.76 + uAmp * 0.05 * sin(uTime * 7.0);
  float t = uTime * uSwirl;
  vec2 q = vec2(fbm(uv * 1.6 + t * 0.25), fbm(uv * 1.6 - t * 0.2 + 3.1));
  float n = fbm(uv * 2.2 + q * 1.8 + t * 0.15);
  vec3 col = mix(uA, uB, smoothstep(0.25, 0.75, n));
  col = mix(col, uC, smoothstep(0.55, 0.95, q.x));
  col += (1.0 - smoothstep(0.0, radius, length(uv - vec2(-0.28, 0.32)))) * 0.35 * uBright;
  col *= uBright;
  float gray = dot(col, vec3(0.299, 0.587, 0.114));
  col = mix(vec3(gray), col, uSat);
  float body = 1.0 - smoothstep(radius - 0.015, radius + 0.015, r);
  float sweep = pow(0.5 + 0.5 * cos(atan(uv.y, uv.x) - uTime * 3.0), 8.0);
  float rim = smoothstep(radius - 0.1, radius, r) * body;
  col = mix(col, uRing, clamp(rim * uRingAmt * sweep * 1.4, 0.0, 1.0));
  float outside = max(r - radius, 0.0);
  float glow = exp(-9.0 * outside) * (1.0 - body) * uGlow;
  float halo = exp(-4.5 * outside) * (1.0 - body) * uHalo * (0.55 + 0.45 * sin(uTime * 0.785));
  vec3 rgb = col * body + uB * glow * 0.8 + uRing * halo;
  float alpha = clamp(body + glow * 0.55 + halo * 0.7, 0.0, 1.0);
  o = vec4(rgb, alpha);
}`;

interface Painter {
  draw(time: number, x: PoseParams): void;
  colors(c: Record<"a" | "b" | "c" | "ring", readonly number[]>): void;
  destroy(): void;
}

function webglPainter(canvas: HTMLCanvasElement): Painter | null {
  const gl = (canvas as HTMLCanvasElement & { getContext(kind: "webgl2", o?: unknown): WebGL2RenderingContext | null })
    .getContext("webgl2", { premultipliedAlpha: false, alpha: true, antialias: true });
  if (!gl) return null;
  const compile = (type: number, source: string) => {
    const shader = gl.createShader(type);
    if (!shader) return null;
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    return gl.getShaderParameter(shader, gl.COMPILE_STATUS) ? shader : null;
  };
  const vs = compile(gl.VERTEX_SHADER, VERTEX);
  const fs = compile(gl.FRAGMENT_SHADER, FRAGMENT);
  const program = gl.createProgram();
  if (!vs || !fs || !program) return null;
  gl.attachShader(program, vs);
  gl.attachShader(program, fs);
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return null;
  gl.useProgram(program);
  const buffer = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const at = gl.getAttribLocation(program, "p");
  gl.enableVertexAttribArray(at);
  gl.vertexAttribPointer(at, 2, gl.FLOAT, false, 0, 0);
  gl.enable(gl.BLEND);
  gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
  const u = (name: string) => gl.getUniformLocation(program, name);
  const loc = { res: u("uRes"), time: u("uTime"), a: u("uA"), b: u("uB"), c: u("uC"), ring: u("uRing"), swirl: u("uSwirl"), glow: u("uGlow"),
    amp: u("uAmp"), sat: u("uSat"), ringAmt: u("uRingAmt"), halo: u("uHalo"), bright: u("uBright") };
  return {
    colors(c) {
      gl.uniform3f(loc.a, c.a[0], c.a[1], c.a[2]);
      gl.uniform3f(loc.b, c.b[0], c.b[1], c.b[2]);
      gl.uniform3f(loc.c, c.c[0], c.c[1], c.c[2]);
      gl.uniform3f(loc.ring, c.ring[0], c.ring[1], c.ring[2]);
    },
    draw(time, x) {
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.uniform2f(loc.res, canvas.width, canvas.height);
      gl.uniform1f(loc.time, time);
      gl.uniform1f(loc.swirl, x.swirl);
      gl.uniform1f(loc.glow, x.glow);
      gl.uniform1f(loc.amp, x.amp);
      gl.uniform1f(loc.sat, x.sat);
      gl.uniform1f(loc.ringAmt, x.ring);
      gl.uniform1f(loc.halo, x.halo);
      gl.uniform1f(loc.bright, x.bright);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    },
    destroy() {
      gl.deleteBuffer(buffer);
      gl.deleteProgram(program);
      gl.deleteShader(vs);
      gl.deleteShader(fs);
    },
  };
}

const css = (c: readonly number[], alpha = 1) => `rgba(${Math.round(c[0] * 255)}, ${Math.round(c[1] * 255)}, ${Math.round(c[2] * 255)}, ${alpha})`;

function canvasPainter(canvas: HTMLCanvasElement): Painter | null {
  const ctx = canvas.getContext?.("2d") as CanvasRenderingContext2D | null | undefined;
  if (!ctx) return null;
  let colors: Record<"a" | "b" | "c" | "ring", readonly number[]> = { a: FALLBACK.a, b: FALLBACK.b, c: FALLBACK.c, ring: FALLBACK.working };
  return {
    colors(c) {
      colors = c;
    },
    draw(time, x) {
      const w = canvas.width;
      const h = canvas.height;
      const cx = w / 2;
      const cy = h / 2;
      const r = Math.min(w, h) * 0.38 * (1 + x.amp * 0.025 * Math.sin(time * 7));
      ctx.clearRect(0, 0, w, h);
      if (x.glow > 0.01 || x.halo > 0.01) {
        const pulse = 0.55 + 0.45 * Math.sin(time * 0.785);
        const outer = ctx.createRadialGradient(cx, cy, r * 0.9, cx, cy, r * 1.3);
        outer.addColorStop(0, x.halo > 0.01 ? css(colors.ring, 0.55 * x.halo * pulse) : css(colors.b, 0.45 * x.glow));
        outer.addColorStop(1, css(colors.b, 0));
        ctx.fillStyle = outer;
        ctx.fillRect(0, 0, w, h);
      }
      ctx.save();
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.clip();
      if ("filter" in ctx) ctx.filter = `saturate(${x.sat.toFixed(2)}) brightness(${x.bright.toFixed(2)})`;
      const base = ctx.createRadialGradient(cx - r * 0.35, cy - r * 0.4, r * 0.1, cx, cy, r);
      base.addColorStop(0, css(colors.b));
      base.addColorStop(1, css(colors.a));
      ctx.fillStyle = base;
      ctx.fillRect(cx - r, cy - r, r * 2, r * 2);
      const blobs: Array<[readonly number[], number, number]> = [[colors.c, 0.9, 0], [colors.b, -0.7, 2.1], [colors.a, 0.5, 4.2]];
      for (const [color, speed, phase] of blobs) {
        const angle = phase + time * x.swirl * speed;
        const bx = cx + Math.cos(angle) * r * 0.42;
        const by = cy + Math.sin(angle) * r * 0.42;
        const blob = ctx.createRadialGradient(bx, by, 0, bx, by, r * 0.75);
        blob.addColorStop(0, css(color, 0.75));
        blob.addColorStop(1, css(color, 0));
        ctx.fillStyle = blob;
        ctx.fillRect(cx - r, cy - r, r * 2, r * 2);
      }
      if ("filter" in ctx) ctx.filter = "none";
      ctx.restore();
      if (x.ring > 0.01) {
        const start = time * 3;
        ctx.lineWidth = Math.max(2, r * 0.08);
        ctx.lineCap = "round";
        ctx.strokeStyle = css(colors.ring, Math.min(1, x.ring));
        ctx.beginPath();
        ctx.arc(cx, cy, r - ctx.lineWidth / 2, start, start + Math.PI * 0.6);
        ctx.stroke();
      }
    },
    destroy() {
      /* nothing to free */
    },
  };
}

export function createOrb(options: OrbOptions, deps: OrbDeps = {}): Orb {
  const element = el("span", "cyber-orb");
  element.setAttribute("role", "img");
  const size = Math.max(16, Math.round(options.size));
  element.style.width = `${size}px`;
  element.style.height = `${size}px`;
  let pose: OrbPose = options.pose ?? "idle";
  const label = options.label ?? "Cyber";

  const raf = deps.raf ?? ((fn: (t: number) => void) => (typeof requestAnimationFrame === "function" ? requestAnimationFrame(fn) : setTimeout(() => fn(Date.now()), 16)));
  const cancelRaf = deps.cancelRaf ?? ((h: unknown) => (typeof cancelAnimationFrame === "function" ? cancelAnimationFrame(h as number) : clearTimeout(h as ReturnType<typeof setTimeout>)));
  const reducedMotion = deps.reducedMotion ?? (() => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches);
  const hidden = deps.hidden ?? (() => typeof document !== "undefined" && document.visibilityState === "hidden");
  const onVisibility = deps.onVisibility ?? ((fn: () => void) => {
    if (typeof document === "undefined" || typeof document.addEventListener !== "function") return () => undefined;
    document.addEventListener("visibilitychange", fn);
    return () => document.removeEventListener("visibilitychange", fn);
  });
  const cssVar = deps.cssVar ?? ((name: string) => (typeof getComputedStyle === "function" ? getComputedStyle(element).getPropertyValue(name) : ""));

  const canvas = el("canvas", "cyber-orb-canvas");
  const dpr = Math.min(2, typeof window !== "undefined" && window.devicePixelRatio ? window.devicePixelRatio : 1);
  canvas.width = Math.round(size * dpr);
  canvas.height = Math.round(size * dpr);
  const canGl = typeof (canvas as { getContext?: unknown }).getContext === "function";
  let painter: Painter | null = null;
  let renderer = chooseRenderer({ webgl2: canGl, canvas: canGl, reducedMotion: reducedMotion() });
  if (renderer === "webgl2") {
    painter = webglPainter(canvas);
    if (!painter) renderer = "canvas";
  }
  if (renderer === "canvas") {
    painter = canvasPainter(canvas);
    if (!painter) renderer = "still";
  }
  element.dataset.renderer = renderer;
  if (renderer === "still") element.append(el("span", "cyber-orb-still"));
  else element.append(canvas);

  const x = poseParams(pose);
  const v: PoseParams = { swirl: 0, glow: 0, amp: 0, sat: 0, ring: 0, halo: 0, bright: 0 };
  let target = poseParams(pose);
  let frame: unknown = null;
  let last = 0;
  let started = 0;
  let drawn = 0;
  let destroyed = false;

  const color = (name: string, fallback: readonly number[]) => parseColor(cssVar(name)) ?? fallback;
  function refreshColors(): void {
    painter?.colors({ a: color("--mgr-orb-a", FALLBACK.a), b: color("--mgr-orb-b", FALLBACK.b), c: color("--mgr-orb-c", FALLBACK.c),
      ring: color(ringColorVar(pose), pose === "attention" ? FALLBACK.attention : FALLBACK.working) });
  }

  function describe(): void {
    element.dataset.pose = pose;
    element.className = `cyber-orb cyber-orb-${pose}`;
    const words: Record<OrbPose, string> = { idle: "ready", listen: "listening", think: "thinking", working: "working", speak: "answering",
      attention: "needs you", offline: "offline" };
    element.setAttribute("aria-label", `${label}: ${words[pose]}`);
  }

  function tick(now: number): void {
    frame = null;
    if (destroyed || !painter) return;
    if (hidden()) return; // resumes on visibilitychange
    const fps = poseFps(pose);
    const interval = fps ? 1000 / fps : 0;
    if (!started) started = now;
    if (fps && last && now - last < interval - 1) {
      frame = raf(tick);
      return;
    }
    const dt = last ? (now - last) / 1000 : 1 / 60;
    last = now;
    springStep(x, v, target, dt);
    painter.draw((now - started) / 1000, x);
    drawn += 1;
    const moving = fps > 0 || !settled(x, v, target);
    if (moving) frame = raf(tick);
  }

  function kick(): void {
    if (destroyed || !painter || frame !== null) return;
    last = 0;
    frame = raf(tick);
  }

  const stopVisibility = onVisibility(() => {
    if (!hidden()) kick();
  });

  describe();
  refreshColors();
  kick();

  return {
    element,
    renderer,
    pose: () => pose,
    setPose(next) {
      if (!ORB_POSES.includes(next) || next === pose) return;
      pose = next;
      target = poseParams(next);
      describe();
      refreshColors();
      kick();
    },
    refreshColors() {
      refreshColors();
      kick();
    },
    frames: () => drawn,
    destroy() {
      destroyed = true;
      if (frame !== null) cancelRaf(frame);
      frame = null;
      stopVisibility();
      painter?.destroy();
      element.remove();
    },
  };
}
