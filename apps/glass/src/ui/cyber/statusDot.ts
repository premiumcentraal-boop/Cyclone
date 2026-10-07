/**
 * A live status dot (plan 54 §5): green and breathing while something is live, still otherwise. Colour is never the
 * only signal: every dot carries its words for screen readers and as a tooltip.
 *
 * Origin: original Cyclone code; after Space UI's Status Badge (MIT, https://github.com/adrielzimbril/space-ui); no
 * Space UI source is copied.
 */
import { el } from "../dom.js";

export type DotState = "live" | "idle" | "warn" | "down";

export function statusDot(state: DotState, label: string): HTMLElement {
  const dot = el("span", `cyber-dot cyber-dot-${state}`);
  dot.setAttribute("role", "img");
  dot.setAttribute("aria-label", label);
  dot.title = label;
  return dot;
}
