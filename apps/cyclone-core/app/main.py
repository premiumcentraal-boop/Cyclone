"""Cyclone Core: the authenticated application integration boundary.

Desktop browsers never call Hermes, n8n, Docker, Postgres, or the Windows Host
Bridge directly. Core persists product state, publishes normalized activity,
and routes each external capability through an explicit policy boundary.

This module owns the HTTP surface; orchestration (agent wakes, inbox
dispatch, handoffs, run monitoring) lives in ``orchestrator`` and is shared
with the Cyclone MCP server (``mcp_server``).
"""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import hashlib
import json
import re
from pathlib import Path
from typing import AsyncIterator
from uuid import UUID, uuid4

import httpx
try:
    import redis.asyncio as redis
except ModuleNotFoundError:  # Host test preflight may use a stripped Python runtime.
    redis = None  # type: ignore[assignment]
from fastapi import Depends, FastAPI, File, Header, HTTPException, Request, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from fastapi.staticfiles import StaticFiles

from .contracts import (
    AgentRunResponse,
    AgentSummary,
    Approval,
    ApprovalDecisionRequest,
    ApprovalRequest,
    ArtifactCreateRequest,
    ArtifactResponse,
    AttachmentRef,
    AutomationEventRequest,
    AutomationEventResponse,
    ComputerOwnershipRequest,
    ComputerSessionResponse,
    ConversationDetail,
    ConversationSummary,
    CreateAgentRequest,
    CreateConversationRequest,
    CreateRoutineRequest,
    CreateMessageRequest,
    HealthDependency,
    HealthResponse,
    HermesRunEventsResponse,
    HermesRunEvent,
    HermesRunStart,
    HermesRunStatus,
    HostBridgeDecision,
    HostBridgeRequest,
    InboxItem,
    IntegrationResponse,
    IntegrationState,
    MemberAddRequest,
    MemoryEntry,
    MemorySearchResponse,
    MemoryWriteRequest,
    ReactionRequest,
    ReactionResponse,
    RunApprovalRequest,
    TaskStatusRequest,
    TaskSummary,
    RoutineSummary,
    UpdateAgentRequest,
    UserRecord,
)
from .events import EventBus
from .hermes import HermesAdapter
from .mcp_server import create_cyclone_mcp
from .memory import VaultMemoryService
from .mentions import parse_mentions, resolve_addressed_slug
from .orchestrator import dispatch_inbox_item, publish_message, wake_agent
from .policy import HostAction, PolicyEngine
from .repository import NotFoundError, Repository
from .router import route_group_message
from .settings import Settings, get_settings
from .telegram import TelegramWorker


class AppServices:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.repository = Repository(settings.database_url)
        self.hermes = HermesAdapter(base_url=settings.hermes_base_url, api_key=settings.hermes_api_key)
        self.event_bus = EventBus()
        self.memory = VaultMemoryService(settings.vault_path)
        self.policy = PolicyEngine()
        self.redis: object | None = None
        self.run_tasks: dict[str, asyncio.Task[None]] = {}
        self.pending_run_approvals: set[str] = set()
        self.telegram: TelegramWorker | None = None
        self.telegram_task: asyncio.Task[None] | None = None

    async def open(self) -> None:
        await self.repository.open()
        self.memory.bootstrap()
        if redis is None:
            raise RuntimeError("The Cyclone Core runtime requires the redis package. Install requirements.txt.")
        self.redis = redis.from_url(self.settings.redis_url, decode_responses=True)
        if self.settings.telegram_bot_token:
            self.telegram = TelegramWorker(self, self.settings.telegram_bot_token, self.settings.telegram_allowed_users)
            self.telegram_task = asyncio.create_task(self.telegram.run())

    async def close(self) -> None:
        if self.telegram_task is not None:
            self.telegram_task.cancel()
        for task in tuple(self.run_tasks.values()):
            task.cancel()
        if self.run_tasks:
            await asyncio.gather(*self.run_tasks.values(), return_exceptions=True)
        await self.hermes.close()
        if self.redis is not None:
            close = getattr(self.redis, "aclose", None)
            if close is not None:
                await close()
        await self.repository.close()

    async def redis_health(self) -> tuple[bool, str]:
        if self.redis is None:
            return False, "Redis client has not started."
        try:
            ping = getattr(self.redis, "ping")
            await ping()
            return True, "Redis responded to PING."
        except Exception:
            return False, "Redis is unavailable."


async def _recovery_sweep(runtime: AppServices) -> None:
    """Re-dispatch inbox items left pending by a previous process (restart recovery)."""
    try:
        items = await runtime.repository.pending_inbox_items(limit=50)
    except Exception:
        return
    for item in items:
        try:
            await dispatch_inbox_item(runtime, item)
        except Exception:
            pass  # The item stays pending; a later sweep retries it.


_cyclone_mcp = create_cyclone_mcp(lambda: app.state.services)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    services = AppServices(get_settings())
    app.state.services = services
    await services.open()
    asyncio.create_task(_recovery_sweep(services))
    try:
        # Starlette does not run a mounted application's lifespan. FastMCP's
        # Streamable HTTP transport needs its session manager running, so own
        # it at the Core application's lifespan instead.
        async with _cyclone_mcp.session_manager.run():
            yield
    finally:
        await services.close()


app = FastAPI(
    title="Cyclone Core",
    version="0.2.0",
    description="Private control plane for the Cyclone agent operating environment.",
    lifespan=lifespan,
)

settings = get_settings()
if settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST", "PATCH", "DELETE"],
        allow_headers=["Authorization", "Content-Type"],
    )


def services(request: Request) -> AppServices:
    return request.app.state.services


async def require_internal_key(
    x_cyclone_internal_key: str = Header(default="", alias="X-Cyclone-Internal-Key"),
    runtime: AppServices = Depends(services),
) -> None:
    if not x_cyclone_internal_key or not _constant_time_equal(x_cyclone_internal_key, runtime.settings.internal_api_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid internal integration credential.")


def _constant_time_equal(left: str, right: str) -> bool:
    import hmac

    return hmac.compare_digest(left.encode("utf-8"), right.encode("utf-8"))


def _not_found(error: NotFoundError) -> HTTPException:
    return HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(error))


# The Cyclone MCP server (agent messaging tools) is mounted loopback-only,
# like the rest of the API. Services are resolved per call from app.state.
app.mount("/mcp", _cyclone_mcp.streamable_http_app())
_attachments_dir = get_settings().workspace_path / "attachments"
try:
    _attachments_dir.mkdir(parents=True, exist_ok=True)
    app.mount("/attachments", StaticFiles(directory=_attachments_dir), name="attachments")
except OSError:
    pass  # Workspace not mounted (host test preflight); upload endpoint will raise clearly.


@app.get("/health", response_model=HealthResponse, tags=["operations"])
async def health(runtime: AppServices = Depends(services)) -> HealthResponse:
    database_ok = await runtime.repository.ping()
    redis_ok, redis_detail = await runtime.redis_health()
    hermes_ok, hermes_detail = await runtime.hermes.health()
    vault_ok = runtime.settings.vault_path.exists() and runtime.settings.vault_path.is_dir()
    workspace_ok = runtime.settings.workspace_path.exists() and runtime.settings.workspace_path.is_dir()
    dependencies = {
        "database": HealthDependency(status="ok" if database_ok else "unavailable", detail="PostgreSQL query succeeded." if database_ok else "PostgreSQL query failed."),
        "redis": HealthDependency(status="ok" if redis_ok else "unavailable", detail=redis_detail),
        "hermes": HealthDependency(status="ok" if hermes_ok else "degraded", detail=hermes_detail),
        "vault": HealthDependency(status="ok" if vault_ok else "unavailable", detail=str(runtime.settings.vault_path)),
        "workspace": HealthDependency(status="ok" if workspace_ok else "unavailable", detail=str(runtime.settings.workspace_path)),
    }
    core_ok = database_ok and redis_ok and vault_ok and workspace_ok
    return HealthResponse(
        status="ok" if core_ok and hermes_ok else "degraded",
        timestamp=datetime.now(timezone.utc),
        dependencies=dependencies,
    )


# --------------------------------------------------------------------------
# Agents
# --------------------------------------------------------------------------

@app.get("/api/v1/agents", response_model=list[AgentSummary], tags=["agents"])
async def list_agents(runtime: AppServices = Depends(services)) -> list[AgentSummary]:
    return await runtime.repository.list_agents()


@app.post("/api/v1/agents", response_model=AgentSummary, status_code=status.HTTP_201_CREATED, tags=["agents"])
async def create_agent(request: CreateAgentRequest, runtime: AppServices = Depends(services)) -> AgentSummary:
    """Create a persistent named agent (the \"spawn a teammate\" flow)."""
    try:
        agent = await runtime.repository.create_agent(
            slug=request.slug,
            name=request.name,
            role=request.role,
            description=request.description,
            avatar_color=request.avatar_color,
            avatar_shape=request.avatar_shape,
            provider=request.provider,
            model=request.model,
            hermes_profile=request.hermes_profile,
            workspace_path=request.workspace_path,
        )
    except Exception as error:
        if "unique" in str(error).lower() and "agents_slug_key" in str(error):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"An agent with slug '{request.slug}' already exists.",
            ) from error
        raise
    await runtime.repository.add_audit_event(
        actor_type="human",
        actor_id="administrator",
        action="agent.created",
        target=agent.slug,
        outcome="created",
        metadata={"agent_id": str(agent.id), "name": agent.name, "role": agent.role},
    )
    return agent


@app.patch("/api/v1/agents/{agent_id}", response_model=AgentSummary, tags=["agents"])
async def update_agent(agent_id: UUID, request: UpdateAgentRequest, runtime: AppServices = Depends(services)) -> AgentSummary:
    """Persist an administrator's profile edits for future Hermes context packets."""
    try:
        agent = await runtime.repository.update_agent(
            agent_id,
            name=request.name,
            role=request.role,
            description=request.description,
        )
    except NotFoundError as error:
        raise _not_found(error) from error
    await runtime.repository.add_audit_event(
        actor_type="human",
        actor_id="administrator",
        action="agent.profile_updated",
        target=agent.slug,
        outcome="updated",
        metadata={"agent_id": str(agent.id), "name": agent.name, "role": agent.role},
    )
    return agent


@app.get("/api/v1/agents/{agent_id}/computer", response_model=ComputerSessionResponse, tags=["computers"])
async def get_agent_computer(agent_id: UUID, runtime: AppServices = Depends(services)) -> ComputerSessionResponse:
    """Return a real persisted computer descriptor or an honest 404."""
    try:
        return await runtime.repository.get_latest_computer_session(agent_id)
    except NotFoundError as error:
        raise _not_found(error) from error


@app.post("/api/v1/computers/{session_id}/ownership", response_model=ComputerSessionResponse, tags=["computers"])
async def set_computer_ownership(
    session_id: UUID,
    request: ComputerOwnershipRequest,
    runtime: AppServices = Depends(services),
) -> ComputerSessionResponse:
    """Switch exclusive input ownership for an existing live computer session."""
    try:
        session = await runtime.repository.set_computer_session_owner(session_id, request.owner)
    except NotFoundError as error:
        raise _not_found(error) from error
    await runtime.repository.add_audit_event(
        actor_type="human" if request.owner == "human" else "agent",
        actor_id="administrator" if request.owner == "human" else session.agent_id,
        action="computer.ownership_changed",
        target=session.id,
        outcome=request.owner,
    )
    return session


@app.get("/api/v1/agents/{agent_id}/inbox", response_model=list[InboxItem], tags=["agents"])
async def list_inbox(agent_id: UUID, only_pending: bool = False, runtime: AppServices = Depends(services)) -> list[InboxItem]:
    """The persistent asynchronous inbox of one agent."""
    try:
        await runtime.repository.get_agent_by_id(agent_id)
    except NotFoundError as error:
        raise _not_found(error) from error
    return await runtime.repository.list_agent_inbox(agent_id, only_pending=only_pending)


@app.get("/api/v1/agents/{agent_id}/routines", tags=["agents"])
async def list_agent_routines(agent_id: UUID, runtime: AppServices = Depends(services)) -> list[dict[str, object]]:
    """Real scheduled routines owned by one agent (empty list = honest empty state)."""
    try:
        await runtime.repository.get_agent_by_id(agent_id)
    except NotFoundError as error:
        raise _not_found(error) from error
    return await runtime.repository.list_agent_routines(agent_id)


@app.get("/api/v1/integrations", response_model=IntegrationResponse, tags=["operations"])
async def list_integrations(runtime: AppServices = Depends(services)) -> IntegrationResponse:
    """Real integration availability — never claims an unconfigured capability."""
    hermes_ok, hermes_detail = await runtime.hermes.health()
    vault_ok = runtime.settings.vault_path.exists() and runtime.settings.vault_path.is_dir()
    workspace_ok = runtime.settings.workspace_path.exists() and runtime.settings.workspace_path.is_dir()
    n8n_ok = False
    n8n_detail = "n8n is not reachable from Core."
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            response = await client.get("http://n8n:5678/healthz")
            n8n_ok = response.status_code == 200
            n8n_detail = "n8n is reachable." if n8n_ok else f"n8n returned HTTP {response.status_code}."
    except httpx.HTTPError:
        pass
    integrations = [
        IntegrationState(name="Hermes", available=hermes_ok, detail=hermes_detail),
        IntegrationState(name="Obsidian vault", available=vault_ok, detail="Vault mounted and readable." if vault_ok else "Vault is not mounted."),
        IntegrationState(name="Workspace", available=workspace_ok, detail="Shared workspace mounted." if workspace_ok else "Workspace is not mounted."),
        IntegrationState(name="n8n", available=n8n_ok, detail=n8n_detail),
        IntegrationState(name="Browser", available=hermes_ok, detail="Hermes browser tools are available." if hermes_ok else "Unavailable until Hermes is healthy."),
    ]
    return IntegrationResponse(integrations=integrations)


@app.post("/api/v1/attachments", tags=["operations"])
async def upload_attachment(file: UploadFile = File(...), runtime: AppServices = Depends(services)) -> dict[str, object]:
    """Store a real file under the shared workspace and return its reference.

    The desktop uploads actual files; the message metadata carries the
    reference and agents see the path in the workspace.
    """
    attachments_dir = runtime.settings.workspace_path / "attachments"
    attachments_dir.mkdir(parents=True, exist_ok=True)
    original = Path(file.filename or "attachment").name
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", original)[:120]
    target = attachments_dir / f"{uuid4().hex[:10]}-{safe}"
    size = 0
    with target.open("wb") as handle:
        while chunk := await file.read(1_048_576):
            size += len(chunk)
            handle.write(chunk)
    if size == 0:
        target.unlink(missing_ok=True)
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="The uploaded file is empty.")
    return {"name": safe, "size": size, "url": f"/attachments/{target.name}", "kind": "file"}


@app.get("/api/v1/users/me", response_model=UserRecord, tags=["operations"])
async def get_current_user(runtime: AppServices = Depends(services)) -> UserRecord:
    """The persistent user identity behind this Cyclone instance.

    The Telegram integration creates/updates this record on first contact;
    before that, the desktop falls back to its local default identity.
    """
    rows = await runtime.repository.list_users(limit=1)
    if rows:
        row = rows[0]
        return UserRecord(
            id=row["id"], display_name=row["display_name"], initials=row["initials"],
            telegram_chat_id=row["telegram_chat_id"], created_at=row["created_at"], updated_at=row["updated_at"],
        )
    return UserRecord(
        id=UUID(int=1), display_name="Premium Centraal", initials="PC",
        created_at=datetime.now(timezone.utc), updated_at=datetime.now(timezone.utc),
    )


# --------------------------------------------------------------------------
# Conversations
# --------------------------------------------------------------------------

@app.get("/api/v1/conversations", response_model=list[ConversationSummary], tags=["conversations"])
async def list_conversations(runtime: AppServices = Depends(services)) -> list[ConversationSummary]:
    return await runtime.repository.list_conversations()


@app.post("/api/v1/conversations", response_model=ConversationDetail, status_code=status.HTTP_201_CREATED, tags=["conversations"])
async def create_conversation(request: CreateConversationRequest, runtime: AppServices = Depends(services)) -> ConversationDetail:
    try:
        conversation = await runtime.repository.create_conversation(
            title=request.title, kind=request.kind, project_key=request.project_key, agent_slugs=request.agent_slugs
        )
    except NotFoundError as error:
        raise _not_found(error) from error
    await runtime.event_bus.publish(
        conversation_id=conversation.id,
        event_type="conversation.created",
        payload={"id": str(conversation.id), "title": conversation.title, "kind": conversation.kind},
    )
    return conversation


@app.post("/api/v1/conversations/{conversation_id}/routines", response_model=RoutineSummary, status_code=status.HTTP_201_CREATED, tags=["routines"])
async def create_routine(
    conversation_id: UUID, request: CreateRoutineRequest, runtime: AppServices = Depends(services)
) -> RoutineSummary:
    """Store a taught routine and show its creation naturally in the real conversation."""
    try:
        await runtime.repository.get_conversation(conversation_id, message_limit=1)
        owner = await runtime.repository.get_agent_by_slug(request.owner_agent_slug) if request.owner_agent_slug else None
        routine = await runtime.repository.create_routine(
            slug=request.slug,
            name=request.name,
            description=request.description,
            instructions=request.instructions,
            owner_agent_id=owner.id if owner else None,
            schedule=request.schedule,
        )
    except NotFoundError as error:
        raise _not_found(error) from error
    except Exception as error:
        if "unique" in str(error).lower() and "routines_slug_key" in str(error):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"A routine with slug '{request.slug}' already exists.") from error
        raise
    message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        author_type="system",
        kind="automation",
        body=f"Created routine: {routine.name}",
        metadata={"routine": {"id": str(routine.id), "name": routine.name, "schedule": request.schedule, "status": "created"}},
    )
    await publish_message(runtime, message, "routine.created")
    await runtime.repository.add_audit_event(
        actor_type="human",
        actor_id="administrator",
        action="routine.taught",
        target=routine.slug,
        outcome="created",
        metadata={"routine_id": str(routine.id), "conversation_id": str(conversation_id), "owner_agent_id": str(routine.owner_agent_id) if routine.owner_agent_id else None},
    )
    return routine


@app.get("/api/v1/conversations/{conversation_id}", response_model=ConversationDetail, tags=["conversations"])
async def get_conversation(conversation_id: UUID, runtime: AppServices = Depends(services)) -> ConversationDetail:
    try:
        return await runtime.repository.get_conversation(conversation_id)
    except NotFoundError as error:
        raise _not_found(error) from error


@app.post("/api/v1/conversations/{conversation_id}/members", response_model=ConversationDetail, status_code=status.HTTP_201_CREATED, tags=["conversations"])
async def add_member(conversation_id: UUID, request: MemberAddRequest, runtime: AppServices = Depends(services)) -> ConversationDetail:
    """Add a persistent agent to a group. Future events reach it; history stays."""
    try:
        conversation = await runtime.repository.get_conversation(conversation_id, message_limit=1)
        agent = await runtime.repository.get_agent_by_slug(request.agent_slug)
    except NotFoundError as error:
        raise _not_found(error) from error
    await runtime.repository.add_conversation_member(conversation_id, agent)
    await runtime.repository.add_message(
        conversation_id=conversation_id,
        author_type="system",
        kind="activity",
        body=f"{agent.name} joined this conversation.",
        metadata={"agent_slug": agent.slug, "action": "member_added"},
    )
    return await runtime.repository.get_conversation(conversation_id)


@app.delete("/api/v1/conversations/{conversation_id}/members/{agent_id}", response_model=ConversationDetail, tags=["conversations"])
async def remove_member(conversation_id: UUID, agent_id: UUID, runtime: AppServices = Depends(services)) -> ConversationDetail:
    """Remove an agent from a group. History stays; new events stop arriving."""
    try:
        conversation = await runtime.repository.get_conversation(conversation_id, message_limit=1)
        agent = await runtime.repository.get_agent_by_id(agent_id)
    except NotFoundError as error:
        raise _not_found(error) from error
    removed = await runtime.repository.remove_conversation_member(conversation_id, agent_id)
    if not removed:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Agent is not a member of this conversation.")
    await runtime.repository.add_message(
        conversation_id=conversation_id,
        author_type="system",
        kind="activity",
        body=f"{agent.name} left this conversation.",
        metadata={"agent_slug": agent.slug, "action": "member_removed"},
    )
    return await runtime.repository.get_conversation(conversation_id)


@app.get("/api/v1/conversations/{conversation_id}/events", tags=["conversations"])
async def stream_conversation_events(conversation_id: UUID, request: Request, runtime: AppServices = Depends(services)) -> StreamingResponse:
    try:
        await runtime.repository.get_conversation(conversation_id, message_limit=1)
    except NotFoundError as error:
        raise _not_found(error) from error

    async def event_stream() -> AsyncIterator[str]:
        subscription = await runtime.event_bus.subscribe(conversation_id)
        try:
            yield ": cyclone event stream established\n\n"
            while True:
                if await request.is_disconnected():
                    break
                try:
                    envelope = await asyncio.wait_for(subscription.get(), timeout=15)
                    payload = envelope.model_dump(mode="json")
                    yield f"id: {envelope.id}\nevent: {envelope.type}\ndata: {json.dumps(payload)}\n\n"
                except TimeoutError:
                    yield ": keepalive\n\n"
        finally:
            await runtime.event_bus.unsubscribe(subscription)

    return StreamingResponse(event_stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/api/v1/conversations/{conversation_id}/messages", response_model=AgentRunResponse, tags=["conversations"])
async def create_message_and_start_agent(
    conversation_id: UUID, request: CreateMessageRequest, runtime: AppServices = Depends(services)
) -> AgentRunResponse:
    """Record a human message and route it through the group coordinator.

    Semantic mentions are persisted as database objects. A leading ``@slug``
    addresses that member; inline mentions route to the first mentioned
    member; ``@everyone`` broadcasts without waking everyone; unmentioned
    messages route deterministically (active task owner, last speaker,
    coordinator). The selected agent is woken through its durable inbox and
    executes a real Hermes turn asynchronously.
    """
    try:
        conversation = await runtime.repository.get_conversation(conversation_id, message_limit=100)
        requested_agent = await runtime.repository.get_agent_by_slug(request.agent_slug)
    except NotFoundError as error:
        raise _not_found(error) from error

    member_agents = [member.agent for member in conversation.members if member.agent is not None]
    member_by_slug = {member.slug: member for member in member_agents}
    mentioned_slugs = parse_mentions(request.body)
    addressed_slug = resolve_addressed_slug(request.body, set(member_by_slug))
    has_everyone = "@everyone" in request.body.lower()

    active_task = await runtime.repository.get_active_task(conversation_id)
    last_agent_author = None
    for message in reversed(conversation.messages):
        if message.author_type == "agent" and message.author_agent_id is not None:
            last_agent_author = str(message.author_agent_id)
            break
    decision = route_group_message(
        conversation=conversation,
        requested_agent=requested_agent,
        member_agents=member_agents,
        addressed_slug=addressed_slug,
        mentioned_slugs=mentioned_slugs,
        has_everyone=has_everyone,
        active_task=active_task,
        last_agent_message_author_id=last_agent_author,
    )

    # Persist the human message with structured mention objects.
    user_metadata: dict[str, object] = {}
    if mentioned_slugs or has_everyone:
        user_metadata["mentions"] = mentioned_slugs
    if request.attachments:
        user_metadata["attachments"] = [
            {"name": attachment.name, "size": attachment.size, "url": attachment.url, "kind": attachment.kind}
            for attachment in request.attachments
        ]
    user_message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        author_type="human",
        kind="message",
        body=request.body,
        metadata=user_metadata or None,
        reply_to_message_id=request.reply_to_message_id,
    )
    mention_rows = []
    for slug in mentioned_slugs:
        target = member_by_slug.get(slug)
        if target is not None:
            mention_rows.append({"mention_type": "agent", "target_agent_id": target.id, "target_slug": target.slug})
        else:
            mention_rows.append({"mention_type": "agent", "target_slug": slug})
    if has_everyone:
        mention_rows.append({"mention_type": "everyone", "target_slug": "everyone"})
    await runtime.repository.add_mentions(user_message.id, mention_rows)
    await publish_message(runtime, user_message)

    task = await runtime.repository.create_task(
        conversation_id=conversation_id,
        owner_agent_id=decision.target.id if decision.target else None,
        title=request.body[:300],
        objective=request.body,
        verification_criteria="Report only verified work and state open blockers.",
    )
    task_message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        task_id=task.id,
        author_type="system",
        kind="task",
        body=f"{decision.target.name if decision.target else 'The group'} accepted a task: {task.title}",
        metadata={"task_id": str(task.id), "status": task.status, "routing": decision.reason},
    )
    await publish_message(runtime, task_message, "task.created")

    if not request.run:
        return AgentRunResponse(task=task, user_message=user_message, status="queued", detail=f"Routed to {decision.reason}; task recorded without starting a model run.")

    # @everyone: durable inbox events for all members; no auto-woken chatter.
    if decision.broadcast:
        for member in member_agents:
            await runtime.repository.enqueue_inbox_item(
                agent_id=member.id,
                event_type="group.broadcast",
                conversation_id=conversation_id,
                message_id=user_message.id,
                task_id=task.id,
                payload={"input_text": request.body},
            )
        return AgentRunResponse(
            task=task,
            user_message=user_message,
            status="queued",
            detail=f"Broadcast to {len(member_agents)} group members; no agent was auto-woken.",
        )

    target = decision.target
    if target is None:
        return AgentRunResponse(task=task, user_message=user_message, status="queued", detail="No responder selected.")

    response = await wake_agent(
        runtime,
        agent=target,
        conversation=conversation,
        member_agents=member_agents,
        trigger_message=user_message,
        task=task,
        event_type="human.message",
        payload={"input_text": request.body, "extra": f"Routed because: {decision.reason}."},
    )
    if isinstance(response, AgentRunResponse):
        response.user_message = user_message
        return response
    return AgentRunResponse(
        task=task,
        user_message=user_message,
        status="queued",
        detail=f"Task queued for {target.name}; dispatch will retry.",
    )


# --------------------------------------------------------------------------
# Tasks / runs
# --------------------------------------------------------------------------

@app.get("/api/v1/tasks/{task_id}", response_model=TaskSummary, tags=["tasks"])
async def get_task(task_id: UUID, runtime: AppServices = Depends(services)) -> TaskSummary:
    try:
        return await runtime.repository.get_task(task_id)
    except NotFoundError as error:
        raise _not_found(error) from error


@app.patch("/api/v1/tasks/{task_id}/status", response_model=TaskSummary, tags=["tasks"])
async def update_task_status(task_id: UUID, request: TaskStatusRequest, runtime: AppServices = Depends(services)) -> TaskSummary:
    """Explicit task state transitions (working -> review -> changes_requested -> ...)."""
    try:
        task = await runtime.repository.get_task(task_id)
    except NotFoundError as error:
        raise _not_found(error) from error
    updated = await runtime.repository.set_task_status(task_id, request.status)
    if request.note:
        await runtime.repository.add_message(
            conversation_id=task.conversation_id,
            task_id=task.id,
            author_type="system",
            kind="task",
            body=request.note,
            metadata={"task_id": str(task.id), "status": request.status},
        )
    await runtime.event_bus.publish(conversation_id=task.conversation_id, event_type="task.updated", payload=updated.model_dump(mode="json"))
    return updated


@app.get("/api/v1/tasks/{task_id}/artifacts", response_model=list[ArtifactResponse], tags=["tasks"])
async def list_task_artifacts(task_id: UUID, runtime: AppServices = Depends(services)) -> list[ArtifactResponse]:
    return await runtime.repository.list_artifacts(task_id)


@app.post("/api/v1/tasks/{task_id}/artifacts", response_model=ArtifactResponse, status_code=status.HTTP_201_CREATED, tags=["tasks"])
async def create_task_artifact(task_id: UUID, request: ArtifactCreateRequest, runtime: AppServices = Depends(services)) -> ArtifactResponse:
    creator = None
    if request.created_by_agent_slug:
        try:
            creator = await runtime.repository.get_agent_by_slug(request.created_by_agent_slug)
        except NotFoundError as error:
            raise _not_found(error) from error
    return await runtime.repository.create_artifact(
        task_id=task_id,
        conversation_id=None,
        created_by_agent_id=creator.id if creator else None,
        type_=request.type,
        path=request.path,
    )


@app.get("/api/v1/runs/{run_id}", response_model=HermesRunStatus, tags=["runs"])
async def get_run(run_id: str, runtime: AppServices = Depends(services)) -> HermesRunStatus:
    try:
        data = await runtime.hermes.get_run(run_id)
    except RuntimeError as error:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(error)) from error
    return HermesRunStatus(run_id=str(data.get("run_id", run_id)), status=str(data.get("status", "unknown")), output=data.get("output") if isinstance(data.get("output"), str) else None, raw=data)


@app.post("/api/v1/runs/{run_id}/stop", status_code=status.HTTP_202_ACCEPTED, tags=["runs"])
async def stop_run(run_id: str, runtime: AppServices = Depends(services)) -> dict[str, str]:
    try:
        await runtime.hermes.stop_run(run_id)
    except RuntimeError as error:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(error)) from error
    return {"status": "stopping", "run_id": run_id}


@app.post("/api/v1/runs/{run_id}/approval", tags=["runs"])
async def resolve_run_approval(run_id: str, request: RunApprovalRequest, runtime: AppServices = Depends(services)) -> dict[str, str]:
    """Resolve a real Hermes run approval (choice: once | session | always | deny)."""
    try:
        await runtime.hermes.resolve_run_approval(run_id, request.choice)
    except RuntimeError as error:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=str(error)) from error
    await runtime.repository.add_audit_event(
        actor_type="human", actor_id="administrator", action="run.approval_resolved",
        target=run_id, outcome=request.choice,
    )
    return {"status": "resolved", "run_id": run_id, "choice": request.choice}


@app.get("/api/v1/runs/{run_id}/events", response_model=HermesRunEventsResponse, tags=["runs"])
async def documented_run_events_surface(run_id: str) -> HermesRunEventsResponse:
    """Advertise the Core-normalized run event API without pretending history exists."""
    return HermesRunEventsResponse(run_id=run_id, events=[])


# --------------------------------------------------------------------------
# Approvals
# --------------------------------------------------------------------------

@app.post("/api/v1/conversations/{conversation_id}/approvals", response_model=Approval, status_code=status.HTTP_201_CREATED, tags=["approvals"])
async def create_approval(conversation_id: UUID, request: ApprovalRequest, runtime: AppServices = Depends(services)) -> Approval:
    try:
        await runtime.repository.get_conversation(conversation_id, message_limit=1)
        agent = await runtime.repository.get_agent_by_slug(request.requested_by_agent_slug) if request.requested_by_agent_slug else None
        approval = await runtime.repository.create_approval(
            conversation_id=conversation_id,
            task_id=request.task_id,
            requested_by_agent_id=agent.id if agent else None,
            capability=request.capability,
            target=request.target,
            scope=request.scope,
            expected_effect=request.expected_effect,
            policy_reason=request.policy_reason,
            expires_at=request.expires_at,
        )
    except NotFoundError as error:
        raise _not_found(error) from error
    message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        task_id=request.task_id,
        author_type="system",
        kind="approval",
        body=f"Approval required: {approval.capability} → {approval.target}",
        metadata=approval.model_dump(mode="json"),
    )
    await publish_message(runtime, message, "approval.requested")
    await runtime.repository.add_audit_event(actor_type="agent" if agent else "system", actor_id=agent.slug if agent else "cyclone-core", action="approval.requested", target=approval.target, outcome="pending", metadata={"approval_id": str(approval.id), "capability": approval.capability})
    return approval


@app.post("/api/v1/approvals/{approval_id}/decision", response_model=Approval, tags=["approvals"])
async def decide_approval(approval_id: UUID, request: ApprovalDecisionRequest, runtime: AppServices = Depends(services)) -> Approval:
    try:
        approval = await runtime.repository.decide_approval(approval_id, decision=request.decision, decided_by=request.decided_by)
    except NotFoundError as error:
        raise _not_found(error) from error
    message = await runtime.repository.add_message(
        conversation_id=approval.conversation_id,
        task_id=approval.task_id,
        author_type="system",
        kind="approval",
        body=f"Approval {request.decision}: {approval.capability} → {approval.target}",
        metadata=approval.model_dump(mode="json"),
    )
    await publish_message(runtime, message, "approval.decided")
    await runtime.repository.add_audit_event(actor_type="human", actor_id=request.decided_by, action="approval.decided", target=approval.target, outcome=request.decision, metadata={"approval_id": str(approval.id), "capability": approval.capability})
    return approval


# --------------------------------------------------------------------------
# Memory / vault
# --------------------------------------------------------------------------

@app.post("/api/v1/memory", response_model=MemoryEntry, status_code=status.HTTP_201_CREATED, tags=["memory"])
async def write_memory(request: MemoryWriteRequest, runtime: AppServices = Depends(services)) -> MemoryEntry:
    agent = None
    if request.agent_slug:
        try:
            agent = await runtime.repository.get_agent_by_slug(request.agent_slug)
        except NotFoundError as error:
            raise _not_found(error) from error
    try:
        entry = runtime.memory.write(title=request.title, category=request.category, content=request.content, project_key=request.project_key, agent_slug=request.agent_slug)
    except ValueError as error:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(error)) from error
    fingerprint = hashlib.sha256(request.content.encode("utf-8")).hexdigest()
    record = await runtime.repository.create_knowledge_entry(
        vault_path=entry.vault_path,
        title=entry.title,
        category=entry.category,
        project_key=request.project_key,
        agent_id=agent.id if agent else None,
        content=request.content,
        content_fingerprint=fingerprint,
        source_conversation_id=None,
    )
    return MemoryEntry(**record)


@app.get("/api/v1/memory/search", response_model=MemorySearchResponse, tags=["memory"])
async def search_memory(query: str, limit: int = 10, runtime: AppServices = Depends(services)) -> MemorySearchResponse:
    if not query.strip():
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Query must not be blank.")
    entries = await runtime.repository.search_knowledge(query, min(max(limit, 1), 50))
    return MemorySearchResponse(entries=[MemoryEntry(**entry) for entry in entries], retrieval="keyword", query=query)


# --------------------------------------------------------------------------
# Reactions
# --------------------------------------------------------------------------

@app.post("/api/v1/messages/{message_id}/reactions", response_model=ReactionResponse, status_code=status.HTTP_201_CREATED, tags=["reactions"])
async def add_reaction(message_id: UUID, request: ReactionRequest, runtime: AppServices = Depends(services)) -> ReactionResponse:
    reaction = await runtime.repository.add_reaction(
        message_id=message_id,
        actor_type=request.actor_type,
        actor_agent_id=request.actor_agent_id,
        emoji=request.emoji,
    )
    if reaction is None:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="That reaction already exists.")
    return reaction


@app.delete("/api/v1/messages/{message_id}/reactions", status_code=status.HTTP_204_NO_CONTENT, tags=["reactions"])
async def remove_reaction(message_id: UUID, request: ReactionRequest, runtime: AppServices = Depends(services)) -> None:
    await runtime.repository.remove_reaction(
        message_id=message_id,
        actor_type=request.actor_type,
        actor_agent_id=request.actor_agent_id,
        emoji=request.emoji,
    )


# --------------------------------------------------------------------------
# Internal integrations (n8n, host bridge)
# --------------------------------------------------------------------------

@app.post("/api/v1/internal/automation/events", response_model=AutomationEventResponse, tags=["automation"], dependencies=[Depends(require_internal_key)])
async def receive_automation_event(request: AutomationEventRequest, runtime: AppServices = Depends(services)) -> AutomationEventResponse:
    existing = await runtime.repository.find_automation_event(request.external_event_id)
    if existing:
        return AutomationEventResponse(id=existing, status="duplicate", conversation_id=request.conversation_id)
    event_id, _ = await runtime.repository.record_automation_event(
        external_event_id=request.external_event_id,
        event_type=request.event_type,
        payload=request.payload,
        routine_slug=request.routine_slug,
    )
    conversation_id = request.conversation_id or await runtime.repository.default_conversation_id()
    message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        author_type="automation",
        kind="automation",
        body=f"Automation event received: {request.event_type}",
        metadata={"automation_event_id": str(event_id), "routine_slug": request.routine_slug, "payload": request.payload},
        source="n8n",
    )
    await publish_message(runtime, message, "automation.received")
    await runtime.repository.mark_automation_processed(event_id)
    await runtime.repository.add_audit_event(actor_type="automation", actor_id="n8n", action="automation.event_received", target=request.event_type, outcome="processed", metadata={"event_id": str(event_id)})
    return AutomationEventResponse(id=event_id, status="received", conversation_id=conversation_id)


@app.post("/api/v1/host-bridge/authorize", response_model=HostBridgeDecision, tags=["host-bridge"])
async def authorize_host_bridge(request: HostBridgeRequest, runtime: AppServices = Depends(services)) -> HostBridgeDecision:
    decision = runtime.policy.evaluate(HostAction(capability=request.capability, target=request.target, arguments=request.arguments, conversation_id=request.conversation_id, task_id=request.task_id))
    if decision.allowed:
        await runtime.repository.add_audit_event(actor_type="system", actor_id="cyclone-core", action="host_bridge.authorized", target=request.target, outcome="allowed", metadata={"capability": request.capability})
        return HostBridgeDecision(allowed=True, requires_approval=False, reason=decision.reason)
    if not decision.requires_approval:
        await runtime.repository.add_audit_event(actor_type="system", actor_id="cyclone-core", action="host_bridge.authorized", target=request.target, outcome="denied", metadata={"capability": request.capability, "reason": decision.reason})
        return HostBridgeDecision(allowed=False, requires_approval=False, reason=decision.reason)
    approval = await runtime.repository.create_approval(
        conversation_id=request.conversation_id,
        task_id=request.task_id,
        requested_by_agent_id=None,
        capability=request.capability,
        target=request.target,
        scope=request.arguments,
        expected_effect="Execute requested Windows host capability after administrator approval.",
        policy_reason=decision.reason,
        expires_at=None,
    )
    message = await runtime.repository.add_message(
        conversation_id=request.conversation_id,
        task_id=request.task_id,
        author_type="system",
        kind="approval",
        body=f"Host Bridge approval required: {request.capability} → {request.target}",
        metadata=approval.model_dump(mode="json"),
    )
    await publish_message(runtime, message, "approval.requested")
    await runtime.repository.add_audit_event(actor_type="system", actor_id="cyclone-core", action="host_bridge.authorized", target=request.target, outcome="approval_required", metadata={"approval_id": str(approval.id), "capability": request.capability})
    return HostBridgeDecision(allowed=False, requires_approval=True, reason=decision.reason, approval=approval)
