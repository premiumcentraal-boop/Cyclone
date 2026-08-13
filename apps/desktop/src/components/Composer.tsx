import { FormEvent, KeyboardEvent, useEffect, useMemo, useRef, useState } from "react";
import { AttachmentIcon, CloseIcon, MicrophoneIcon, PlusIcon, SendIcon } from "./Icons";
import { BotAvatar } from "./BotAvatar";
import { coreClient } from "../core-client";
import type { Agent, AttachmentRef, ComposerMention } from "../types";

interface ComposerProps {
  conversationName: string;
  agents: Agent[];
  disabled?: boolean;
  busy?: boolean;
  onSend: (message: string, attachments: AttachmentRef[], model: { provider: string | null; model: string | null }) => Promise<void> | void;
}

interface ModelOption {
  label: string;
  provider: string | null;
  model: string | null;
}

const MODEL_OPTIONS: ModelOption[] = [
  { label: "Auto", provider: null, model: null },
  { label: "deepseek-v4-flash", provider: "deepseek", model: "deepseek-v4-flash" },
  { label: "deepseek-v4-pro", provider: "deepseek", model: "deepseek-v4-pro" },
];

function mentionQuery(value: string, caret: number): { start: number; query: string } | undefined {
  const before = value.slice(0, caret);
  const match = before.match(/(?:^|\s)@([^\s@]*)$/);
  if (!match || match.index === undefined) return undefined;
  return { start: match.index + (match[0].startsWith(" ") ? 1 : 0), query: match[1].toLowerCase() };
}

export function Composer({ conversationName, agents, disabled = false, busy = false, onSend }: ComposerProps) {
  const [value, setValue] = useState("");
  const [caret, setCaret] = useState(0);
  const [openMenu, setOpenMenu] = useState(false);
  const [attachOpen, setAttachOpen] = useState(false);
  const [modelOpen, setModelOpen] = useState(false);
  const [modelChoice, setModelChoice] = useState<ModelOption>(MODEL_OPTIONS[0]);
  const [attachments, setAttachments] = useState<AttachmentRef[]>([]);
  const [uploading, setUploading] = useState(false);
  const [attachNotice, setAttachNotice] = useState("");
  const [urlDraft, setUrlDraft] = useState("");
  const [isRecording, setIsRecording] = useState(false);
  const textAreaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const currentQuery = mentionQuery(value, caret);
  const suggestions = useMemo(() => {
    if (!currentQuery) return [];
    return agents.filter((agent) => `${agent.name} ${agent.role}`.toLowerCase().includes(currentQuery.query)).slice(0, 6);
  }, [agents, currentQuery]);
  const showEveryone = Boolean(currentQuery && "everyone".startsWith(currentQuery.query));

  function insertMention(token: string) {
    if (!currentQuery) return;
    const before = value.slice(0, currentQuery.start);
    const after = value.slice(caret);
    const next = `${before}${token} ${after}`;
    setValue(next);
    const nextCaret = before.length + token.length + 1;
    setCaret(nextCaret);
    setOpenMenu(false);
    requestAnimationFrame(() => {
      textAreaRef.current?.focus();
      textAreaRef.current?.setSelectionRange(nextCaret, nextCaret);
    });
  }

  useEffect(() => {
    setOpenMenu(Boolean(currentQuery && (suggestions.length || showEveryone)));
  }, [currentQuery, suggestions.length, showEveryone]);

  useEffect(() => {
    const element = textAreaRef.current;
    if (!element) return;
    element.style.height = "0px";
    element.style.height = `${Math.min(element.scrollHeight, 110)}px`;
  }, [value]);

  async function attachFile(file: File | undefined) {
    if (!file) return;
    if (file.size > 15 * 1024 * 1024) {
      setAttachNotice("Files up to 15 MB are supported.");
      return;
    }
    setUploading(true);
    setAttachNotice("");
    try {
      const ref = await coreClient.uploadAttachment(file);
      setAttachments((current) => [...current, ref]);
      setAttachOpen(false);
    } catch (error) {
      setAttachNotice(error instanceof Error ? error.message : "Cyclone could not upload the file.");
    } finally {
      setUploading(false);
    }
  }

  async function attachClipboard() {
    setAttachNotice("");
    try {
      const text = (await navigator.clipboard.readText()).trim();
      if (/^https?:\/\/\S+$/i.test(text)) {
        setAttachments((current) => [...current, { name: text.slice(0, 80), url: text, kind: "url" }]);
        setAttachOpen(false);
      } else {
        setAttachNotice("The clipboard does not contain a link. Attach a file or paste a URL instead.");
      }
    } catch {
      setAttachNotice("Clipboard access is not available in this browser.");
    }
  }

  function attachUrl() {
    const url = urlDraft.trim();
    if (!/^https?:\/\/\S+$/i.test(url)) {
      setAttachNotice("Enter a full link, e.g. https://example.com/page");
      return;
    }
    setAttachments((current) => [...current, { name: url.slice(0, 80), url, kind: "url" }]);
    setUrlDraft("");
    setAttachNotice("");
    setAttachOpen(false);
  }

  function removeAttachment(index: number) {
    setAttachments((current) => current.filter((_, item) => item !== index));
  }

  async function submit(event?: FormEvent) {
    event?.preventDefault();
    const body = value.trim();
    if ((!body && !attachments.length) || busy || disabled) return;
    await onSend(body, attachments, { provider: modelChoice.provider, model: modelChoice.model });
    setValue("");
    setAttachments([]);
    setOpenMenu(false);
    setAttachOpen(false);
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void submit();
    }
    if (event.key === "Escape") {
      setOpenMenu(false);
      setAttachOpen(false);
      setModelOpen(false);
    }
  }

  function selectAgent(agent: Agent) {
    insertMention(`@${agent.name}`);
  }

  return <form className="composer" onSubmit={(event) => void submit(event)}>
    {openMenu && <div className="mention-picker" role="listbox" aria-label="Mention an agent">
      {suggestions.map((agent) => <button type="button" key={agent.id} role="option" onMouseDown={(event) => { event.preventDefault(); selectAgent(agent); }}><BotAvatar agent={agent} size={20} /><span><strong>{agent.name}</strong><small>{agent.role}</small></span></button>)}
      {showEveryone && <button type="button" role="option" onMouseDown={(event) => { event.preventDefault(); insertMention("@everyone"); }}><span className="mention-picker__everyone"><strong>Everyone</strong><small>Broadcast to the whole group</small></span></button>}
    </div>}
    {attachOpen && <div className="attach-pop" role="menu" aria-label="Add attachment">
      <button type="button" role="menuitem" onClick={() => { fileInputRef.current?.click(); }} disabled={uploading}><AttachmentIcon size={15} /><span><strong>{uploading ? "Uploading…" : "Attach file"}</strong><small>From your computer, up to 15 MB</small></span></button>
      <button type="button" role="menuitem" onClick={() => void attachClipboard()}><span className="attach-pop__icon"><svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true"><path d="M4.5 3.5h5v2h-5zM3 2.5h8v1.5H3zM2 4h10v8H2z" fill="none" stroke="currentColor" strokeWidth="1.1" /></svg></span><span><strong>Paste from clipboard</strong><small>Attach a copied link</small></span></button>
      <div className="attach-pop__url">
        <input value={urlDraft} onChange={(event) => setUrlDraft(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter") attachUrl(); }} placeholder="Paste a link…" aria-label="Link URL" />
        <button type="button" onClick={attachUrl} disabled={!urlDraft.trim()}>Add</button>
      </div>
      {attachNotice && <small className="attach-pop__notice" role="status">{attachNotice}</small>}
    </div>}
    <input ref={fileInputRef} type="file" hidden onChange={(event) => { void attachFile(event.target.files?.[0]); event.target.value = ""; }} />
    {attachments.length > 0 && <div className="composer__chips" aria-label="Attachments">
      {attachments.map((attachment, index) => <span key={`${attachment.url ?? attachment.name}-${index}`} className="composer__chip" title={attachment.url ?? attachment.name}><AttachmentIcon size={12} /><em>{attachment.name}</em><button type="button" aria-label={`Remove ${attachment.name}`} onClick={() => removeAttachment(index)}><CloseIcon size={10} /></button></span>)}
    </div>}
    <button type="button" className="composer__plus" title="Add attachment" aria-label="Add attachment" onClick={() => { setAttachOpen((current) => !current); setModelOpen(false); }} disabled={disabled}><PlusIcon size={16} /></button>
    <textarea ref={textAreaRef} value={value} disabled={disabled} onChange={(event) => { setValue(event.target.value); setCaret(event.target.selectionStart); }} onClick={(event) => setCaret(event.currentTarget.selectionStart)} onKeyUp={(event) => setCaret(event.currentTarget.selectionStart)} onKeyDown={handleKeyDown} placeholder={`Message ${conversationName}`} rows={1} aria-label={`Message ${conversationName}`} />
    <button type="button" className="composer__model" aria-label="Select model" aria-expanded={modelOpen} onClick={() => { setModelOpen((current) => !current); setAttachOpen(false); }} title={modelChoice.provider ? `${modelChoice.provider} / ${modelChoice.model}` : "Use the agent's default model"}>{modelChoice.label}</button>
    {modelOpen && <div className="model-pop" role="listbox" aria-label="Choose a model">
      {MODEL_OPTIONS.map((option) => <button type="button" key={option.label} role="option" aria-selected={modelChoice.label === option.label} className={modelChoice.label === option.label ? "selected" : ""} onClick={() => { setModelChoice(option); setModelOpen(false); }}><span><strong>{option.label}</strong><small>{option.provider ? `${option.provider} · ${option.model}` : "Resolved from the agent's configuration"}</small></span></button>)}
    </div>}
    {value.trim() || attachments.length ? <button type="submit" className="composer__send" disabled={disabled || busy} aria-label="Send message"><SendIcon size={15} /></button> : <button type="button" className={`composer__mic ${isRecording ? "composer__mic--recording" : ""}`} aria-label={isRecording ? "Stop recording" : "Start voice message"} onClick={() => setIsRecording((current) => !current)} disabled={disabled}><MicrophoneIcon size={14} /></button>}
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
