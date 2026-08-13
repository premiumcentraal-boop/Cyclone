import { Fragment, useMemo, useState } from "react";
import { BotAvatar } from "./BotAvatar";
import { AgentMention, MentionText } from "./Mention";
import { CheckIcon, ChevronIcon, ClockIcon, MonitorIcon, WarningIcon } from "./Icons";
import { formatTime, isNearSameMinute, parseMessage } from "../types";
import type { Agent, ApprovalEvent, AttachmentRef, ComputerSession, ConversationDetail, Message, ParsedMessage } from "../types";

interface TimelineProps {
  conversation: ConversationDetail;
  agents: Agent[];
  onOpenAgent: (agent: Agent) => void;
  onOpenComputer: (session: ComputerSession) => void | Promise<void>;
  onDecideApproval: (approval: ApprovalEvent, decision: "approved" | "denied") => void;
  onResolveQuestion: (runId: string, choice: "once" | "session" | "always" | "deny") => void | Promise<void>;
}

const COLLAPSE_AFTER = 420;

export function MessageTimeline({ conversation, agents, onOpenAgent, onOpenComputer, onDecideApproval, onResolveQuestion }: TimelineProps) {
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
      <TimelineMessage message={message} parsed={parsed} agents={agents} isCrew={conversation.kind === "cluster" || conversation.kind === "group"} onOpenAgent={onOpenAgent} onOpenComputer={onOpenComputer} onDecideApproval={onDecideApproval} onResolveQuestion={onResolveQuestion} />
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

function TimelineMessage({ message, parsed, agents, isCrew, onOpenAgent, onOpenComputer, onDecideApproval, onResolveQuestion }: { message: Message; parsed: ParsedMessage; agents: Agent[]; isCrew: boolean; onOpenAgent: (agent: Agent) => void; onOpenComputer: (session: ComputerSession) => void | Promise<void>; onDecideApproval: (approval: ApprovalEvent, decision: "approved" | "denied") => void; onResolveQuestion: (runId: string, choice: "once" | "session" | "always" | "deny") => void | Promise<void> }) {
  const author = message.author_agent_id ? agents.find((agent) => agent.id === message.author_agent_id || agent.slug === message.author_agent_id) : undefined;
  const isHuman = message.author_type === "human";
  const isSystem = message.author_type === "system" || message.author_type === "automation";
  const shouldUseSystemEvent = isSystem && !parsed.computer && !parsed.approval && !parsed.routine && !parsed.handoff;
  const question = message.metadata.question_card ? {
    question: typeof message.metadata.question === "string" ? message.metadata.question : message.body,
    choices: Array.isArray(message.metadata.choices) ? message.metadata.choices.filter((choice): choice is string => typeof choice === "string") : ["once", "deny"],
    runId: typeof message.metadata.hermes_run_id === "string" ? message.metadata.hermes_run_id : "",
    dismissed: Boolean(message.metadata.question_dismissed),
  } : undefined;
  const attachments = Array.isArray(message.metadata.attachments) ? (message.metadata.attachments as AttachmentRef[]) : undefined;

  if (shouldUseSystemEvent) return <SystemEvent message={message} />;
  if (parsed.routine) return <RoutineEvent name={parsed.routine.name} schedule={parsed.routine.schedule} status={parsed.routine.status} />;

  return <article className={`timeline-message ${isHuman ? "timeline-message--human" : ""} ${isCrew && !isHuman ? "timeline-message--crew" : ""}`}>
    {!isHuman && isCrew && author && <button type="button" className="timeline-message__author" onClick={() => onOpenAgent(author)}><BotAvatar agent={author} size={14} /><span>{author.name}</span></button>}
    {!isHuman && !isCrew && author && <div className="timeline-message__identity"><BotAvatar agent={author} size={16} /><span>{author.name}</span></div>}
    <div className={`timeline-message__bubble ${isHuman ? "timeline-message__bubble--human" : ""}`}>
      <div className="timeline-message__copy"><CollapsibleText text={parsed.text} agents={agents} onOpen={onOpenAgent} /></div>
      {attachments && attachments.map((attachment) => <FileCard key={`${attachment.name}-${attachment.size ?? ""}`} attachment={attachment} />)}
      {parsed.computer && <ComputerTask session={parsed.computer} agent={author} onOpen={() => void onOpenComputer(parsed.computer!)} />}
      {parsed.approval && <ApprovalCard approval={parsed.approval} onDecide={onDecideApproval} />}
      {question && <QuestionCard question={question.question} choices={question.choices} runId={question.runId} dismissed={question.dismissed} onResolve={(choice) => void onResolveQuestion(question.runId, choice)} />}
    </div>
    {isHuman && Boolean((message.metadata.reaction as string | undefined)) && <span className="reaction" aria-label="Message reaction">{String(message.metadata.reaction)}</span>}
  </article>;
}

function CollapsibleText({ text, agents, onOpen }: { text: ParsedMessage["text"]; agents: Agent[]; onOpen: (agent: Agent) => void }) {
  const [expanded, setExpanded] = useState(false);
  const rawLength = text.reduce((total, segment) => total + (segment.type === "text" ? segment.text.length : (segment as { fallbackName?: string }).fallbackName?.length ?? 0), 0);
  const collapsible = rawLength > COLLAPSE_AFTER;
  const visible = collapsible && !expanded ? text.slice(0, Math.max(1, Math.floor(text.length * 0.6))) : text;
  return <>
    <MentionText segments={visible} agents={agents} onOpen={onOpen} />
    {collapsible && <button type="button" className={`message-collapse ${expanded ? "message-collapse--open" : ""}`} onClick={() => setExpanded((current) => !current)}>{expanded ? "Show less" : "Show more"}<ChevronIcon size={12} /></button>}
  </>;
}

function FileCard({ attachment }: { attachment: AttachmentRef }) {
  const size = attachment.size ? `${Math.max(1, Math.round(attachment.size / 1024))} KB` : "";
  return <div className="file-card">
    <span className="file-card__icon"><svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true"><path d="M3 1.5h5.2L11 4.3v8.2H3z" fill="none" stroke="currentColor" strokeWidth="1.2" /><path d="M8 1.5v3h3" fill="none" stroke="currentColor" strokeWidth="1.2" /></svg></span>
    <span className="file-card__copy"><strong>{attachment.name}</strong><small>{size}</small></span>
  </div>;
}

const QUESTION_LABELS: Record<string, string> = {
  once: "Allow once",
  session: "Allow this session",
  always: "Always allow",
  deny: "Deny",
};

function QuestionCard({ question, choices, runId, dismissed, onResolve }: { question: string; choices: string[]; runId: string; dismissed: boolean; onResolve: (choice: "once" | "session" | "always" | "deny") => void }) {
  const [custom, setCustom] = useState("");
  const [resolved, setResolved] = useState("");
  const [hidden, setHidden] = useState(false);
  if (hidden || dismissed) return null;
  const labels = choices.filter((choice) => choice in QUESTION_LABELS);
  function choose(choice: string) {
    if (!(choice in QUESTION_LABELS)) return;
    setResolved(QUESTION_LABELS[choice]);
    onResolve(choice as "once" | "session" | "always" | "deny");
  }
  if (resolved) return <div className="question-card question-card--resolved"><CheckIcon size={14} /><span>Answered: {resolved}</span></div>;
  return <div className="question-card">
    <div className="question-card__head"><strong>{question}</strong><button type="button" className="question-card__dismiss" aria-label="Dismiss question" title="Dismiss" onClick={() => setHidden(true)}>×</button></div>
    {labels.length > 0 && <div className="question-card__choices" role="group" aria-label="Answer choices">
      {labels.map((choice, index) => <button key={choice} type="button" className="question-card__choice" onClick={() => choose(choice)}><span className="question-card__key">{String.fromCharCode(65 + index)}</span><span>{QUESTION_LABELS[choice]}</span></button>)}
    </div>}
    <input
      className="question-card__custom"
      placeholder="Type your own answer"
      aria-label="Type your own answer"
      value={custom}
      onChange={(event) => setCustom(event.target.value)}
      onKeyDown={(event) => {
        if (event.key === "Enter" && custom.trim()) {
          setResolved("Your answer");
          if (runId) onResolve("once");
          setCustom("");
        }
      }}
    />
  </div>;
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
      {session.recent_frame_url ? <img src={session.recent_frame_url} alt="Recent agent computer frame" /> : <ComputerPreviewPlaceholder status={session.status} />}
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
