import type { Agent, AgentAvatarShape } from "./types";

// The ten-character palette from the real Grok Bot creator (DESIGN.md §22).
export const AGENT_PALETTE = [
  "#936439", // brown
  "#FF263C", // red
  "#FF6700", // orange
  "#FF9800", // amber
  "#00C972", // green
  "#00BCA6", // teal
  "#1084FE", // blue
  "#9159FE", // purple
  "#FF309B", // pink
  "#777777", // gray
] as const;

export const AGENT_SHAPES: AgentAvatarShape[] = [
  "round",
  "blob",
  "squircle",
  "capsule",
  "triangle",
  "polygon",
  "cloud",
  "droplet",
];

export function agentColor(agent: Pick<Agent, "avatar_color" | "slug">): string {
  if (/^#[0-9a-f]{6}$/i.test(agent.avatar_color)) return agent.avatar_color;
  let sum = 0;
  for (const character of agent.slug) sum += character.charCodeAt(0);
  return AGENT_PALETTE[sum % AGENT_PALETTE.length];
}

export function agentShapeFromSlug(slug: string): AgentAvatarShape {
  let sum = 0;
  for (const character of slug) sum += character.charCodeAt(0);
  return AGENT_SHAPES[sum % AGENT_SHAPES.length];
}

export function shade(hex: string, percent: number): string {
  const parsed = hex.replace("#", "");
  if (!/^[0-9a-f]{6}$/i.test(parsed)) return hex;
  const amount = Math.round(2.55 * percent);
  const channels = [0, 2, 4].map((offset) => Math.max(0, Math.min(255, Number.parseInt(parsed.slice(offset, offset + 2), 16) + amount)));
  return `#${channels.map((channel) => channel.toString(16).padStart(2, "0")).join("")}`;
}

export function agentInitials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  return words.slice(0, 2).map((word) => word.slice(0, 1)).join("").toUpperCase() || "?";
}

export function humanizeAgentState(status: string): string {
  const normalized = status.replaceAll("_", " ");
  if (normalized === "working") return "Working";
  if (normalized === "thinking") return "Thinking";
  if (normalized === "waiting for user") return "Waiting for you";
  if (normalized === "waiting for approval") return "Needs approval";
  if (normalized === "human takeover") return "You’re helping";
  if (normalized === "done") return "Done";
  if (normalized === "error") return "Needs attention";
  if (normalized === "offline") return "Offline";
  if (normalized === "blocked") return "Blocked";
  return normalized ? normalized.slice(0, 1).toUpperCase() + normalized.slice(1) : "Idle";
}

export function avatarEyesForState(status: string): "idle" | "thinking" | "working" | "waiting" | "done" | "error" {
  if (status === "thinking") return "thinking";
  if (status === "working") return "working";
  if (status === "waiting_for_user" || status === "waiting_for_approval" || status === "human_takeover") return "waiting";
  if (status === "done") return "done";
  if (status === "error" || status === "blocked" || status === "offline") return "error";
  return "idle";
}

export function agentSearchText(agent: Agent): string {
  return [agent.name, agent.role, agent.description, agent.status].join(" ").toLowerCase();
}
