export type DependencyStatus = "ok" | "degraded" | "unavailable" | "unknown";
export type AgentStatus = "offline" | "idle" | "thinking" | "working" | "waiting_for_user" | "human_takeover" | "done" | "error" | "blocked" | "waiting_for_approval" | string;
export type ComputerOwner = { type: "agent"; agentId: string } | { type: "human" } | { type: "idle" };

export interface HealthResponse {
  status: "ok" | "degraded";
  service: string;
  timestamp: string;
  dependencies: Record<string, { status: DependencyStatus; detail: string }>;
}

export interface Agent {
  id: string;
  slug: string;
  name: string;
  role: string;
  description: string;
  avatar_color: string;
  avatar_shape?: AgentAvatarShape;
  status: AgentStatus;
  provider?: string | null;
  model?: string | null;
  hermes_profile: string;
  workspace_path: string;
}

export type AgentAvatarShape = "round" | "blob" | "squircle" | "capsule" | "triangle" | "polygon" | "cloud" | "droplet" | "diamond" | "pebble";

export interface ConversationSummary {
  id: string;
  title: string;
  kind: "direct" | "cluster" | "telegram" | "routine" | string;
  project_key?: string | null;
  updated_at: string;
  latest_preview?: string | null;
  /** Actual agent identities for rendering the sidebar before opening the chat. */
  member_agents?: Agent[];
}

export type MessageKind =
  | "message"
  | "activity"
  | "task"
  | "handoff"
  | "approval"
  | "result"
  | "automation"
  | "system";

export interface Mention {
  id: string;
  mention_type: "agent" | "group" | "everyone" | "routine" | "connector";
  target_agent_id?: string | null;
  target_slug?: string | null;
  position_start?: number | null;
  position_end?: number | null;
}

export interface Message {
  id: string;
  conversation_id: string;
  task_id?: string | null;
  reply_to_message_id?: string | null;
  author_type: "human" | "agent" | "system" | "automation";
  author_agent_id?: string | null;
  author_name: string;
  kind: MessageKind;
  body: string;
  metadata: Record<string, unknown>;
  source: string;
  mentions?: Mention[];
  created_at: string;
}

export interface ConversationMember {
  display_name: string;
  member_type: "agent" | "human" | "system";
  member_role: string;
  agent?: Agent | null;
}

export interface ConversationDetail {
  id: string;
  title: string;
  kind: ConversationSummary["kind"];
  project_key?: string | null;
  hermes_conversation_key?: string | null;
  created_at: string;
  updated_at: string;
  members: ConversationMember[];
  messages: Message[];
}

export interface Task {
  id: string;
  status: string;
  title: string;
  objective: string;
  hermes_run_id?: string | null;
}

export interface AgentRunResponse {
  task: Task;
  user_message: Message;
  run?: { run_id: string; status: string } | null;
  status: "queued" | "started" | "blocked";
  detail: string;
}

export interface CoreEvent {
  id: string;
  type: string;
  conversation_id: string;
  occurred_at: string;
  payload: Record<string, unknown>;
}

export type StartupStage = "checking" | "starting" | "ready" | "degraded" | "unavailable";

export interface StartupState {
  stage: StartupStage;
  headline: string;
  detail: string;
  health?: HealthResponse;
}

export interface ComputerSession {
  id: string;
  agent_id: string;
  status: "idle" | "working" | "waiting_for_user" | "done" | "error" | "unavailable";
  instruction?: string | null;
  stream_url?: string | null;
  recent_frame_url?: string | null;
  owner: ComputerOwner;
  updated_at?: string | null;
}

export interface RoutineSummary {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  owner_agent_id: string | null;
  n8n_workflow_id: string | null;
  enabled: boolean;
}

export interface IntegrationState {
  name: string;
  available: boolean;
  detail: string;
}

export interface AttachmentRef {
  name: string;
  size?: number | null;
  url?: string | null;
  kind?: string;
}

export type MentionSegment =
  | { type: "text"; text: string }
  | { type: "agent_mention"; agentId: string; fallbackName?: string };

export interface ComposerMention {
  agent: Agent;
  start: number;
  end: number;
}

export interface RoutineEvent {
  id?: string;
  name: string;
  schedule?: string;
  status?: "created" | "updated" | "paused";
}

export interface HandoffEvent {
  fromAgentIds: string[];
  summary?: string;
}

export interface ApprovalEvent {
  id: string;
  status: "pending" | "approved" | "denied" | "expired" | "cancelled";
  capability: string;
  target: string;
  expected_effect?: string;
}

export interface ParsedMessage {
  text: MentionSegment[];
  handoff?: HandoffEvent;
  routine?: RoutineEvent;
  computer?: ComputerSession;
  approval?: ApprovalEvent;
}

export interface UserIdentity {
  displayName: string;
  initials: string;
}

export const DEFAULT_USER: UserIdentity = { displayName: "Administrator", initials: "AD" };

export function agentShape(agent: Agent): AgentAvatarShape {
  if (agent.avatar_shape) return agent.avatar_shape;
  const shapes: AgentAvatarShape[] = ["round", "triangle", "diamond", "pebble", "squircle"];
  let total = 0;
  for (const character of agent.slug) total += character.charCodeAt(0);
  return shapes[total % shapes.length];
}

export function isCrew(conversation: Pick<ConversationSummary, "kind">): boolean {
  return conversation.kind === "cluster";
}

export function agentByReference(agents: Agent[], reference?: string | null): Agent | undefined {
  if (!reference) return undefined;
  return agents.find((agent) => agent.id === reference || agent.slug === reference);
}

export function formatTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" }).format(date);
}

export function formatSidebarTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const now = new Date();
  const sameDay = now.toDateString() === date.toDateString();
  if (sameDay) return formatTime(value);
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  if (yesterday.toDateString() === date.toDateString()) return "Yesterday";
  return new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(date);
}

export function isNearSameMinute(left: string, right: string): boolean {
  const leftDate = new Date(left).getTime();
  const rightDate = new Date(right).getTime();
  return Math.abs(leftDate - rightDate) < 1000 * 60 * 4;
}

export function metadataString(metadata: Record<string, unknown>, key: string): string | undefined {
  const value = metadata[key];
  return typeof value === "string" ? value : undefined;
}

export function metadataStringArray(metadata: Record<string, unknown>, key: string): string[] {
  const value = metadata[key];
  return Array.isArray(value) && value.every((item) => typeof item === "string") ? value : [];
}

export function metadataRecord(metadata: Record<string, unknown>, key: string): Record<string, unknown> | undefined {
  const value = metadata[key];
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined;
}

export function parseMessage(message: Message, agents: Agent[] = []): ParsedMessage {
  const metadata = message.metadata;
  const segments: MentionSegment[] = [];
  const explicitMentions = metadataStringArray(metadata, "mention_agent_ids");
  const rawSegments = metadata.structured_content;
  if (Array.isArray(rawSegments)) {
    rawSegments.forEach((segment) => {
      if (!segment || typeof segment !== "object") return;
      const object = segment as Record<string, unknown>;
      if (object.type === "agent_mention" && typeof object.agent_id === "string") {
        segments.push({ type: "agent_mention", agentId: object.agent_id, fallbackName: typeof object.name === "string" ? object.name : undefined });
      } else if (typeof object.text === "string") {
        segments.push({ type: "text", text: object.text });
      }
    });
  }
  if (!segments.length && explicitMentions.length) {
    const pattern = new RegExp(`@(${explicitMentions.map((agentId) => {
      const agent = agents.find((candidate) => candidate.id === agentId || candidate.slug === agentId);
      return (agent?.name ?? agentId).replace(/[.*+?^${}()|[\\]\\]/g, "\\$&");
    }).join("|")})`, "g");
    let cursor = 0;
    for (const match of message.body.matchAll(pattern)) {
      const at = match.index ?? cursor;
      if (at > cursor) segments.push({ type: "text", text: message.body.slice(cursor, at) });
      const displayName = match[1];
      const agent = agents.find((candidate) => candidate.name === displayName || candidate.id === displayName || candidate.slug === displayName);
      const agentId = explicitMentions.find((id) => {
        const candidate = agents.find((item) => item.id === id || item.slug === id);
        return candidate?.name === displayName || id === displayName;
      }) ?? agent?.id ?? displayName;
      segments.push({ type: "agent_mention", agentId, fallbackName: agent?.name ?? displayName });
      cursor = at + match[0].length;
    }
    if (cursor < message.body.length) segments.push({ type: "text", text: message.body.slice(cursor) });
  }
  if (!segments.length) segments.push({ type: "text", text: message.body });

  const handoffAgentIds = metadataStringArray(metadata, "handoff_agent_ids");
  const routineRecord = metadataRecord(metadata, "routine");
  const computerRecord = metadataRecord(metadata, "computer");
  const approvalRecord = metadataRecord(metadata, "approval");

  const computer = computerRecord ? {
    id: typeof computerRecord.session_id === "string" ? computerRecord.session_id : `computer-${message.id}`,
    agent_id: typeof computerRecord.agent_id === "string" ? computerRecord.agent_id : message.author_agent_id ?? "",
    status: (typeof computerRecord.status === "string" ? computerRecord.status : "unavailable") as ComputerSession["status"],
    instruction: typeof computerRecord.instruction === "string" ? computerRecord.instruction : message.body,
    stream_url: typeof computerRecord.stream_url === "string" ? computerRecord.stream_url : undefined,
    recent_frame_url: typeof computerRecord.recent_frame_url === "string" ? computerRecord.recent_frame_url : undefined,
    owner: { type: "idle" },
    updated_at: message.created_at,
  } satisfies ComputerSession : undefined;

  return {
    text: segments,
    handoff: handoffAgentIds.length ? { fromAgentIds: handoffAgentIds, summary: metadataString(metadata, "handoff_summary") } : undefined,
    routine: routineRecord && typeof routineRecord.name === "string" ? {
      id: typeof routineRecord.id === "string" ? routineRecord.id : undefined,
      name: routineRecord.name,
      schedule: typeof routineRecord.schedule === "string" ? routineRecord.schedule : undefined,
      status: (typeof routineRecord.status === "string" ? routineRecord.status : "created") as RoutineEvent["status"],
    } : undefined,
    computer,
    approval: approvalRecord && typeof approvalRecord.id === "string" && typeof approvalRecord.status === "string" && typeof approvalRecord.capability === "string" && typeof approvalRecord.target === "string" ? {
      id: approvalRecord.id,
      status: approvalRecord.status as ApprovalEvent["status"],
      capability: approvalRecord.capability,
      target: approvalRecord.target,
      expected_effect: typeof approvalRecord.expected_effect === "string" ? approvalRecord.expected_effect : undefined,
    } : undefined,
  };
}
