/**
 * Shortcuts that work the same on Windows and on a Mac (plan 53 R4): Ctrl on Windows and Linux, ⌘ on a Mac, and the
 * label Glass shows follows the computer it runs on ("Ctrl+K" or "⌘K").
 *
 * Chosen to stay clear of what browsers keep for themselves on Windows: Ctrl+J opens Downloads in Chrome and Edge, so
 * Cyber's panel is Ctrl+. (⌘. on a Mac). ⌘J / Ctrl+J still toggle it where the browser lets the page have the key.
 */
export type ShortcutId = "palette" | "panel";

export interface PlatformLike {
  platform?: string;
  userAgent?: string;
  userAgentData?: { platform?: string };
}

export function isMacLike(nav: PlatformLike | undefined = (globalThis as { navigator?: PlatformLike }).navigator): boolean {
  const platform = nav?.userAgentData?.platform || nav?.platform || nav?.userAgent || "";
  return /mac|iphone|ipad|ipod/i.test(platform);
}

const KEYS: Record<ShortcutId, string[]> = {
  palette: ["k"],
  panel: [".", "j"],
};

/** The key Glass shows for a shortcut: "⌘K" on a Mac, "Ctrl+K" elsewhere. */
export function shortcutLabel(id: ShortcutId, mac = isMacLike()): string {
  const key = KEYS[id][0].toUpperCase();
  return mac ? `⌘${key}` : `Ctrl+${key}`;
}

export interface ModKeyLike {
  key: string;
  ctrlKey?: boolean;
  metaKey?: boolean;
  altKey?: boolean;
  shiftKey?: boolean;
}

/** Which shortcut a key press is, if any: ⌘ on a Mac, Ctrl elsewhere; Alt or Shift held never match. */
export function shortcutOf(event: ModKeyLike, mac = isMacLike()): ShortcutId | null {
  const mod = mac ? event.metaKey && !event.ctrlKey : event.ctrlKey && !event.metaKey;
  if (!mod || event.altKey || event.shiftKey) return null;
  const key = String(event.key).toLowerCase();
  for (const id of Object.keys(KEYS) as ShortcutId[]) if (KEYS[id].includes(key)) return id;
  return null;
}
