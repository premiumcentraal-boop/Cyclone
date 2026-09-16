import type { DesktopService, Layer2Status } from "../services/types.js";

export const INSTAGRAM_ANDROID_PACKAGE = "com.instagram.android";
export const INSTAGRAM_PRESET_SOURCE_REPOSITORY = "kevinbadi/Kevs-IOS-Agents";
export const INSTAGRAM_PRESET_SOURCE_REF = "b909752df7af7a595714ed660af7cc971ec408d5";
export const INSTAGRAM_LAYER2_GOAL_MAX = 500;

export type InstagramPresetId = "warmup" | "engage-following" | "cold-dms" | "post";
export type InstagramPersonality = "skimmer" | "casual" | "engaged" | "dialed";
export type InstagramPostDestination = "draft" | "publish";

export interface InstagramPresetDefinition {
  id: InstagramPresetId;
  sourceTaskType: "doomscroll" | "doomscroll-following" | "cold-dms" | "post";
  title: string;
  description: string;
  workspaceId: string;
  consequential: boolean;
}

export interface InstagramPresetParams {
  account?: string;
  durationMinutes?: number;
  personality?: InstagramPersonality;
  likeEnabled?: boolean;
  commentEnabled?: boolean;
  commentText?: string;
  handles?: string[];
  message?: string;
  cycles?: number;
  destination?: InstagramPostDestination;
  caption?: string;
  mediaInstructions?: string;
  musicUrl?: string;
  publishConfirmed?: boolean;
}

export interface InstagramPresetInvocation {
  preset: InstagramPresetDefinition;
  appPackage: typeof INSTAGRAM_ANDROID_PACKAGE;
  goal: string;
  sourceRepository: typeof INSTAGRAM_PRESET_SOURCE_REPOSITORY;
  sourceRef: typeof INSTAGRAM_PRESET_SOURCE_REF;
}

export const INSTAGRAM_PRESETS: readonly InstagramPresetDefinition[] = [
  {
    id: "warmup",
    sourceTaskType: "doomscroll",
    title: "Instagram warmup",
    description: "Browse Home with personality-based pacing and optional verified engagement.",
    workspaceId: "instagram-warmup",
    consequential: false,
  },
  {
    id: "engage-following",
    sourceTaskType: "doomscroll-following",
    title: "Engage following",
    description: "Browse Following with the same bounded pacing and engagement controls.",
    workspaceId: "instagram-following",
    consequential: false,
  },
  {
    id: "cold-dms",
    sourceTaskType: "cold-dms",
    title: "Cold DMs",
    description: "Send a verified message to an explicit bounded recipient list.",
    workspaceId: "instagram-cold-dms",
    consequential: true,
  },
  {
    id: "post",
    sourceTaskType: "post",
    title: "Instagram post",
    description: "Create a verified draft or publish only after explicit confirmation.",
    workspaceId: "instagram-post",
    consequential: true,
  },
] as const;

const PRESET_BY_ID = new Map(INSTAGRAM_PRESETS.map((preset) => [preset.id, preset]));
const PERSONAS = new Set<InstagramPersonality>(["skimmer", "casual", "engaged", "dialed"]);

export function defaultInstagramPresetParams(id: InstagramPresetId): InstagramPresetParams {
  if (id === "warmup" || id === "engage-following") {
    return { durationMinutes: 15, personality: "casual", likeEnabled: true, commentEnabled: false };
  }
  if (id === "cold-dms") return { handles: [], message: "", cycles: 1 };
  return { destination: "draft", caption: "", mediaInstructions: "", publishConfirmed: false };
}

export function buildInstagramPresetInvocation(
  id: InstagramPresetId,
  raw: InstagramPresetParams,
): InstagramPresetInvocation {
  const preset = PRESET_BY_ID.get(id);
  if (!preset) throw new Error("Unknown Instagram preset");
  const account = cleanAccount(raw.account);
  const goal = id === "warmup" || id === "engage-following"
    ? engagementGoal(id, raw, account)
    : id === "cold-dms"
      ? coldDmGoal(raw, account)
      : postGoal(raw, account);
  return {
    preset,
    appPackage: INSTAGRAM_ANDROID_PACKAGE,
    goal,
    sourceRepository: INSTAGRAM_PRESET_SOURCE_REPOSITORY,
    sourceRef: INSTAGRAM_PRESET_SOURCE_REF,
  };
}

/**
 * Queue only: One stores the goal in Android Layer 2 / Up next. Actual UI actions still cross the
 * phone's canonical executor, verification and GATE paths. This function never sends ADB/shell input.
 */
export async function queueInstagramPreset(
  service: DesktopService,
  deviceId: string,
  invocation: InstagramPresetInvocation,
): Promise<Layer2Status> {
  if (!deviceId.trim()) throw new Error("Choose a trusted phone first");
  if (!service.listLayer2Workspaces || !service.layer2Workspace) {
    throw new Error("Instagram presets require Cyclone One Layer 2 support");
  }
  const current = await service.listLayer2Workspaces(deviceId);
  const existing = current.workspaces.find((workspace) => workspace.id === invocation.preset.workspaceId);
  if (existing && existing.appPackage !== invocation.appPackage) {
    throw new Error("Preset workspace exists with a different Android package");
  }
  if (!existing) {
    await service.layer2Workspace(deviceId, "register", {
      id: invocation.preset.workspaceId,
      label: invocation.preset.title,
      appPackage: invocation.appPackage,
      androidUserId: 0,
      displayId: 0,
    });
  }
  return service.layer2Workspace(deviceId, "arm", {
    id: invocation.preset.workspaceId,
    goal: invocation.goal,
  });
}

function engagementGoal(id: "warmup" | "engage-following", raw: InstagramPresetParams, account?: string): string {
  const duration = boundedInteger(raw.durationMinutes, 15, 1, 180, "Duration");
  const personality = raw.personality ?? "casual";
  if (!PERSONAS.has(personality)) throw new Error("Unknown Instagram personality");
  const comments = raw.commentEnabled === true;
  const commentText = String(raw.commentText ?? "").trim();
  if (comments && !commentText) throw new Error("Comment text is required when comments are enabled");
  if (commentText.length > 150) throw new Error("Comment text must be 150 characters or fewer");
  const feed = id === "engage-following" ? "Following" : "Home";
  const commentRule = comments
    ? `Comment exactly ${quote(commentText)}; comment is SEND, require phone GATE and verify it.`
    : "No comments.";
  return fitGoal([
    `IG ${id}. Open ${INSTAGRAM_ANDROID_PACKAGE}${account ? ` as @${account}` : ""}.`,
    `Browse ${feed} for ${duration}m, ${personality} pacing with varied dwell/scroll.`,
    raw.likeEnabled === false ? "No likes." : "Likes allowed; verify like state.",
    commentRule,
    "Re-observe after actions; no blind double-clicks. Stop on login/challenge/permission/human boundary.",
  ]);
}

function coldDmGoal(raw: InstagramPresetParams, account?: string): string {
  const handles = normalizeHandles(raw.handles ?? []);
  if (handles.length === 0) throw new Error("Add at least one Instagram handle");
  if (handles.length > 25) throw new Error("Cold DMs are limited to 25 explicit handles per preset run");
  const message = String(raw.message ?? "").trim();
  if (!message) throw new Error("DM message is required");
  if (message.length > 240) throw new Error("For the One preset queue, keep the DM message to 240 characters or fewer");
  const cycles = boundedInteger(raw.cycles, 1, 1, 10, "Cycles");
  return fitGoal([
    `IG cold-dms. Open ${INSTAGRAM_ANDROID_PACKAGE}${account ? ` as @${account}` : ""}.`,
    `Recipients:${handles.map((handle) => `@${handle}`).join(",")}; cycles:${cycles}. Message exactly:${quote(message)}.`,
    "Before each SEND verify recipient/thread. Every DM requires phone GATE/human confirmation.",
    "After Send verify recipient/thread plus sent-state; never blind-retry. Stop on ambiguity/challenge/unavailable target.",
  ]);
}

function postGoal(raw: InstagramPresetParams, account?: string): string {
  const destination = raw.destination ?? "draft";
  if (destination !== "draft" && destination !== "publish") throw new Error("Post destination must be draft or publish");
  if (destination === "publish" && raw.publishConfirmed !== true) {
    throw new Error("Publishing requires explicit confirmation; choose draft or confirm publish");
  }
  const caption = String(raw.caption ?? "").trim();
  if (caption.length > 2200) throw new Error("Caption must be 2200 characters or fewer");
  const media = String(raw.mediaInstructions ?? "").trim();
  if (!media) throw new Error("Describe which 1–3 phone media items should be selected");
  const musicUrl = String(raw.musicUrl ?? "").trim();
  if (musicUrl && !/^https:\/\/(?:www\.)?instagram\.com\//i.test(musicUrl)) {
    throw new Error("Music URL must be an HTTPS Instagram URL");
  }
  const resultRule = destination === "publish"
    ? "PUBLISH requires phone SEND/GATE; after action verify posted-state. Never blind-retry publish."
    : "Save DRAFT; verify saved/draft-state and never publish.";
  return fitGoal([
    `IG post. Open ${INSTAGRAM_ANDROID_PACKAGE}${account ? ` as @${account}` : ""}.`,
    `Select 1-3 phone media matching:${quote(media)}; pause if ambiguous.`,
    caption ? `Caption exactly:${quote(caption)}.` : "Caption empty.",
    musicUrl ? `Music:${musicUrl}.` : "No new music.",
    resultRule,
    "Verify picker selection and each Next transition.",
  ]);
}

function fitGoal(parts: string[]): string {
  const goal = parts.join(" ").replace(/\s+/g, " ").trim();
  if (goal.length > INSTAGRAM_LAYER2_GOAL_MAX) {
    throw new Error("Preset details exceed the phone's 500-character Up next contract; shorten handles, message, caption or media description");
  }
  return goal;
}

function cleanAccount(value?: string): string | undefined {
  const clean = String(value ?? "").trim().replace(/^@/, "");
  if (!clean) return undefined;
  if (!/^[A-Za-z0-9._]{1,30}$/.test(clean)) throw new Error("Instagram account must be a valid handle");
  return clean;
}

function normalizeHandles(values: string[]): string[] {
  const seen = new Set<string>();
  for (const value of values) {
    for (const token of String(value).split(/[\s,]+/)) {
      const handle = token.trim().replace(/^@/, "").toLowerCase();
      if (!handle) continue;
      if (!/^[a-z0-9._]{1,30}$/.test(handle)) throw new Error(`Invalid Instagram handle: ${token}`);
      seen.add(handle);
    }
  }
  return [...seen];
}

function boundedInteger(value: number | undefined, fallback: number, min: number, max: number, label: string): number {
  const selected = value == null ? fallback : Number(value);
  if (!Number.isInteger(selected) || selected < min || selected > max) {
    throw new Error(`${label} must be an integer from ${min} to ${max}`);
  }
  return selected;
}

function quote(value: string): string {
  return `“${value.replace(/[\r\n]+/g, " ").trim()}”`;
}
