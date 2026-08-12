import { BotAvatar, CrewAvatar } from "./BotAvatar";
import { formatSidebarTime } from "../types";
import type { Agent, ConversationSummary } from "../types";

export function ConversationRow({ conversation, agents, active, focused, onSelect }: { conversation: ConversationSummary; agents: Agent[]; active: boolean; focused?: boolean; onSelect: (conversation: ConversationSummary) => void }) {
  const memberAgents = conversation.kind === "cluster" ? agents.slice(0, 3) : agents.slice(0, 1);
  const agent = memberAgents[0];
  return <button type="button" className={`conversation-row ${active ? "conversation-row--active" : ""} ${focused ? "conversation-row--focused" : ""}`} onClick={() => onSelect(conversation)}>
    <span className="conversation-row__avatar">
      {conversation.kind === "cluster" ? <CrewAvatar agents={memberAgents} size={32} /> : agent ? <BotAvatar agent={agent} size={32} /> : <span className="conversation-row__fallback" />}
    </span>
    <span className="conversation-row__copy">
      <span className="conversation-row__line"><strong>{conversation.title}</strong><time>{formatSidebarTime(conversation.updated_at)}</time></span>
      <span className="conversation-row__preview">{conversation.latest_preview?.trim() || "No messages yet"}</span>
    </span>
  </button>;
}

export function ConversationRowSkeleton() {
  return <div className="conversation-row conversation-row--skeleton" aria-hidden="true"><span className="skeleton skeleton--avatar" /><span className="conversation-row__copy"><span className="skeleton skeleton--title" /><span className="skeleton skeleton--preview" /></span></div>;
}
