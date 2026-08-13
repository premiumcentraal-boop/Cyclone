import { useEffect, useState } from "react";
import { coreClient } from "../core-client";
import type { IntegrationState } from "../types";
import { SpinnerIcon } from "./Icons";

interface PluginsViewProps {
  onClose: () => void;
}

const PLUGIN_ICONS: Record<string, string> = {
  Hermes: "H",
  "Obsidian vault": "O",
  Workspace: "W",
  n8n: "n",
  Browser: "B",
};

export function PluginsView({ onClose }: PluginsViewProps) {
  const [integrations, setIntegrations] = useState<IntegrationState[] | null>(null);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    let disposed = false;
    coreClient.integrations().then(
      (result) => { if (!disposed) setIntegrations(result.integrations); },
      () => { if (!disposed) setIntegrations([]); },
    );
    return () => { disposed = true; };
  }, []);

  return <div className="conversation-surface" style={{ gridTemplateRows: "41px minmax(0, 1fr)" }}>
    <header className="conversation-header">
      <div className="conversation-header__identity"><strong>Plugins</strong></div>
      <button type="button" className="conversation-header__computer" aria-label="Close plugins" title="Back to chat" onClick={onClose}><span style={{ fontSize: 18, lineHeight: 1 }}>×</span></button>
    </header>
    {notice && <div className="quiet-notice" role="status"><span>{notice}</span><button type="button" onClick={() => setNotice("")}>×</button></div>}
    <div className="plugins-view">
      <div className="plugins-view__title">Integrations</div>
      <div className="plugins-view__list">
        {integrations === null && <div className="quiet-notice"><SpinnerIcon size={14} /><span>Checking integrations…</span></div>}
        {integrations !== null && integrations.length === 0 && <div className="quiet-notice"><span>No integrations are reachable right now.</span></div>}
        {integrations !== null && integrations.map((integration) => (
          <div className="plugin-row" key={integration.name}>
            <span className={`plugin-row__icon ${integration.available ? "plugin-row__icon--on" : ""}`}>{PLUGIN_ICONS[integration.name] ?? "•"}</span>
            <span className="plugin-row__copy"><strong>{integration.name}</strong><small>{integration.detail}</small></span>
            <span className={`plugin-row__state ${integration.available ? "" : "plugin-row__state--off"}`}>{integration.available ? "Connected" : "Off"}</span>
          </div>
        ))}
      </div>
    </div>
  </div>;
}
