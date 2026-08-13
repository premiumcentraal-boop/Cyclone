import { FormEvent, useState } from "react";
import { CloseIcon } from "./Icons";
import type { Agent } from "../types";

function slugify(value: string): string {
  return value.toLowerCase().trim().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 63);
}

interface TeachTaskModalProps {
  agent?: Agent;
  onClose: () => void;
  onCreate: (routine: { slug: string; name: string; description: string; instructions: string; schedule?: string }) => Promise<void> | void;
}

export function TeachTaskModal({ agent, onClose, onCreate }: TeachTaskModalProps) {
  const [name, setName] = useState("");
  const [instructions, setInstructions] = useState("");
  const [schedule, setSchedule] = useState("");
  const [working, setWorking] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmedName = name.trim();
    const trimmedInstructions = instructions.trim();
    if (!trimmedName || !trimmedInstructions || working) return;
    setWorking(true);
    setError("");
    try {
      await onCreate({
        slug: slugify(trimmedName),
        name: trimmedName,
        description: trimmedInstructions,
        instructions: trimmedInstructions,
        schedule: schedule.trim() || undefined,
      });
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Cyclone could not teach this task.");
    } finally {
      setWorking(false);
    }
  }

  return <div className="teach-task-modal" role="dialog" aria-modal="true" aria-label="Teach a task">
    <div className="teach-task-modal__scrim" onClick={onClose} />
    <form className="teach-task-modal__window" onSubmit={(event) => void submit(event)}>
      <header><span><strong>Teach a task</strong><small>{agent ? `${agent.name} will own this routine.` : "Choose a conversation with an agent first."}</small></span><button type="button" aria-label="Close" onClick={onClose}><CloseIcon size={14} /></button></header>
      <label><span>Name</span><input value={name} onChange={(event) => setName(event.target.value)} placeholder="e.g. Weekly audit" autoFocus /></label>
      <label><span>What should happen?</span><textarea value={instructions} onChange={(event) => setInstructions(event.target.value)} placeholder="Teach the agent the recurring work and completion criteria…" rows={5} /></label>
      <label><span>Schedule <em>optional</em></span><input value={schedule} onChange={(event) => setSchedule(event.target.value)} placeholder="e.g. Every Monday at 9:00" /></label>
      {error && <p className="teach-task-modal__error">{error}</p>}
      <footer><button type="button" onClick={onClose}>Cancel</button><button type="submit" disabled={!agent || !name.trim() || !instructions.trim() || working}>{working ? "Teaching…" : "Create routine"}</button></footer>
    </form>
  </div>;
}
