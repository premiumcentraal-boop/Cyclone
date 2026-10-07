/**
 * A phone's avatar in Cyber's trails, briefs and alerts: the phone's own colour (alpha 107's colour select) and
 * initials from its nickname, with a pattern angle seeded by its id so two phones of the same colour still differ.
 * Deterministic: the same phone always looks the same.
 *
 * Origin: original Cyclone code; the idea of procedural avatars is after Space UI's generative avatars (MIT,
 * https://github.com/adrielzimbril/space-ui); no Space UI source is copied.
 */
import { el } from "../dom.js";

/** 32-bit FNV-1a: a small, stable seed from a string. */
export function seedOf(text: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i += 1) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

/** Up to two letters: "Work Pixel" → "WP", "pixel" → "PI", "" → "?". */
export function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (!words.length) return "?";
  const letters = words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2);
  return letters.toUpperCase();
}

/** The twelve phone colours (alpha 107, ``PHONE_COLORS`` in the gateway); their values live in styles/fleet.css. */
export const PHONE_COLORS = ["blue", "indigo", "purple", "pink", "red", "orange", "yellow", "green", "mint", "teal", "cyan", "graphite"] as const;

export function phoneAvatar(deviceId: string, name: string, color?: string | null, size = 24): HTMLElement {
  const seed = seedOf(deviceId || name);
  const base = color && (PHONE_COLORS as readonly string[]).includes(color) ? `var(--phone-${color})` : "var(--accent)";
  const angle = seed % 360;
  const node = el("span", "cyber-avatar", initials(name));
  node.style.width = node.style.height = `${size}px`;
  node.style.fontSize = `${Math.round(size * 0.42)}px`;
  node.style.background = `linear-gradient(${angle}deg, ${base}, color-mix(in srgb, ${base} 55%, white))`;
  node.setAttribute("role", "img");
  node.setAttribute("aria-label", name || deviceId);
  node.title = name || deviceId;
  return node;
}
