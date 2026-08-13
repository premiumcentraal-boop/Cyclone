import { useEffect, useState } from "react";
import { coreClient } from "../core-client";
import type { Agent, ComputerSession, RoutineSummary } from "../types";
import { BotAvatar } from "./BotAvatar";
import { MonitorIcon, SettingsIcon, CloseIcon, SpinnerIcon } from "./Icons";

interface AgentUtilityPanelProps {
  agent: Agent | undefined;
  conversationTitle: string;
  onClose: () => void;
  onOpenComputer: (session?: ComputerSession) => void;
  onEditProfile: (agent: Agent) => void;
}

export function AgentUtilityPanel({ agent, conversationTitle, onClose, onOpenComputer, onEditProfile }: AgentUtilityPanelProps) {
  const [session, setSession] = useState<ComputerSession | null | undefined>(undefined);
  const [routines, setRoutines] = useState<RoutineSummary[] | null>(null);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    let disposed = false;
    if (!agent) {
      setSession(null);
      setRoutines([]);
      return undefined;
    }
    setSession(undefined);
    setRoutines(null);
    coreClient.computerSession(agent.id).then(
      (found) => { if (!disposed) setSession(found); },
      () => { if (!disposed) setSession(null); },
    );
    coreClient.agentRoutines(agent.id).then(
      (found) => { if (!disposed) setRoutines(found); },
      () => { if (!disposed) setRoutines([]); },
    );
    return () => { disposed = true; };
  }, [agent]);

  const name = agent?.name ?? "Agent";

  return <aside className="utility-panel" aria-label={`${name} utility panel`}>
    <div className="utility-header">
      <button type="button" className="utility-header__edit" aria-label="Edit agent profile" title={`Edit ${name} profile`} onClick={() => agent && onEditProfile(agent)}><SettingsIcon size={15} /><span>Edit profile</span></button>
      <button type="button" aria-label="Close panel" title="Close panel" onClick={onClose}><CloseIcon size={15} /></button>
    </div>
    <div className="utility-body">
      {notice && <div className="quiet-notice" role="status"><span>{notice}</span><button type="button" onClick={() => setNotice("")}>×</button></div>}
      <section>
        <div className="screen-preview">
          <button
            type="button"
            className="screen-preview__frame"
            aria-label={`Open ${name}'s computer`}
            title="Open computer"
            onClick={() => onOpenComputer(session ?? undefined)}
          >
            {session === undefined
              ? <SpinnerIcon size={16} />
              : session && session.stream_url
                ? <iframe src={session.stream_url} title={`${name}'s screen`} sandbox="allow-same-origin allow-scripts" />
                : <MonitorIcon size={22} />}
          </button>
          <div className="screen-preview__caption">{name}'s screen</div>
        </div>
      </section>
      <section className="routines-section">
        {routines === null && <div className="routines-section__empty"><SpinnerIcon size={14} /></div>}
        {routines !== null && routines.length === 0 && (
          <>
            <p className="routines-section__empty">Routines are recurring tasks this agent runs on a schedule.</p>
            <button type="button" className="routines-section__create" onClick={() => setNotice("Routine creation arrives with the routines pass.")}>Create Routine</button>
          </>
        )}
        {routines !== null && routines.length > 0 && routines.map((routine) => (
          <div className="routine-row" key={routine.id}>
            <strong>{routine.name}</strong>
            <small>{routine.description || routine.slug}</small>
          </div>
        ))}
      </section>
      <section className="screen-preview" style={{ display: "none" }} />
    </div>
  </aside>;
}

export function UtilityPanelHeaderPreview({ agent }: { agent: Agent | undefined }) {
  return <div style={{ display: "flex", alignItems: "center", gap: 6, padding: "0 14px" }}>
    {agent ? <BotAvatar agent={agent} size={18} /> : <span className="conversation-header__placeholder" />}
    <span style={{ fontSize: 13, fontWeight: 550 }}>{agent?.name ?? "Agent"}</span>
  </div>;
}
