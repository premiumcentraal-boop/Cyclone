/**
 * Which mood Cyber shows (plan 53 R4), from its live events and the dock summary. Pure, so the rules are tested once
 * and the dock and the panel agree:
 *
 *   offline   — Cyber cannot work (no key, no model, the day's limit) and nothing is running
 *   speak     — an answer is being written           (any conversation)
 *   working   — a tool is running
 *   think     — waiting for the model
 *   error     — a turn just failed                   (for a few seconds)
 *   success   — a turn just finished                 (for a few seconds)
 *   attention — an approval or a proposal waits for the owner
 *   listen    — the owner is writing to Cyber
 *   idle      — nothing to do
 */
import type { CyberMood } from "../ui/cyber/character.js";
import type { AiEvent } from "../services/aiStream.js";

type Busy = "think" | "working" | "speak";

export interface Activity {
  running: Record<string, Busy>;
  flash: { mood: "success" | "error"; until: number } | null;
}

export const SUCCESS_MS = 2500;
export const ERROR_MS = 3500;

export const emptyActivity = (): Activity => ({ running: {}, flash: null });

/** The next activity after one event (a new object; the old one is left as it was). */
export function applyEvent(activity: Activity, event: AiEvent, now: number): Activity {
  const id = event.conversationId;
  if (!id) return activity;
  const running = { ...activity.running };
  let flash = activity.flash;
  switch (event.type) {
    case "run.started":
    case "tool.finished":
      running[id] = "think";
      break;
    case "tool.started":
      running[id] = "working";
      break;
    case "text.delta":
      running[id] = "speak";
      break;
    case "run.finished":
      delete running[id];
      // A turn the owner stopped is not a success.
      if (event.data.detail !== "Stopped.") flash = { mood: "success", until: now + SUCCESS_MS };
      break;
    case "run.failed":
      delete running[id];
      flash = { mood: "error", until: now + ERROR_MS };
      break;
    default:
      return activity;
  }
  return { running, flash };
}

export interface MoodInputs {
  activity: Activity;
  /** From the dock summary; null while it has not loaded. */
  ready: boolean | null;
  needsYou: number;
  listening: boolean;
  now: number;
}

const BUSY_ORDER: Busy[] = ["speak", "working", "think"];

export function moodOf({ activity, ready, needsYou, listening, now }: MoodInputs): CyberMood {
  const busy = Object.values(activity.running);
  for (const mood of BUSY_ORDER) if (busy.includes(mood)) return mood;
  if (ready === false) return "offline";
  if (activity.flash && activity.flash.until > now) return activity.flash.mood;
  if (listening) return "listen";
  if (needsYou > 0) return "attention";
  return "idle";
}

/** When the mood must be looked at again without a new event (a flash running out); null when nothing is pending. */
export function nextCheck(activity: Activity, now: number): number | null {
  return activity.flash && activity.flash.until > now ? activity.flash.until - now : null;
}
