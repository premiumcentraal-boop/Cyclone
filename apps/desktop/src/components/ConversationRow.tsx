import { BotAvatar, CrewAvatar } from "./BotAvatar";
import { formatSidebarTime } from "../types";
import type { Agent, ConversationSummary } from "../types";

export function ConversationRow({ conversation, agents, active, focused, onSelect }: { conversation: ConversationSummary; agents: Agent[]; active: boolean; focused?: boolean; onSelect: (conversation: ConversationSummary) => void }) {
  const crew = conversation.kind === "cluster" || conversation.kind === "group";
  const memberAgents = crew ? agents.slice(0, 3) : agents.slice(0, 1);
  const agent = memberAgents[0];
  return <button type="button" className={`conversation-row ${active ? "conversation-row--active" : ""} ${focused ? "conversation-row--focused" : ""}`} onClick={() => onSelect(conversation)}>
    <span className="conversation-row__avatar">
      {crew ? <CrewAvatar agents={memberAgents} size={32} /> : agent ? <BotAvatar agent={agent} size={32} /> : <span className="conversation-row__fallback" />}
    </span>
    <span className="conversation-row__copy">
      <span className="conversation-row__line"><strong>{conversation.title}</strong><time>{formatSidebarTime(conversation.updated_at)}</time></span>
      <span className="conversation-row__preview">{conversationPreview(conversation.latest_preview)}</span>
    </span>
  </button>;
}

/** Keep operational protocol useful without leaking it into the chat rail. */
function conversationPreview(value?: string | null): string {
  const preview = value?.trim();
  if (!preview) return "No messages yet";
  if (/^(?:@HANDOFF|DELEGATED-OK|queue event|internal envelope)/i.test(preview)) return "Work handed to a teammate";
  if (/^Automation event received:/i.test(preview)) return "Routine triggered";
  if (/\brun_[a-z0-9_-]+\b/i.test(preview)) return "Agent work updated";
  return preview.replace(/@HANDOFF\s+@[a-z0-9_-]+\s*:[^\r\n]*/gi, "Work handed to a teammate").trim() || "Work handed to a teammate";
}

export function ConversationRowSkeleton() {
  return <div className="conversation-row conversation-row--skeleton" aria-hidden="true"><span className="skeleton skeleton--avatar" /><span className="conversation-row__copy"><span className="skeleton skeleton--title" /><span className="skeleton skeleton--preview" /></span></div>;
}
