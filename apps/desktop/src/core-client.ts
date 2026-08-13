import type { Agent, AgentAvatarShape, AgentRunResponse, AttachmentRef, ComputerSession, ConversationDetail, ConversationSummary, HealthResponse, IntegrationState, RoutineSummary } from "./types";

const coreUrl = import.meta.env.VITE_CYCLONE_CORE_URL ?? "http://127.0.0.1:8787";

export class CoreClientError extends Error {
  constructor(message: string, public readonly status?: number) {
    super(message);
    this.name = "CoreClientError";
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${coreUrl}${path}`, {
      ...init,
      headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
    });
  } catch {
    throw new CoreClientError("Cyclone Core is not reachable.");
  }

  if (!response.ok) {
    const error = (await response.json().catch(() => ({}))) as { detail?: string };
    throw new CoreClientError(error.detail ?? `Cyclone Core returned HTTP ${response.status}.`, response.status);
  }
  return (await response.json()) as T;
}

export const coreClient = {
  health: () => request<HealthResponse>("/health"),
  listAgents: () => request<Agent[]>("/api/v1/agents"),
  createAgent: (agent: {
    slug: string;
    name: string;
    role?: string;
    description?: string;
    avatar_color?: string;
    avatar_shape?: AgentAvatarShape;
    provider?: string | null;
    model?: string | null;
  }) => request<Agent>("/api/v1/agents", { method: "POST", body: JSON.stringify(agent) }),
  listConversations: () => request<ConversationSummary[]>("/api/v1/conversations"),
  createConversation: (payload: { title: string; kind: "direct" | "group" | "cluster" | "routine"; project_key?: string | null; agent_slugs: string[] }) =>
    request<ConversationDetail>("/api/v1/conversations", { method: "POST", body: JSON.stringify(payload) }),
  conversation: (id: string) => request<ConversationDetail>(`/api/v1/conversations/${id}`),
  sendMessage: (conversationId: string, body: string, agentSlug: string, options: { provider?: string | null; model?: string | null; attachments?: AttachmentRef[]; replyToMessageId?: string | null } = {}) =>
    request<AgentRunResponse>(`/api/v1/conversations/${conversationId}/messages`, {
      method: "POST",
      body: JSON.stringify({
        body,
        agent_slug: agentSlug,
        run: true,
        provider: options.provider ?? undefined,
        model: options.model ?? undefined,
        reply_to_message_id: options.replyToMessageId ?? undefined,
        attachments: options.attachments ?? [],
      }),
    }),
  uploadAttachment: async (file: File): Promise<AttachmentRef> => {
    const form = new FormData();
    form.append("file", file, file.name);
    const response = await fetch(`${coreUrl}/api/v1/attachments`, { method: "POST", body: form });
    if (!response.ok) {
      const detail = await response.text().catch(() => "");
      throw new CoreClientError(`Upload failed (HTTP ${response.status})${detail ? `: ${detail}` : ""}`, response.status);
    }
    return response.json() as Promise<AttachmentRef>;
  },
  approve: (approvalId: string, decision: "approved" | "denied") =>
    request(`/api/v1/approvals/${approvalId}/decision`, {
      method: "POST",
      body: JSON.stringify({ decision }),
    }),
  computerSession: (agentId: string) => request<ComputerSession>(`/api/v1/agents/${agentId}/computer`),
  computerOwnership: (sessionId: string, owner: "human" | "agent" | "idle") => request<ComputerSession>(`/api/v1/computers/${sessionId}/ownership`, {
    method: "POST",
    body: JSON.stringify({ owner }),
  }),
  agentRoutines: (agentId: string) => request<RoutineSummary[]>(`/api/v1/agents/${agentId}/routines`),
  integrations: () => request<{ integrations: IntegrationState[] }>("/api/v1/integrations"),
  usersMe: () => request<{ id: string; display_name: string; initials: string; telegram_chat_id?: number | null }>("/api/v1/users/me"),
  runApproval: (runId: string, choice: "once" | "session" | "always" | "deny") =>
    request<{ status: string; run_id: string; choice: string }>(`/api/v1/runs/${runId}/approval`, {
      method: "POST",
      body: JSON.stringify({ choice }),
    }),
  eventsUrl: (conversationId: string) => `${coreUrl}/api/v1/conversations/${conversationId}/events`,
};

export { coreUrl };
