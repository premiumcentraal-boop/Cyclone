import { Fragment, useMemo } from "react";
import { BotAvatar } from "./BotAvatar";
import { AgentMention, MentionText } from "./Mention";
import { CheckIcon, ClockIcon, MonitorIcon, WarningIcon } from "./Icons";
import { formatTime, isNearSameMinute, parseMessage } from "../types";
import type { Agent, ApprovalEvent, ComputerSession, ConversationDetail, Message, ParsedMessage } from "../types";

interface TimelineProps {
  conversation: ConversationDetail;
  agents: Agent[];
  onOpenAgent: (agent: Agent) => void;
  onOpenComputer: (session: ComputerSession) => void | Promise<void>;
  onDecideApproval: (approval: ApprovalEvent, decision: "approved" | "denied") => void;
}

export function MessageTimeline({ conversation, agents, onOpenAgent, onOpenComputer, onDecideApproval }: TimelineProps) {
  const entries = useMemo(() => conversation.messages.map((message, index) => ({
    message,
    parsed: parseMessage(message, agents),
    previous: conversation.messages[index - 1],
  })), [agents, conversation.messages]);

  if (!entries.length) return <EmptyConversation conversation={conversation} />;

  return <div className="timeline" aria-label={`${conversation.title} conversation`}>
    {entries.map(({ message, parsed, previous }) => <Fragment key={message.id}>
      {shouldShowTime(previous, message) && <TimeSeparator time={message.created_at} />}
      {parsed.handoff && <HandoffSeparator agentIds={parsed.handoff.fromAgentIds} agents={agents} onOpenAgent={onOpenAgent} />}
      <TimelineMessage message={message} parsed={parsed} agents={agents} isCrew={conversation.kind === "cluster"} onOpenAgent={onOpenAgent} onOpenComputer={onOpenComputer} onDecideApproval={onDecideApproval} />
    </Fragment>)}
  </div>;
}

function shouldShowTime(previous: Message | undefined, next: Message): boolean {
  if (!previous) return true;
  return !isNearSameMinute(previous.created_at, next.created_at);
}

function TimeSeparator({ time }: { time: string }) {
  return <div className="timeline__time"><span>{formatTime(time)}</span></div>;
}

function HandoffSeparator({ agentIds, agents, onOpenAgent }: { agentIds: string[]; agents: Agent[]; onOpenAgent: (agent: Agent) => void }) {
  const handoffAgents = agentIds.map((id) => agents.find((candidate) => candidate.id === id || candidate.slug === id)).filter((agent): agent is Agent => Boolean(agent));
  if (!handoffAgents.length) return null;
  return <div className="handoff-separator"><span>Messages from</span>{handoffAgents.map((agent, index) => <Fragment key={agent.id}><AgentMention agent={agent} onOpen={onOpenAgent} />{index < handoffAgents.length - 2 ? "," : index === handoffAgents.length - 2 ? " and" : ""}</Fragment>)}</div>;
}

function TimelineMessage({ message, parsed, agents, isCrew, onOpenAgent, onOpenComputer, onDecideApproval }: { message: Message; parsed: ParsedMessage; agents: Agent[]; isCrew: boolean; onOpenAgent: (agent: Agent) => void; onOpenComputer: (session: ComputerSession) => void | Promise<void>; onDecideApproval: (approval: ApprovalEvent, decision: "approved" | "denied") => void }) {
  const author = message.author_agent_id ? agents.find((agent) => agent.id === message.author_agent_id || agent.slug === message.author_agent_id) : undefined;
  const isHuman = message.author_type === "human";
  const isSystem = message.author_type === "system" || message.author_type === "automation";
  const shouldUseSystemEvent = isSystem && !parsed.computer && !parsed.approval && !parsed.routine && !parsed.handoff;

  if (shouldUseSystemEvent) return <SystemEvent message={message} />;
  if (parsed.routine) return <RoutineEvent name={parsed.routine.name} schedule={parsed.routine.schedule} status={parsed.routine.status} />;

  return <article className={`timeline-message ${isHuman ? "timeline-message--human" : ""} ${isCrew && !isHuman ? "timeline-message--crew" : ""}`}>
    {!isHuman && isCrew && author && <button type="button" className="timeline-message__author" onClick={() => onOpenAgent(author)}><BotAvatar agent={author} size={14} /><span>{author.name}</span></button>}
    {!isHuman && !isCrew && author && <div className="timeline-message__identity"><BotAvatar agent={author} size={16} /><span>{author.name}</span></div>}
    <div className={`timeline-message__bubble ${isHuman ? "timeline-message__bubble--human" : ""}`}>
      <div className="timeline-message__copy"><MentionText segments={parsed.text} agents={agents} onOpen={onOpenAgent} /></div>
      {parsed.computer && <ComputerTask session={parsed.computer} agent={author} onOpen={() => void onOpenComputer(parsed.computer!)} />}
      {parsed.approval && <ApprovalCard approval={parsed.approval} onDecide={onDecideApproval} />}
    </div>
    {isHuman && Boolean((message.metadata.reaction as string | undefined)) && <span className="reaction" aria-label="Message reaction">{String(message.metadata.reaction)}</span>}
  </article>;
}

function SystemEvent({ message }: { message: Message }) {
  return <div className="system-event"><span>{message.body}</span></div>;
}

function RoutineEvent({ name, schedule, status }: { name: string; schedule?: string; status?: string }) {
  return <div className="system-event system-event--routine"><span>{status === "updated" ? "Updated routine" : "Created routine"}</span><ClockIcon size={13} /><strong>{name}</strong>{schedule && <span className="system-event__schedule">{schedule}</span>}</div>;
}

function ComputerTask({ session, agent, onOpen }: { session: ComputerSession; agent?: Agent; onOpen: () => void }) {
  const done = session.status === "done";
  const waiting = session.status === "waiting_for_user";
  const unavailable = session.status === "unavailable" || session.status === "error";
  return <button type="button" className={`computer-task ${waiting ? "computer-task--waiting" : ""} ${unavailable ? "computer-task--unavailable" : ""}`} onClick={onOpen}>
    <span className="computer-task__header"><span><MonitorIcon size={15} /><strong>Computer</strong></span><span className={`computer-task__status ${done ? "computer-task__status--done" : waiting ? "computer-task__status--waiting" : unavailable ? "computer-task__status--unavailable" : ""}`}>{done ? "Done" : waiting ? "Needs you" : unavailable ? "Unavailable" : "Live"}</span></span>
    <span className="computer-task__instruction">{session.instruction || (agent ? `${agent.name} is preparing the computer.` : "Computer session available.")}</span>
    <span className="computer-task__preview" aria-hidden="true">
      {session.recentFrameUrl ? <img src={session.recentFrameUrl} alt="Recent agent computer frame" /> : <ComputerPreviewPlaceholder status={session.status} />}
      <span className="computer-task__preview-label">{waiting ? "Open computer to continue" : "Open computer"}</span>
    </span>
  </button>;
}

function ComputerPreviewPlaceholder({ status }: { status: ComputerSession["status"] }) {
  return <span className={`computer-preview computer-preview--${status}`}><span className="computer-preview__bar"><i /><i /><i /></span><span className="computer-preview__desk"><span className="computer-preview__window"><span className="computer-preview__windowbar" /><span className="computer-preview__lines"><i /><i /><i /></span></span><span className="computer-preview__cursor" /></span></span>;
}

function ApprovalCard({ approval, onDecide }: { approval: ApprovalEvent; onDecide: (approval: ApprovalEvent, decision: "approved" | "denied") => void }) {
  if (approval.status !== "pending") return <div className="approval-card approval-card--resolved"><CheckIcon size={14} /><span>{approval.status === "approved" ? "Approved" : "Not approved"}: {approval.capability}</span></div>;
  return <div className="approval-card"><span className="approval-card__title"><WarningIcon size={15} /><strong>Approval needed</strong></span><p>{approval.expected_effect || `${approval.capability} → ${approval.target}`}</p><span className="approval-card__target">{approval.target}</span><span className="approval-card__actions"><button type="button" onClick={() => onDecide(approval, "denied")}>Deny</button><button type="button" className="approval-card__allow" onClick={() => onDecide(approval, "approved")}>Allow once</button></span></div>;
}

function EmptyConversation({ conversation }: { conversation: ConversationDetail }) {
  return <div className="conversation-empty"><h2>{conversation.title}</h2><p>This conversation is ready. Tell the team what you need done.</p></div>;
}
