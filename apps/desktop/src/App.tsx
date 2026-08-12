import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { coreClient, CoreClientError } from "./core-client";
import { demoAgents, demoConversations, disconnectedConversation } from "./mock-data";
import { BotAvatar, CrewAvatar } from "./components/BotAvatar";
import { Composer } from "./components/Composer";
import { ConversationRow, ConversationRowSkeleton } from "./components/ConversationRow";
import { MonitorIcon, PanelIcon, SearchIcon, SpinnerIcon } from "./components/Icons";
import { MessageTimeline } from "./components/MessageTimeline";
import { NewConversationModal } from "./components/NewConversationModal";
import { ComputerUnavailableOverlay, RemoteComputerOverlay } from "./components/RemoteComputerOverlay";
import { TitleBar } from "./components/TitleBar";
import type { Agent, ApprovalEvent, ComputerOwner, ComputerSession, ConversationDetail, ConversationSummary, HealthResponse, StartupState, UserIdentity } from "./types";
import { DEFAULT_USER } from "./types";
import { handleWindowAction } from "./window-controls";
import "./styles.css";

const PREVIEW_CONVERSATION_IDS = new Set(["preview-chief", "preview-crew"]);
const identity: UserIdentity = DEFAULT_USER;

type DataMode = "loading" | "live" | "disconnected";

function App() {
  const [mode, setMode] = useState<DataMode>("loading");
  const [startup, setStartup] = useState<StartupState>({ stage: "checking", headline: "Starting Cyclone…", detail: "Connecting to your local agent environment." });
  const [agents, setAgents] = useState<Agent[]>([]);
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [selectedId, setSelectedId] = useState<string>("");
  const [conversation, setConversation] = useState<ConversationDetail | null>(null);
  const [search, setSearch] = useState("");
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState("");
  const [computerSession, setComputerSession] = useState<ComputerSession | null>(null);
  const [computerUnavailable, setComputerUnavailable] = useState(false);
  const [newModalOpen, setNewModalOpen] = useState(false);
  const [focusedConversationId, setFocusedConversationId] = useState<string | null>(null);
  const activeEventSource = useRef<EventSource | null>(null);

  const loadLiveData = useCallback(async () => {
    const [health, loadedAgents, loadedConversations] = await Promise.all([
      coreClient.health(),
      coreClient.listAgents(),
      coreClient.listConversations(),
    ]);
    setAgents(loadedAgents);
    setConversations(loadedConversations);
    setStartup(fromHealth(health));
    setMode("live");
    setNotice(health.status === "ok" ? "" : healthDetail(health));

    const target = loadedConversations.find((item) => item.id === selectedId) ?? loadedConversations[0];
    if (target) {
      setSelectedId(target.id);
      setConversation(await coreClient.conversation(target.id));
    } else {
      setConversation(null);
    }
  }, [selectedId]);

  const enterDisconnectedState = useCallback((error?: unknown) => {
    setMode("disconnected");
    setAgents([]);
    setConversations([]);
    setSelectedId("");
    setConversation(disconnectedConversation());
    const detail = error instanceof CoreClientError ? error.message : "Cyclone Core is not reachable.";
    setStartup({ stage: "unavailable", headline: "Cyclone is offline", detail });
    setNotice("Start the local Cyclone environment to load your actual agents, conversations, and work.");
  }, []);

  useEffect(() => {
    let disposed = false;
    const initialize = async () => {
      try {
        setStartup({ stage: "starting", headline: "Starting agent environment…", detail: "Checking Cyclone Core." });
        await loadLiveData();
      } catch (error) {
        if (!disposed) enterDisconnectedState(error);
      }
    };
    void initialize();
    return () => { disposed = true; };
  }, [enterDisconnectedState, loadLiveData]);

  const refreshConversation = useCallback(async (conversationId: string) => {
    if (mode !== "live") return;
    try {
      const updated = await coreClient.conversation(conversationId);
      setConversation(updated);
      setConversations((current) => current.map((item) => item.id === conversationId ? { ...item, updated_at: updated.updated_at, latest_preview: updated.messages.at(-1)?.body ?? item.latest_preview } : item));
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Cyclone could not refresh the conversation.");
    }
  }, [mode]);

  useEffect(() => {
    activeEventSource.current?.close();
    if (mode !== "live" || !selectedId) return undefined;
    const source = new EventSource(coreClient.eventsUrl(selectedId));
    activeEventSource.current = source;
    const refresh = () => void refreshConversation(selectedId);
    ["message.created", "task.created", "task.updated", "agent.run.started", "agent.run.completed", "agent.run.blocked", "approval.requested", "approval.decided", "automation.received"].forEach((event) => source.addEventListener(event, refresh));
    source.onerror = () => setNotice((current) => current || "Live updates paused. Cyclone will reconnect when the conversation changes.");
    return () => source.close();
  }, [mode, refreshConversation, selectedId]);

  const searchableConversations = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return conversations;
    return conversations.filter((item) => `${item.title} ${item.latest_preview ?? ""}`.toLowerCase().includes(needle));
  }, [conversations, search]);

  const selectedAgents = useMemo(() => conversation?.members.map((member) => member.agent).filter((agent): agent is Agent => Boolean(agent)) ?? [], [conversation]);
  const headerAgents = selectedAgents.length ? selectedAgents : agents;
  const isPreview = mode === "disconnected" && Boolean(conversation && PREVIEW_CONVERSATION_IDS.has(conversation.id));

  async function selectConversation(item: ConversationSummary) {
    setFocusedConversationId(null);
    if (mode !== "live") return;
    setSelectedId(item.id);
    try {
      setConversation(await coreClient.conversation(item.id));
      setNotice("");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Cyclone could not open this conversation.");
    }
  }

  async function sendMessage(body: string) {
    if (!conversation || mode !== "live" || sending) return;
    setSending(true);
    try {
      const selectedAgent = selectedAgents.find((agent) => agent.status !== "offline") ?? agents.find((agent) => agent.slug === "chief") ?? agents[0];
      if (!selectedAgent) {
        setNotice("There is no available agent in this conversation yet.");
        return;
      }
      const result = await coreClient.sendMessage(conversation.id, body, selectedAgent.slug);
      setNotice(result.status === "blocked" ? result.detail : "");
      await refreshConversation(conversation.id);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Cyclone could not send the message.");
    } finally {
      setSending(false);
    }
  }

  async function decideApproval(approval: ApprovalEvent, decision: "approved" | "denied") {
    if (mode !== "live" || !conversation) return;
    try {
      await coreClient.approve(approval.id, decision);
      await refreshConversation(conversation.id);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Cyclone could not record that decision.");
    }
  }

  function openAgent(agent: Agent) {
    const candidate = conversations.find((item) => item.kind === "direct" && item.title.toLowerCase() === agent.name.toLowerCase());
    if (candidate) {
      void selectConversation(candidate);
      return;
    }
    setNotice(`${agent.name} is part of this conversation. A direct thread will appear when Cyclone has one for that agent.`);
  }

  async function openComputer(session?: ComputerSession) {
    if (session) {
      setComputerSession(session);
      return;
    }
    const agent = headerAgents[0];
    if (!agent) {
      setComputerUnavailable(true);
      return;
    }
    if (mode !== "live") {
      setComputerSession({ id: `unavailable-${agent.id}`, agentId: agent.id, status: "unavailable", owner: { type: "idle" } });
      return;
    }
    try {
      setComputerSession(await coreClient.computerSession(agent.id));
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "No computer session is available for this agent.");
      setComputerSession({ id: `unavailable-${agent.id}`, agentId: agent.id, status: "unavailable", owner: { type: "idle" } });
    }
  }

  async function updateComputerOwner(owner: ComputerOwner) {
    const current = computerSession;
    if (!current) return;
    const ownerName = owner.type;
    if (mode !== "live" || current.id.startsWith("unavailable-")) {
      setComputerSession({ ...current, owner });
      return;
    }
    try {
      const updated = await coreClient.computerOwnership(current.id, ownerName);
      setComputerSession(updated);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "Cyclone could not change computer control.");
    }
  }

  async function handleCreateConversation(title: string, agentSlugs: string[], kind: "direct" | "cluster") {
    if (mode !== "live") throw new Error("Cyclone Core must be online to create a conversation.");
    const conversation = await coreClient.createConversation({ title, agent_slugs: agentSlugs, kind });
    setConversations(await coreClient.listConversations());
    setSelectedId(conversation.id);
    setConversation(conversation);
    setNotice("");
  }

  async function handleCreateAgent(name: string, role: string, description: string, color: string, shape: "round" | "triangle" | "diamond" | "pebble" | "squircle") {
    if (mode !== "live") throw new Error("Cyclone Core must be online to create an agent.");
    const slug = name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 63);
    const agent = await coreClient.createAgent({ slug, name, role, description, avatar_color: color, avatar_shape: shape });
    setAgents(await coreClient.listAgents());
    setNotice(`${agent.name} is now part of your team.`);
    return agent;
  }

  return <div className="cyclone-app">
    <div className="cyclone-window">
      <aside className="sidebar">
        <TitleBar onNewConversation={() => setNewModalOpen(true)} onWindowAction={(action) => void handleWindowAction(action)} />
        <div className="sidebar__search"><SearchIcon size={14} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search" aria-label="Search conversations" /></div>
        <div className="sidebar__threads" aria-label="Conversations">
          {mode === "loading" && <><ConversationRowSkeleton /><ConversationRowSkeleton /><ConversationRowSkeleton /><ConversationRowSkeleton /></>}
          {mode === "live" && searchableConversations.map((item) => <ConversationRow key={item.id} conversation={item} agents={agentsForConversation(item, agents, conversation)} active={item.id === selectedId} focused={item.id === focusedConversationId} onSelect={selectConversation} />)}
          {mode === "live" && !searchableConversations.length && <div className="sidebar__empty">No conversations found.</div>}
          {mode === "disconnected" && <div className="sidebar__offline"><span className="sidebar__offline-dot" /><p>Waiting for your agent network</p><small>Conversations appear here when Cyclone Core is online.</small></div>}
        </div>
        <button type="button" className="sidebar__user" title="Administrator settings"><span>{identity.initials}</span><strong>{identity.displayName}</strong></button>
      </aside>

      <main className="conversation-surface">
        <ConversationHeader conversation={conversation} agents={headerAgents} status={startup} onOpenComputer={() => void openComputer()} />
        {notice && <div className="quiet-notice" role="status"><span>{notice}</span><button type="button" onClick={() => setNotice("")}>×</button></div>}
        <section className="conversation-body">
          {conversation ? <MessageTimeline conversation={conversation} agents={agents} onOpenAgent={openAgent} onOpenComputer={openComputer} onDecideApproval={(approval, decision) => void decideApproval(approval, decision)} /> : <LoadingConversation />}
        </section>
        <div className="conversation-composer">
          {mode === "loading" ? <div className="composer composer--disabled"><SpinnerIcon size={15} /><span>Connecting to Cyclone…</span></div> : <Composer conversationName={conversation?.title ?? "Cyclone"} agents={agents} disabled={mode !== "live" || !conversation} busy={sending} onSend={sendMessage} onAttachment={() => setNotice("Attach files and references from a live Cyclone conversation.")} />}
        </div>
      </main>
    </div>
    {computerSession && <RemoteComputerOverlay session={computerSession} agent={agents.find((agent) => agent.id === computerSession.agentId || agent.slug === computerSession.agentId)} onClose={() => setComputerSession(null)} onChangeOwner={updateComputerOwner} />}
    {computerUnavailable && <ComputerUnavailableOverlay onClose={() => setComputerUnavailable(false)} />}
    {newModalOpen && <NewConversationModal agents={agents} onClose={() => setNewModalOpen(false)} onCreateConversation={handleCreateConversation} onCreateAgent={handleCreateAgent} />}
  </div>;
}

function ConversationHeader({ conversation, agents, status, onOpenComputer }: { conversation: ConversationDetail | null; agents: Agent[]; status: StartupState; onOpenComputer: () => void }) {
  const title = conversation?.title ?? "Cyclone";
  const crew = conversation?.kind === "cluster";
  const activeAgent = agents[0];
  const activity = status.stage === "ready" ? "" : status.stage === "unavailable" ? "Offline" : status.headline;
  return <header className="conversation-header">
    <div className="conversation-header__identity">
      {crew ? <CrewAvatar agents={agents} size={19} /> : activeAgent ? <BotAvatar agent={activeAgent} size={19} /> : <span className="conversation-header__placeholder" />}
      <strong>{title}</strong>{activity && <span className="conversation-header__status">{activity}</span>}
    </div>
    <button type="button" className="conversation-header__computer" aria-label="Open agent computer" title="Open agent computer" onClick={onOpenComputer}><MonitorIcon size={17} /></button>
  </header>;
}

function LoadingConversation() {
  return <div className="conversation-loading"><SpinnerIcon size={18} /><p>Loading your conversation…</p></div>;
}

function agentsForConversation(summary: ConversationSummary, allAgents: Agent[], active: ConversationDetail | null): Agent[] {
  if (active?.id === summary.id) {
    const members = active.members.map((member) => member.agent).filter((agent): agent is Agent => Boolean(agent));
    if (members.length) return members;
  }
  if (summary.kind === "cluster") return allAgents.slice(0, 3);
  const direct = allAgents.find((agent) => agent.name.toLowerCase() === summary.title.toLowerCase());
  return direct ? [direct] : allAgents.slice(0, 1);
}

function fromHealth(health: HealthResponse): StartupState {
  if (health.status === "ok") return { stage: "ready", headline: "Ready", detail: "Your agent environment is ready.", health };
  const unavailable = Object.entries(health.dependencies).filter(([, item]) => item.status === "unavailable").map(([name]) => name);
  return { stage: "degraded", headline: unavailable.length ? "Needs attention" : "Partially ready", detail: unavailable.length ? `${unavailable.join(", ")} needs attention.` : "One or more services are not ready.", health };
}

function healthDetail(health: HealthResponse): string {
  const items = Object.entries(health.dependencies).filter(([, item]) => item.status !== "ok");
  if (!items.length) return "";
  return items.map(([name, item]) => `${capitalize(name)}: ${item.detail}`).join(" · ");
}

function capitalize(value: string) { return value.slice(0, 1).toUpperCase() + value.slice(1); }

export default App;

