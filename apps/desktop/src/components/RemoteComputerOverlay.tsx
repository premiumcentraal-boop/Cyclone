import { useEffect, useRef, useState } from "react";
import { BotAvatar } from "./BotAvatar";
import { CloseIcon, ExpandIcon, MonitorIcon, ReconnectIcon, UserCursorIcon } from "./Icons";
import type { Agent, ComputerOwner, ComputerSession } from "../types";

interface RemoteComputerOverlayProps {
  session: ComputerSession;
  agent?: Agent;
  onClose: () => void;
  onChangeOwner: (owner: ComputerOwner) => void;
}

export function RemoteComputerOverlay({ session, agent, onClose, onChangeOwner }: RemoteComputerOverlayProps) {
  const [controlsVisible, setControlsVisible] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const frameRef = useRef<HTMLDivElement>(null);
  const isHuman = session.owner.type === "human";
  const liveStream = Boolean(session.stream_url);

  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handleEscape);
    return () => window.removeEventListener("keydown", handleEscape);
  }, [onClose]);

  function toggleFullscreen() {
    setFullscreen((current) => !current);
  }

  function assumeControl() {
    onChangeOwner({ type: "human" });
  }

  function returnControl() {
    if (agent) onChangeOwner({ type: "agent", agentId: agent.id });
    else onChangeOwner({ type: "idle" });
  }

  return <div className={`computer-overlay ${fullscreen ? "computer-overlay--fullscreen" : ""}`} role="dialog" aria-modal="true" aria-label={`${agent?.name ?? "Agent"} computer`} onMouseMove={() => setControlsVisible(true)} onMouseLeave={() => setControlsVisible(false)}>
    <div className="computer-overlay__scrim" onClick={onClose} />
    <section className="computer-overlay__window" ref={frameRef}>
      <div className={`computer-overlay__controls ${controlsVisible ? "computer-overlay__controls--visible" : ""}`}>
        <span className="computer-overlay__identity">{agent && <BotAvatar agent={agent} size={18} />}<span>{agent?.name ?? "Computer"}</span></span>
        <span className="computer-overlay__actions">
          <button type="button" title="Reconnect computer" aria-label="Reconnect computer"><ReconnectIcon size={15} /></button>
          <button type="button" title={fullscreen ? "Exit fullscreen" : "Fullscreen"} aria-label={fullscreen ? "Exit fullscreen" : "Fullscreen"} onClick={toggleFullscreen}><ExpandIcon size={15} /></button>
          <button type="button" title="Close computer" aria-label="Close computer" onClick={onClose}><CloseIcon size={15} /></button>
        </span>
      </div>
      <ComputerSurface session={session} liveStream={liveStream} isHuman={isHuman} />
      <div className={`computer-overlay__ownership ${controlsVisible || isHuman ? "computer-overlay__ownership--visible" : ""}`}>
        {isHuman ? <button type="button" className="computer-overlay__return" onClick={returnControl}><span className="computer-overlay__owner"><UserCursorIcon size={13} />You</span>Return control</button> : <button type="button" className="computer-overlay__take" onClick={assumeControl}>Take control</button>}
      </div>
    </section>
  </div>;
}

function ComputerSurface({ session, liveStream, isHuman }: { session: ComputerSession; liveStream: boolean; isHuman: boolean }) {
  if (liveStream && session.stream_url) {
    return <div className="computer-surface computer-surface--live"><iframe title="Live agent computer" src={session.stream_url} sandbox="allow-scripts allow-same-origin allow-forms" /><OwnershipCursor visible={isHuman} /></div>;
  }
  if (session.status === "unavailable" || session.status === "error") {
    return <div className="computer-surface computer-surface--unavailable"><MonitorIcon size={26} /><h2>Computer unavailable</h2><p>The agent computer has not provided a live session. Cyclone will not pretend a static preview is interactive.</p></div>;
  }
  return <div className="computer-surface computer-surface--placeholder"><div className="computer-desktop"><div className="computer-desktop__wallpaper" /><div className="computer-desktop__window"><div className="computer-desktop__windowbar"><span /><span /><span /><strong>{session.status === "waiting_for_user" ? "Continue sign-in" : "Agent workspace"}</strong></div><div className="computer-desktop__content">{session.status === "waiting_for_user" ? <><h2>Human checkpoint</h2><p>Complete the requested step, then return control so the agent can continue.</p><button type="button">Continue</button></> : <><h2>Preparing the workspace</h2><p>When a live browser or computer worker connects, its real session will appear here.</p></>}</div></div><div className="computer-desktop__dock"><i /><i /><i /><i /></div></div><OwnershipCursor visible={isHuman} /></div>;
}

function OwnershipCursor({ visible }: { visible: boolean }) {
  if (!visible) return null;
  return <span className="computer-cursor"><UserCursorIcon size={18} /><small>You</small></span>;
}

export function ComputerUnavailableOverlay({ onClose }: { onClose: () => void }) {
  return <div className="computer-overlay" role="dialog" aria-modal="true" aria-label="Computer unavailable"><div className="computer-overlay__scrim" onClick={onClose} /><section className="computer-overlay__window computer-overlay__window--notice"><button className="computer-overlay__notice-close" type="button" onClick={onClose}><CloseIcon size={15} /></button><MonitorIcon size={28} /><h2>No computer session yet</h2><p>Start a real browser or computer task from an agent. Cyclone will show a live or recent session here when one exists.</p></section></div>;
}
