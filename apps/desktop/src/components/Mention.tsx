import type { CSSProperties } from "react";
import { BotAvatar } from "./BotAvatar";
import type { Agent, MentionSegment } from "../types";

export function AgentMention({ agent, fallbackName, onOpen }: { agent?: Agent; fallbackName?: string; onOpen?: (agent: Agent) => void }) {
  if (!agent) return <span className="agent-mention agent-mention--missing">{fallbackName ?? "Agent"}</span>;
  return <button type="button" className="agent-mention" style={{ "--mention-color": agent.avatar_color } as CSSProperties} onClick={() => onOpen?.(agent)} title={`Open ${agent.name}`}>
    <BotAvatar agent={agent} size={14} />
    <span>{agent.name}</span>
  </button>;
}

export function MentionText({ segments, agents, onOpen }: { segments: MentionSegment[]; agents: Agent[]; onOpen?: (agent: Agent) => void }) {
  return <>
    {segments.map((segment, index) => {
      if (segment.type === "text") return <span key={`text-${index}`} className="message-copy__text">{segment.text}</span>;
      const agent = agents.find((candidate) => candidate.id === segment.agentId || candidate.slug === segment.agentId);
      return <AgentMention key={`mention-${segment.agentId}-${index}`} agent={agent} fallbackName={segment.fallbackName} onOpen={onOpen} />;
    })}
  </>;
}
