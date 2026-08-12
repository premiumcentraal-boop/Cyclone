import { FormEvent, KeyboardEvent, useEffect, useMemo, useRef, useState } from "react";
import { AttachmentIcon, MicrophoneIcon, PlusIcon, SendIcon } from "./Icons";
import { BotAvatar } from "./BotAvatar";
import type { Agent, ComposerMention } from "../types";

interface ComposerProps {
  conversationName: string;
  agents: Agent[];
  disabled?: boolean;
  busy?: boolean;
  onSend: (message: string) => Promise<void> | void;
  onAttachment?: () => void;
}

function mentionQuery(value: string, caret: number): { start: number; query: string } | undefined {
  const before = value.slice(0, caret);
  const match = before.match(/(?:^|\s)@([^\s@]*)$/);
  if (!match || match.index === undefined) return undefined;
  return { start: match.index + (match[0].startsWith(" ") ? 1 : 0), query: match[1].toLowerCase() };
}

export function Composer({ conversationName, agents, disabled = false, busy = false, onSend, onAttachment }: ComposerProps) {
  const [value, setValue] = useState("");
  const [caret, setCaret] = useState(0);
  const [openMenu, setOpenMenu] = useState(false);
  const [isRecording, setIsRecording] = useState(false);
  const textAreaRef = useRef<HTMLTextAreaElement>(null);
  const currentQuery = mentionQuery(value, caret);
  const suggestions = useMemo(() => {
    if (!currentQuery) return [];
    return agents.filter((agent) => `${agent.name} ${agent.role}`.toLowerCase().includes(currentQuery.query)).slice(0, 6);
  }, [agents, currentQuery]);

  useEffect(() => {
    setOpenMenu(Boolean(currentQuery && suggestions.length));
  }, [currentQuery, suggestions.length]);

  useEffect(() => {
    const element = textAreaRef.current;
    if (!element) return;
    element.style.height = "0px";
    element.style.height = `${Math.min(element.scrollHeight, 110)}px`;
  }, [value]);

  async function submit(event?: FormEvent) {
    event?.preventDefault();
    const body = value.trim();
    if (!body || busy || disabled) return;
    await onSend(body);
    setValue("");
    setOpenMenu(false);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void submit();
    }
    if (event.key === "Escape") setOpenMenu(false);
  }

  function selectAgent(agent: Agent) {
    if (!currentQuery) return;
    const before = value.slice(0, currentQuery.start);
    const after = value.slice(caret);
    const next = `${before}@${agent.name} ${after}`;
    setValue(next);
    const nextCaret = before.length + agent.name.length + 3;
    setCaret(nextCaret);
    setOpenMenu(false);
    requestAnimationFrame(() => {
      textAreaRef.current?.focus();
      textAreaRef.current?.setSelectionRange(nextCaret, nextCaret);
    });
  }

  return <form className="composer" onSubmit={(event) => void submit(event)}>
    {openMenu && <div className="mention-picker" role="listbox" aria-label="Mention an agent">
      {suggestions.map((agent) => <button type="button" key={agent.id} role="option" onMouseDown={(event) => { event.preventDefault(); selectAgent(agent); }}><BotAvatar agent={agent} size={20} /><span><strong>{agent.name}</strong><small>{agent.role}</small></span></button>)}
    </div>}
    <button type="button" className="composer__plus" title="Add reference or file" aria-label="Add reference or file" onClick={onAttachment} disabled={disabled}><PlusIcon size={16} /></button>
    <textarea ref={textAreaRef} value={value} disabled={disabled} onChange={(event) => { setValue(event.target.value); setCaret(event.target.selectionStart); }} onClick={(event) => setCaret(event.currentTarget.selectionStart)} onKeyUp={(event) => setCaret(event.currentTarget.selectionStart)} onKeyDown={handleKeyDown} placeholder={`Message ${conversationName}`} rows={1} aria-label={`Message ${conversationName}`} />
    {value.trim() ? <button type="submit" className="composer__send" disabled={disabled || busy} aria-label="Send message"><SendIcon size={15} /></button> : <button type="button" className={`composer__mic ${isRecording ? "composer__mic--recording" : ""}`} aria-label={isRecording ? "Stop recording" : "Start voice message"} onClick={() => setIsRecording((current) => !current)} disabled={disabled}><MicrophoneIcon size={14} /></button>}
  </form>;
}

export function ComposerDisabled({ message }: { message: string }) {
  return <div className="composer composer--disabled"><AttachmentIcon size={15} /><span>{message}</span></div>;
}

export function parseComposerMentions(value: string, agents: Agent[]): ComposerMention[] {
  const matches: ComposerMention[] = [];
  const expression = /@([^@\n]+)/g;
  for (const match of value.matchAll(expression)) {
    const name = match[1].trim().toLowerCase();
    const agent = agents.find((candidate) => candidate.name.toLowerCase() === name);
    if (agent && match.index !== undefined) matches.push({ agent, start: match.index, end: match.index + match[0].length });
  }
  return matches;
}
