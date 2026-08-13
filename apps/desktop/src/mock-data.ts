import type { Agent, ConversationDetail, ConversationSummary, HealthResponse } from "./types";

const now = new Date().toISOString();

export const demoHealth: HealthResponse = {
  status: "degraded",
  service: "cyclone-core",
  timestamp: now,
  dependencies: {
    database: { status: "ok", detail: "PostgreSQL is ready." },
    redis: { status: "ok", detail: "Redis is ready." },
    hermes: { status: "degraded", detail: "A model provider still needs local configuration." },
    vault: { status: "ok", detail: "CycloneVault is mounted." },
    workspace: { status: "ok", detail: "The shared workspace is mounted." },
  },
};

export const demoAgents: Agent[] = [
  {
    id: "chief", slug: "chief", name: "Chief", role: "Coordinator",
    description: "Coordinates work, delegates when useful, and reports verified outcomes.",
    avatar_color: "#70B7A7", avatar_shape: "round", status: "idle", hermes_profile: "default", workspace_path: "/workspace",
  },
  {
    id: "research", slug: "research", name: "Research", role: "Evidence specialist",
    description: "Finds primary sources, compares alternatives, and documents uncertainty.",
    avatar_color: "#6665E1", avatar_shape: "triangle", status: "idle", hermes_profile: "research", workspace_path: "/workspace",
  },
  {
    id: "developer", slug: "developer", name: "Developer", role: "Builder",
    description: "Implements and verifies work in the shared workspace.",
    avatar_color: "#E2A254", avatar_shape: "round", status: "idle", hermes_profile: "developer", workspace_path: "/workspace",
  },
  {
    id: "reviewer", slug: "reviewer", name: "Reviewer", role: "Verifier",
    description: "Checks acceptance criteria, tests, and real execution evidence.",
    avatar_color: "#8061E4", avatar_shape: "diamond", status: "idle", hermes_profile: "reviewer", workspace_path: "/workspace",
  },
];

export const demoConversations: ConversationSummary[] = [
  { id: "preview-chief", title: "Chief", kind: "direct", updated_at: now, latest_preview: "Cyclone Core is not connected yet.", is_pinned: false, is_unread: false },
  { id: "preview-crew", title: "Build crew", kind: "cluster", updated_at: now, latest_preview: "Waiting for a real task.", is_pinned: false, is_unread: false },
];

export const demoConversation: ConversationDetail = {
  id: "preview-chief",
  title: "Chief",
  kind: "direct",
  created_at: now,
  updated_at: now,
  members: [{ display_name: "Chief", member_type: "agent", member_role: "chief", agent: demoAgents[0] }],
  messages: [],
};

export function disconnectedConversation(): ConversationDetail {
  return {
    ...demoConversation,
    messages: [{
      id: "preview-system-offline",
      conversation_id: demoConversation.id,
      author_type: "system",
      author_name: "Cyclone",
      kind: "system",
      body: "Cyclone Core is offline. Start the local environment to load your agents and conversations.",
      metadata: {},
      source: "desktop",
      created_at: now,
    }],
  };
}

export function conversationForPreview(summary: ConversationSummary): ConversationDetail {
  if (summary.id === "preview-crew") {
    return {
      id: summary.id,
      title: summary.title,
      kind: "cluster",
      created_at: now,
      updated_at: now,
      members: demoAgents.map((agent) => ({ display_name: agent.name, member_type: "agent", member_role: agent.slug === "chief" ? "chief" : "member", agent })),
      messages: [],
    };
  }
  return disconnectedConversation();
}

export const DEMO_DISCLAIMER = "Preview mode has no agent data. Start Cyclone Core to load your real conversations.";
