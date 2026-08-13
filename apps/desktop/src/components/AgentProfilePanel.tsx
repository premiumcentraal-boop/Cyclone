import { FormEvent, useEffect, useState } from "react";
import { BotAvatar } from "./BotAvatar";
import { CloseIcon } from "./Icons";
import type { Agent } from "../types";

interface AgentProfilePanelProps {
  agent: Agent;
  saving?: boolean;
  onClose: () => void;
  onSave: (updates: { name: string; role: string; description: string }) => Promise<void> | void;
}

export function AgentProfilePanel({ agent, saving = false, onClose, onSave }: AgentProfilePanelProps) {
  const [name, setName] = useState(agent.name);
  const [role, setRole] = useState(agent.role);
  const [description, setDescription] = useState(agent.description);

  useEffect(() => {
    setName(agent.name);
    setRole(agent.role);
    setDescription(agent.description);
  }, [agent]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const nextName = name.trim();
    if (!nextName || saving) return;
    await onSave({ name: nextName, role: role.trim(), description: description.trim() });
  }

  return <aside className="agent-profile-panel" aria-label={`${agent.name} profile settings`}>
    <header className="agent-profile-panel__header">
      <button type="button" className="agent-profile-panel__back" onClick={onClose}>‹ <span>Settings</span></button>
      <button type="button" className="agent-profile-panel__close" aria-label="Close settings" onClick={onClose}><CloseIcon size={15} /></button>
    </header>
    <form className="agent-profile-panel__form" onSubmit={(event) => void submit(event)}>
      <div className="agent-profile-panel__identity">
        <BotAvatar agent={{ ...agent, name }} size={68} />
        <div><strong>{name || agent.name}</strong><small>{role || "Agent"}</small></div>
      </div>
      <label><span>Name</span><input value={name} maxLength={120} onChange={(event) => setName(event.target.value)} autoFocus /></label>
      <label><span>Title</span><input value={role} maxLength={120} onChange={(event) => setRole(event.target.value)} placeholder="Describe what your agent does" /></label>
      <label><span>Description</span><textarea value={description} maxLength={2000} onChange={(event) => setDescription(event.target.value)} placeholder="What this agent is for…" rows={5} /></label>
      <section className="agent-profile-panel__notifications" aria-label="Notifications">
        <span><strong>Notifications</strong><small>Get notified when this agent finishes or needs input</small></span>
        <input type="checkbox" role="switch" defaultChecked aria-label="Agent notifications" />
      </section>
      <div className="agent-profile-panel__actions"><button type="submit" disabled={!name.trim() || saving}>{saving ? "Saving…" : "Save changes"}</button></div>
    </form>
  </aside>;
}
