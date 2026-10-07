/**
 * Cyber, the character (plan 53 R4; replaces R3's orb at the owner's request): a small glass-and-visor robot with its
 * own pose and motion for every action, so what Cyber is doing reads at a glance, even at the size of a sidebar icon.
 *
 * Origin: original Cyclone code (an SVG built with DOM APIs, animated by styles/cyber.css). The approach was chosen
 * after comparing open-source assistant characters (Rive mascots, VRM / Live2D companions such as Project AIRI);
 * no outside source or artwork is copied.
 *
 * Moods: idle (floats, blinks, follows the pointer), listen (leans in, sound waves), think (looks away, hand to chin,
 * dots circle the antenna), working (focused eyes, code on the visor, a gear turning), speak (the mouth moves with the
 * words), attention (hops, waves, amber badge), success (happy eyes, a jump, confetti), error (crossed eyes, a shake)
 * and offline (asleep, grey, floating z's). Under prefers-reduced-motion nothing moves, but every mood keeps its own
 * pose, so the character still says what it is doing.
 */
export type CyberMood = "idle" | "listen" | "think" | "working" | "speak" | "attention" | "success" | "error" | "offline";
export const CYBER_MOODS: readonly CyberMood[] = ["idle", "listen", "think", "working", "speak", "attention", "success", "error", "offline"];

export const MOOD_WORDS: Record<CyberMood, string> = {
  idle: "ready", listen: "listening", think: "thinking", working: "working", speak: "answering", attention: "needs you",
  success: "done", error: "something failed", offline: "offline",
};

const SVG = "http://www.w3.org/2000/svg";
let counter = 0;

type Attrs = Record<string, string | number>;

function node(tag: string, attrs: Attrs = {}, ...children: Element[]): SVGElement {
  const el = document.createElementNS(SVG, tag) as SVGElement;
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, String(v));
  for (const child of children) el.appendChild(child);
  return el;
}

function text(x: number, y: number, value: string): SVGElement {
  const t = node("text", { x, y });
  t.textContent = value;
  return t;
}

/** The SVG. Ids are unique per character so several on one page never share gradients, clips or filters. */
export function buildCharacterSvg(): { svg: SVGElement; id: number } {
  const id = ++counter;
  const ref = (name: string) => `url(#cy-${name}-${id})`;
  // Colours that follow the theme go in style (var() in a presentation attribute is not understood everywhere).
  const stop = (offset: string, color: string) => node("stop", { offset, style: `stop-color: ${color}` });
  const defs = node("defs", {},
    node("linearGradient", { id: `cy-shell-${id}`, x1: 0, y1: 0, x2: 0, y2: 1 }, stop("0", "var(--cy-shell-top)"), stop("1", "var(--cy-shell-bottom)")),
    node("linearGradient", { id: `cy-visor-${id}`, x1: 0, y1: 0, x2: 0, y2: 1 }, stop("0", "var(--cy-visor-top)"), stop("1", "var(--cy-visor-bottom)")),
    node("clipPath", { id: `cy-clip-${id}` }, node("rect", { x: 70, y: 96, width: 120, height: 82, rx: 34 })),
    node("filter", { id: `cy-glow-${id}`, x: "-50%", y: "-50%", width: "200%", height: "200%" },
      node("feGaussianBlur", { stdDeviation: 2.2, result: "b" }),
      node("feMerge", {}, node("feMergeNode", { in: "b" }), node("feMergeNode", { in: "SourceGraphic" }))),
  );
  const codeLines: Array<[number, number, number]> = [[80, 102, 40], [126, 102, 26], [80, 112, 62], [80, 122, 30], [116, 122, 44], [80, 132, 54], [80, 142, 22],
    [108, 142, 50], [80, 152, 70], [80, 162, 34], [80, 172, 58], [80, 182, 40], [126, 182, 26], [80, 192, 62]];
  const confetti: Array<[number, number, string, string, string]> = [[84, 70, "var(--accent)", "-46px", "-60px"], [170, 70, "var(--mgr-working)", "48px", "-58px"],
    [120, 60, "var(--mgr-attention)", "-10px", "-78px"], [140, 62, "var(--success)", "18px", "-80px"], [64, 110, "var(--accent)", "-64px", "-20px"],
    [192, 110, "var(--danger)", "62px", "-24px"]];
  const svg = node("svg", { class: "cy", viewBox: "0 0 260 260", role: "img", "aria-hidden": "false" },
    defs,
    node("g", { class: "cy-shadowg" }, node("ellipse", { class: "cy-shadow", cx: 130, cy: 240, rx: 60, ry: 9 })),
    node("g", { class: "cy-bob" },
      node("g", { class: "cy-body" },
        node("g", { class: "cy-zzz cy-extra" }, text(176, 58, "z"), text(186, 44, "z"), text(196, 30, "Z")),
        node("g", { class: "cy-head" },
          node("line", { class: "cy-stem", x1: 130, y1: 68, x2: 130, y2: 44 }),
          node("circle", { class: "cy-ant", cx: 130, cy: 36, r: 9, filter: ref("glow") }),
          node("g", { class: "cy-dots cy-extra" },
            node("circle", { class: "cy-dot", cx: 130, cy: 14, r: 4 }), node("circle", { class: "cy-dot", cx: 152, cy: 40, r: 3.4, opacity: 0.7 }),
            node("circle", { class: "cy-dot", cx: 108, cy: 42, r: 2.8, opacity: 0.45 })),
          node("rect", { class: "cy-ear", x: 38, y: 120, width: 18, height: 40, rx: 9 }),
          node("rect", { class: "cy-ear", x: 204, y: 120, width: 18, height: 40, rx: 9 }),
          node("rect", { class: "cy-shell", x: 50, y: 66, width: 160, height: 142, rx: 58, fill: ref("shell") }),
          node("rect", { class: "cy-visor", x: 70, y: 96, width: 120, height: 82, rx: 34, fill: ref("visor") }),
          node("g", { "clip-path": ref("clip") },
            node("g", { class: "cy-code cy-extra" }, node("g", {}, ...codeLines.map(([x, y, w]) => node("rect", { x, y, width: w, height: 4, rx: 2 })))),
            node("g", { class: "cy-eyes", filter: ref("glow") },
              node("g", { class: "cy-eye cy-eye-l" }, node("rect", { class: "cy-pupil", x: 98, y: 118, width: 18, height: 28, rx: 9 })),
              node("g", { class: "cy-eye cy-eye-r" }, node("rect", { class: "cy-pupil", x: 144, y: 118, width: 18, height: 28, rx: 9 })),
              node("path", { class: "cy-happy", d: "M97 136 q10 -14 20 0 M143 136 q10 -14 20 0" }),
              node("path", { class: "cy-closed", d: "M97 134 q10 6 20 0 M143 134 q10 6 20 0" }),
              node("path", { class: "cy-xeyes", d: "M99 124 l16 16 M115 124 l-16 16 M145 124 l16 16 M161 124 l-16 16" })),
            node("rect", { class: "cy-mouth", x: 121, y: 158, width: 18, height: 6, rx: 3 }),
            node("path", { class: "cy-smile", d: "M118 156 q12 12 24 0", filter: ref("glow") })),
          node("circle", { class: "cy-blush", cx: 86, cy: 160, r: 7 }), node("circle", { class: "cy-blush", cx: 174, cy: 160, r: 7 }),
          node("g", { class: "cy-badge cy-extra" }, node("circle", { cx: 204, cy: 76, r: 14 }), text(204, 82, "!"))),
        node("g", { class: "cy-waves cy-extra" },
          node("path", { class: "cy-wave", d: "M28 118 q-12 22 0 44" }), node("path", { class: "cy-wave", d: "M16 108 q-18 32 0 64" }),
          node("path", { class: "cy-wave", d: "M232 118 q12 22 0 44" }), node("path", { class: "cy-wave", d: "M244 108 q18 32 0 64" })),
        node("circle", { class: "cy-hand cy-hand-l", cx: 44, cy: 192, r: 13, fill: ref("shell") }),
        node("g", { class: "cy-hand-r" }, node("circle", { class: "cy-hand", cx: 216, cy: 192, r: 13, fill: ref("shell") })),
        node("g", { class: "cy-tool cy-extra" }, node("circle", { class: "cy-gear", cx: 222, cy: 160, r: 13 }), node("circle", { class: "cy-gear-hub", cx: 222, cy: 160, r: 5 })),
        node("g", { class: "cy-confetti" }, ...confetti.map(([x, y, fill, dx, dy]) => {
          const r = node("rect", { class: "cy-conf", x, y, width: 7, height: 12, rx: 2 });
          r.setAttribute("style", `fill: ${fill}; --dx: ${dx}; --dy: ${dy}`);
          return r;
        })))));
  return { svg, id };
}

export interface CharacterOptions {
  size: number;
  mood?: CyberMood;
  label?: string;
  /** Eyes follow the pointer (the larger characters; off for the small ones by default). */
  follow?: boolean;
}

export interface CharacterDeps {
  setTimer?: (fn: () => void, ms: number) => unknown;
  clearTimer?: (handle: unknown) => void;
  reducedMotion?: () => boolean;
  /** Where to listen for the pointer (window in the browser). */
  pointerTarget?: { addEventListener(type: string, fn: (e: { clientX: number; clientY: number }) => void): void; removeEventListener(type: string, fn: (e: { clientX: number; clientY: number }) => void): void } | null;
  viewport?: () => { width: number; height: number };
  random?: () => number;
}

export interface Character {
  element: HTMLElement;
  mood(): CyberMood;
  setMood(mood: CyberMood): void;
  /** Move the mouth once (call for every streamed piece of text while answering). */
  talk(): void;
  destroy(): void;
}

/** Moods in which the eyes do their own thing instead of following the pointer. */
const EYES_BUSY: readonly CyberMood[] = ["think", "working", "success", "error", "offline"];

export function createCharacter(options: CharacterOptions, deps: CharacterDeps = {}): Character {
  const setTimer = deps.setTimer ?? ((fn, ms) => setTimeout(fn, ms));
  const clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h as ReturnType<typeof setTimeout>));
  // Where the platform cannot say (no matchMedia: tests, old embeds), the character keeps still: no blink timers.
  const reduced = deps.reducedMotion ?? (() => typeof matchMedia !== "function" || matchMedia("(prefers-reduced-motion: reduce)").matches);
  const random = deps.random ?? Math.random;
  const viewport = deps.viewport ?? (() => ({ width: (globalThis as { innerWidth?: number }).innerWidth ?? 1280, height: (globalThis as { innerHeight?: number }).innerHeight ?? 800 }));
  const pointer = deps.pointerTarget === undefined ? (options.follow && typeof window !== "undefined" && typeof window.addEventListener === "function" ? window : null) : deps.pointerTarget;

  const element = document.createElement("span");
  element.className = "cy-wrap";
  const size = Math.max(20, Math.round(options.size));
  element.style.width = `${size}px`;
  element.style.height = `${size}px`;
  const { svg } = buildCharacterSvg();
  element.appendChild(svg);
  const label = options.label ?? "Cyber";
  let mood: CyberMood = options.mood ?? "idle";
  let blinkTimer: unknown = null;
  let mouthTimer: unknown = null;
  let destroyed = false;

  const setVar = (name: string, value: string) => (svg as unknown as HTMLElement).style.setProperty(name, value);

  function describe(): void {
    svg.setAttribute("data-mood", mood);
    element.dataset.mood = mood;
    element.setAttribute("role", "img");
    element.setAttribute("aria-label", `${label}: ${MOOD_WORDS[mood]}`);
    element.title = `${label}: ${MOOD_WORDS[mood]}`;
  }

  function scheduleBlink(): void {
    if (destroyed || reduced()) return;
    blinkTimer = setTimer(() => {
      blinkTimer = null;
      if (destroyed) return;
      if (mood !== "offline" && mood !== "success" && mood !== "error") {
        setVar("--blink", "0.08");
        setTimer(() => setVar("--blink", "1"), 130);
      }
      scheduleBlink();
    }, 2400 + random() * 3200);
  }

  const onPointer = (e: { clientX: number; clientY: number }) => {
    if (EYES_BUSY.includes(mood)) return;
    const rect = element.getBoundingClientRect?.();
    if (!rect) return;
    const view = viewport();
    const dx = (e.clientX - (rect.left + rect.width / 2)) / (view.width / 2);
    const dy = (e.clientY - (rect.top + rect.height / 2)) / (view.height / 2);
    setVar("--px", `${(Math.max(-1, Math.min(1, dx)) * 9).toFixed(1)}px`);
    setVar("--py", `${(Math.max(-1, Math.min(1, dy)) * 6).toFixed(1)}px`);
  };
  pointer?.addEventListener("pointermove", onPointer);

  describe();
  scheduleBlink();

  return {
    element,
    mood: () => mood,
    setMood(next) {
      if (!CYBER_MOODS.includes(next) || next === mood) return;
      mood = next;
      if (EYES_BUSY.includes(mood)) {
        setVar("--px", "0px");
        setVar("--py", "0px");
      }
      if (mood !== "speak") setVar("--m", "1");
      describe();
    },
    talk() {
      if (destroyed || mood !== "speak" || reduced()) return;
      setVar("--m", (0.4 + random() * 2.2).toFixed(2));
      if (mouthTimer !== null) clearTimer(mouthTimer);
      mouthTimer = setTimer(() => {
        mouthTimer = null;
        setVar("--m", "1");
      }, 140);
    },
    destroy() {
      destroyed = true;
      if (blinkTimer !== null) clearTimer(blinkTimer);
      if (mouthTimer !== null) clearTimer(mouthTimer);
      pointer?.removeEventListener("pointermove", onPointer);
      element.remove();
    },
  };
}
