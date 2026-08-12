import { FormEvent, useState } from "react";
import { agentColor } from "../agent-visuals";
import { BotAvatar } from "./BotAvatar";
import { CloseIcon } from "./Icons";
import type { Agent, AgentAvatarShape } from "../types";

const SHAPES: AgentAvatarShape[] = ["round", "triangle", "diamond", "pebble", "squircle"];
const COLORS = ["#70B7A7", "#E2A254", "#6665E1", "#8061E4", "#5280E7", "#DC7945"];

function slugify(value: string): string {
  const slug = value.toLowerCase().trim().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 63);
  return slug;
}

interface NewConversationModalProps {
  agents: Agent[];
  onClose: () => void;
  onCreateConversation: (title: string, agentSlugs: string[], kind: "direct" | "cluster") => Promise<void>;
  onCreateAgent: (name: string, role: string, description: string, color: string, shape: AgentAvatarShape) => Promise<Agent | undefined>;
}

export function NewConversationModal({ agents, onClose, onCreateConversation, onCreateAgent }: NewConversationModalProps) {
  const [mode, setMode] = useState<"conversation" | "agent">("conversation");
  const [title, setTitle] = useState("");
  const [selected, setSelected] = useState<string[]>(["chief"]);
  const [kind, setKind] = useState<"direct" | "cluster">("direct");
  const [name, setName] = useState("");
  const [role, setRole] = useState("");
  const [description, setDescription] = useState("");
  const [color, setColor] = useState(COLORS[0]);
  const [shape, setShape] = useState<AgentAvatarShape>("round");
  const [error, setError] = useState("");
  const [working, setWorking] = useState(false);

  function toggleAgent(slug: string) {
    setSelected((current) => current.includes(slug) ? current.filter((item) => item !== slug) : [...current, slug]);
  }

  async function submitConversation(event: FormEvent) {
    event.preventDefault();
    const trimmed = title.trim();
    if (!trimmed || !selected.length || working) return;
    setWorking(true);
    setError("");
    try {
      await onCreateConversation(trimmed, selected, kind);
      onClose();
    } catch (error) {
      setError(error instanceof Error ? error.message : "Cyclone could not create the conversation.");
    } finally {
      setWorking(false);
    }
  }

  async function submitAgent(event: FormEvent) {
    event.preventDefault();
    const trimmedName = name.trim();
    if (!trimmedName || working) return;
    setWorking(true);
    setError("");
    try {
      const agent = await onCreateAgent(trimmedName, role.trim(), description.trim(), color, shape);
      if (agent) {
        setMode("conversation");
        setSelected((current) => current.includes(agent.slug) ? current : [...current, agent.slug]);
        setName("");
        setRole("");
        setDescription("");
      }
    } catch (error) {
      setError(error instanceof Error ? error.message : "Cyclone could not create the agent.");
    } finally {
      setWorking(false);
    }
  }

  return <div className="new-modal" role="dialog" aria-modal="true" aria-label="New conversation">
    <div className="new-modal__scrim" onClick={onClose} />
    <section className="new-modal__window">
      <button type="button" className="new-modal__close" aria-label="Close" onClick={onClose}><CloseIcon size={14} /></button>
      <div className="new-modal__tabs">
        <button type="button" className={mode === "conversation" ? "active" : ""} onClick={() => setMode("conversation")}>New conversation</button>
        <button type="button" className={mode === "agent" ? "active" : ""} onClick={() => setMode("agent")}>New agent</button>
      </div>

      {mode === "conversation" && <form onSubmit={(event) => void submitConversation(event)} className="new-modal__form">
        <label><span>Title</span><input value={title} onChange={(event) => setTitle(event.target.value)} placeholder="e.g. Website redesign" autoFocus /></label>
        <div className="new-modal__kind"><label><input type="radio" name="kind" checked={kind === "direct"} onChange={() => setKind("direct")} />One agent</label><label><input type="radio" name="kind" checked={kind === "cluster"} onChange={() => setKind("cluster")} />Crew</label></div>
        <div className="new-modal__agents">
          <span>Members</span>
          <div>{agents.map((agent) => <button type="button" key={agent.id} className={`agent-pick ${selected.includes(agent.slug) ? "agent-pick--selected" : ""}`} onClick={() => toggleAgent(agent.slug)}><BotAvatar agent={agent} size={22} /><span>{agent.name}</span><i>{selected.includes(agent.slug) ? "✓" : ""}</i></button>)}</div>
        </div>
        {error && <p className="new-modal__error">{error}</p>}
        <div className="new-modal__actions"><button type="submit" disabled={working || !title.trim() || !selected.length}>{working ? "Creating…" : "Create"}</button></div>
      </form>}

      {mode === "agent" && <form onSubmit={(event) => void submitAgent(event)} className="new-modal__form">
        <label><span>Name</span><input value={name} onChange={(event) => setName(event.target.value)} placeholder="e.g. Research" autoFocus /></label>
        <label><span>Role</span><input value={role} onChange={(event) => setRole(event.target.value)} placeholder="e.g. Evidence specialist" /></label>
        <label><span>Description</span><textarea value={description} onChange={(event) => setDescription(event.target.value)} placeholder="What this teammate does…" rows={2} /></label>
        <div className="new-modal__identity">
          <span>Character</span>
          <div className="identity-preview"><BotAvatar agent={{ id: "preview", slug: slugify(name) || "new-agent", name: name.trim() || "New agent", role, description, avatar_color: color, avatar_shape: shape, status: "idle", hermes_profile: "default", workspace_path: "/workspace" }} size={40} /></div>
          <div className="identity-colors">{COLORS.map((candidate) => <button key={candidate} type="button" aria-label={`Color ${candidate}`} className={color === candidate ? "selected" : ""} style={{ background: candidate }} onClick={() => setColor(candidate)} />)}</div>
          <div className="identity-shapes">{SHAPES.map((candidate) => <button key={candidate} type="button" className={shape === candidate ? "selected" : ""} onClick={() => setShape(candidate)}><BotAvatar agent={{ id: "shape", slug: "shape", name: "S", role: "", description: "", avatar_color: color, avatar_shape: candidate, status: "idle", hermes_profile: "default", workspace_path: "/workspace" }} size={24} /></button>)}</div>
        </div>
        {error && <p className="new-modal__error">{error}</p>}
        <div className="new-modal__actions"><button type="submit" disabled={working || !name.trim()}>{working ? "Creating…" : "Create agent"}</button></div>
      </form>}
    </section>
  </div>;
}

export { agentColor };
