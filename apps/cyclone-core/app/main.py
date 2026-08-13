"""Cyclone Core: the authenticated application integration boundary.

Desktop browsers never call Hermes, n8n, Docker, Postgres, or the Windows Host
Bridge directly. Core persists product state, publishes normalized activity, and
routes each external capability through an explicit policy boundary.
"""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from typing import AsyncIterator
from uuid import UUID

import httpx
try:
    import redis.asyncio as redis
except ModuleNotFoundError:  # Host test preflight may use a stripped Python runtime.
    redis = None  # type: ignore[assignment]
from fastapi import Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse

from .contracts import (
    AgentRunResponse,
    AgentSummary,
    Approval,
    ApprovalDecisionRequest,
    ApprovalRequest,
    AutomationEventRequest,
    AutomationEventResponse,
    ComputerOwnershipRequest,
    ComputerSessionResponse,
    ConversationDetail,
    ConversationSummary,
    CreateAgentRequest,
    CreateConversationRequest,
    CreateMessageRequest,
    HealthDependency,
    HealthResponse,
    HermesRunEventsResponse,
    HermesRunEvent,
    HermesRunStart,
    HermesRunStatus,
    HostBridgeDecision,
    HostBridgeRequest,
    MemoryEntry,
    MemorySearchResponse,
    MemoryWriteRequest,
    TaskSummary,
)
from .events import EventBus
from .hermes import HermesAdapter
from .memory import VaultMemoryService
from .mentions import crew_context_text, parse_handoffs, parse_mentions, resolve_addressed_slug
from .policy import HostAction, PolicyEngine
from .repository import NotFoundError, Repository
from .settings import Settings, get_settings

MAX_HANDOFF_DEPTH = 4  # Delegation-loop guard: cap handoffs per task ancestry chain.


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

    async def open(self) -> None:
        await self.repository.open()
        self.memory.bootstrap()
        if redis is None:
            raise RuntimeError("The Cyclone Core runtime requires the redis package. Install requirements.txt.")
        self.redis = redis.from_url(self.settings.redis_url, decode_responses=True)

    async def close(self) -> None:
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


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    services = AppServices(get_settings())
    app.state.services = services
    await services.open()
    try:
        yield
    finally:
        await services.close()


app = FastAPI(
    title="Cyclone Core",
    version="0.1.0",
    description="Private control plane for the Cyclone agent operating environment.",
    lifespan=lifespan,
)

settings = get_settings()
if settings.cors_origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST"],
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


async def _publish_message(runtime: AppServices, message: object, event_type: str = "message.created") -> None:
    payload = message.model_dump(mode="json")  # Message contract
    await runtime.event_bus.publish(
        conversation_id=UUID(str(payload["conversation_id"])), event_type=event_type, payload=payload
    )


async def _monitor_run(
    runtime: AppServices,
    *,
    task_id: UUID,
    conversation_id: UUID,
    agent: AgentSummary,
    run_id: str,
    member_agents: list[AgentSummary],
) -> None:
    """Poll bounded Hermes run status; durable task/message records are truth.

    Hermes offers its own SSE endpoint. The initial Core builds a reliable
    normalized lifecycle from status polling because it remains reconnectable and
    avoids treating transport token deltas as durable chat content. A later
    adapter can enrich this with Hermes run events/subagent lifecycle detail.
    """
    try:
        for _ in range(180):
            await asyncio.sleep(1)
            current = await runtime.hermes.get_run(run_id)
            run_status = str(current.get("status", "unknown"))
            if run_status in {"completed", "failed", "cancelled"}:
                output = current.get("output")
                summary = output if isinstance(output, str) else None
                mapped_status = "completed" if run_status == "completed" else "failed" if run_status == "failed" else "cancelled"
                task = await runtime.repository.set_task_terminal(task_id, status=mapped_status, result_summary=summary)
                await runtime.repository.set_agent_status(agent.id, "idle" if mapped_status == "completed" else "error")
                result_body = summary or f"Hermes run {run_id} ended with status: {run_status}."
                metadata: dict[str, object] = {"hermes_run_id": run_id, "status": run_status}
                if mapped_status == "completed" and summary:
                    mention_slugs = parse_mentions(summary)
                    if mention_slugs:
                        metadata["mentions"] = mention_slugs
                message = await runtime.repository.add_message(
                    conversation_id=conversation_id,
                    task_id=task_id,
                    author_type="agent",
                    author_agent_id=agent.id,
                    kind="result" if mapped_status == "completed" else "activity",
                    body=result_body,
                    metadata=metadata,
                    source="hermes",
                )
                await _publish_message(runtime, message, "agent.run.completed")
                await runtime.event_bus.publish(
                    conversation_id=conversation_id,
                    event_type="task.updated",
                    payload=task.model_dump(mode="json"),
                )
                if mapped_status == "completed" and summary:
                    for instruction in parse_handoffs(summary):
                        await _try_handoff(
                            runtime,
                            conversation_id=conversation_id,
                            from_task=task,
                            from_agent=agent,
                            instruction=instruction,
                            member_agents=member_agents,
                        )
                return
        task = await runtime.repository.set_task_terminal(
            task_id, status="blocked", result_summary="Hermes run status polling exceeded the Core observation window."
        )
        await runtime.repository.set_agent_status(agent.id, "blocked")
        message = await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=task_id,
            author_type="system",
            kind="activity",
            body="The agent run is still unresolved after the observation window. Check Hermes diagnostics before retrying.",
            metadata={"hermes_run_id": run_id},
        )
        await _publish_message(runtime, message, "agent.run.blocked")
        await runtime.event_bus.publish(
            conversation_id=conversation_id, event_type="task.updated", payload=task.model_dump(mode="json")
        )
    except Exception as error:
        task = await runtime.repository.set_task_terminal(
            task_id, status="blocked", result_summary="Cyclone could not observe the Hermes run safely."
        )
        await runtime.repository.set_agent_status(agent.id, "blocked")
        message = await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=task_id,
            author_type="system",
            kind="activity",
            body="Cyclone lost contact with Hermes while observing the run. No completion is claimed; inspect Hermes before retrying.",
            metadata={"hermes_run_id": run_id, "error_class": type(error).__name__},
        )
        await _publish_message(runtime, message, "agent.run.observation_failed")
        await runtime.event_bus.publish(
            conversation_id=conversation_id, event_type="task.updated", payload=task.model_dump(mode="json")
        )
    finally:
        runtime.run_tasks.pop(run_id, None)


def _read_vault_excerpt(runtime: AppServices, vault_path: str, limit: int = 600) -> str | None:
    """Return a short readable excerpt of a vault note, or None when unreadable."""
    try:
        path = Path(vault_path)
        if not path.is_absolute():
            path = runtime.settings.vault_path / path
        text = path.read_text(encoding="utf-8", errors="replace")[:limit]
        return " ".join(text.split())
    except OSError:
        return None


def _build_instructions(agent: AgentSummary, member_agents: list[AgentSummary]) -> str:
    """Assemble the system instructions for a crew-aware agent run."""
    parts: list[str] = []
    if agent.description:
        parts.append(agent.description)
    if agent.role:
        parts.append(f"Role: {agent.role}")
    parts.append(
        "Use the following operating instructions:\n"
        "Report only verified work and state open blockers."
    )
    teammates = [member for member in member_agents if member.id != agent.id]
    if teammates:
        parts.append(crew_context_text([(member.slug, member.role) for member in teammates]))
    return "\n\n".join(parts)


async def _start_agent_run(
    runtime: AppServices,
    *,
    conversation_id: UUID,
    agent: AgentSummary,
    task: TaskSummary,
    input_text: str,
    instructions: str,
    member_agents: list[AgentSummary],
    provider_override: str | None = None,
    model_override: str | None = None,
) -> AgentRunResponse:
    """Start a Hermes run for *task* and attach the observation monitor.

    Returns a blocked response (with durable chat records) when Hermes is
    unavailable or rejects the run; never fabricates an agent result.
    """
    health_ok, health_detail = await runtime.hermes.health()
    if not health_ok:
        blocked = await runtime.repository.set_task_terminal(task.id, status="blocked", result_summary="Hermes is not ready.")
        blocked_message = await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=task.id,
            author_type="system",
            kind="activity",
            body="Cyclone did not start the agent because Hermes is unavailable or not configured.",
            metadata={"reason": health_detail},
        )
        await _publish_message(runtime, blocked_message, "agent.run.blocked")
        return AgentRunResponse(task=blocked, status="blocked", detail=health_detail)

    await runtime.repository.set_agent_status(agent.id, "working")
    try:
        started = await runtime.hermes.start_run(
            conversation_id=conversation_id,
            input_text=input_text,
            system_instructions=instructions,
            provider=provider_override or agent.provider,
            model=model_override or agent.model,
        )
    except RuntimeError as error:
        blocked = await runtime.repository.set_task_terminal(task.id, status="blocked", result_summary="Hermes rejected or could not accept the run.")
        await runtime.repository.set_agent_status(agent.id, "blocked")
        blocked_message = await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=task.id,
            author_type="system",
            kind="activity",
            body="Cyclone did not fabricate a response: Hermes could not accept the run.",
            metadata={"error": str(error)},
        )
        await _publish_message(runtime, blocked_message, "agent.run.blocked")
        return AgentRunResponse(task=blocked, status="blocked", detail=str(error))

    active_task = await runtime.repository.set_task_run(task.id, started.run_id)
    activity = await runtime.repository.add_message(
        conversation_id=conversation_id,
        task_id=task.id,
        author_type="agent",
        author_agent_id=agent.id,
        kind="activity",
        body=f"{agent.name} started work.",
        metadata={"hermes_run_id": started.run_id, "status": started.status},
        source="hermes",
    )
    await _publish_message(runtime, activity, "agent.run.started")
    await runtime.event_bus.publish(conversation_id=conversation_id, event_type="task.updated", payload=active_task.model_dump(mode="json"))
    watcher = asyncio.create_task(
        _monitor_run(
            runtime,
            task_id=task.id,
            conversation_id=conversation_id,
            agent=agent,
            run_id=started.run_id,
            member_agents=member_agents,
        )
    )
    runtime.run_tasks[started.run_id] = watcher
    return AgentRunResponse(
        task=active_task,
        run=HermesRunStart(run_id=started.run_id, status=started.status),
        status="started",
        detail="Hermes accepted the task; Cyclone is observing its real run state.",
    )


async def _try_handoff(
    runtime: AppServices,
    *,
    conversation_id: UUID,
    from_task: TaskSummary,
    from_agent: AgentSummary,
    instruction: object,
    member_agents: list[AgentSummary],
) -> None:
    """Execute a real agent-to-agent delegation from an explicit @HANDOFF."""
    from .mentions import HandoffInstruction

    if not isinstance(instruction, HandoffInstruction):
        return
    target = next((member for member in member_agents if member.slug == instruction.to_slug), None)
    if target is None or target.id == from_agent.id:
        return  # Mention of a non-member or self: a reference, not a handoff.
    depth = await runtime.repository.handoff_depth(from_task.id)
    if depth >= MAX_HANDOFF_DEPTH:
        guard_message = await runtime.repository.add_message(
            conversation_id=conversation_id,
            task_id=from_task.id,
            author_type="system",
            kind="activity",
            body=f"Delegation stopped: {from_agent.name} reached the handoff depth limit.",
            metadata={"from_slug": from_agent.slug, "to_slug": instruction.to_slug, "depth": depth},
        )
        await _publish_message(runtime, guard_message, "agent.run.blocked")
        return
    child_task = await runtime.repository.create_task(
        conversation_id=conversation_id,
        owner_agent_id=target.id,
        title=instruction.summary[:300],
        objective=instruction.summary,
        parent_task_id=from_task.id,
        verification_criteria=instruction.acceptance_criteria,
    )
    await runtime.repository.create_handoff(
        task_id=from_task.id,
        from_agent_id=from_agent.id,
        to_agent_id=target.id,
        summary=instruction.summary,
        acceptance_criteria=instruction.acceptance_criteria,
    )
    handoff_message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        task_id=child_task.id,
        author_type="system",
        kind="handoff",
        body=f"{from_agent.name} handed work to {target.name}: {instruction.summary}",
        metadata={
            "from_slug": from_agent.slug,
            "to_slug": target.slug,
            "parent_task_id": str(from_task.id),
            "task_id": str(child_task.id),
            "acceptance_criteria": instruction.acceptance_criteria,
        },
    )
    await _publish_message(runtime, handoff_message, "handoff.created")
    instructions = _build_instructions(target, member_agents)
    instructions += (
        f"\n\n@{from_agent.slug} delegated this task to you with the following "
        f"request:\n{instruction.summary}"
    )
    await _start_agent_run(
        runtime,
        conversation_id=conversation_id,
        agent=target,
        task=child_task,
        input_text=instruction.summary,
        instructions=instructions,
        member_agents=member_agents,
    )


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


@app.get("/api/v1/agents", response_model=list[AgentSummary], tags=["agents"])
async def list_agents(runtime: AppServices = Depends(services)) -> list[AgentSummary]:
    return await runtime.repository.list_agents()


@app.post("/api/v1/agents", response_model=AgentSummary, status_code=status.HTTP_201_CREATED, tags=["agents"])
async def create_agent(request: CreateAgentRequest, runtime: AppServices = Depends(services)) -> AgentSummary:
    """Create a persistent named agent (the "spawn a teammate" flow).

    Slugs are unique; a conflicting slug is reported as a conflict rather than
    silently overwriting an existing teammate.
    """
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


@app.get("/api/v1/agents/{agent_id}/computer", response_model=ComputerSessionResponse, tags=["computers"])
async def get_agent_computer(agent_id: UUID, runtime: AppServices = Depends(services)) -> ComputerSessionResponse:
    """Return a real persisted computer descriptor or an honest 404.

    Core never synthesizes a browser preview. Browser/computer workers register a
    session record after they have an actual stream or recent-frame artifact.
    """
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
    """Switch exclusive input ownership for an existing live computer session.

    This records ownership only. A browser-worker integration must observe this
    state and pause/resume agent input; Core deliberately does not impersonate a
    desktop or send mouse/keyboard input itself.
    """
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


@app.get("/api/v1/conversations/{conversation_id}", response_model=ConversationDetail, tags=["conversations"])
async def get_conversation(conversation_id: UUID, runtime: AppServices = Depends(services)) -> ConversationDetail:
    try:
        return await runtime.repository.get_conversation(conversation_id)
    except NotFoundError as error:
        raise _not_found(error) from error


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
    """Record a human message and start a real Hermes run.

    Semantic mentions have backend meaning: an ``@slug`` reference to a
    conversation member routes the run to that agent, and the mention set is
    stored on the message so bot-to-bot references stay structured.
    """
    try:
        conversation = await runtime.repository.get_conversation(conversation_id, message_limit=1)
        requested_agent = await runtime.repository.get_agent_by_slug(request.agent_slug)
    except NotFoundError as error:
        raise _not_found(error) from error

    member_agents = [member.agent for member in conversation.members if member.agent is not None]
    member_by_slug = {member.slug: member for member in member_agents}
    mentions = parse_mentions(request.body)
    target = requested_agent
    addressed_slug = resolve_addressed_slug(request.body, set(member_by_slug))
    if addressed_slug is not None:
        addressed = member_by_slug[addressed_slug]
        if addressed.id != requested_agent.id:
            target = addressed  # The message opens with "@slug": directly addressed.
    referenced = [slug for slug in mentions if slug in member_by_slug and member_by_slug[slug].id != target.id]

    user_metadata = {"mentions": mentions} if mentions else None
    user_message = await runtime.repository.add_message(
        conversation_id=conversation_id, author_type="human", kind="message", body=request.body, metadata=user_metadata
    )
    await _publish_message(runtime, user_message)
    task = await runtime.repository.create_task(
        conversation_id=conversation_id,
        owner_agent_id=target.id,
        title=request.body[:300],
        objective=request.body,
        verification_criteria="Report only verified work and state open blockers.",
    )
    task_message = await runtime.repository.add_message(
        conversation_id=conversation_id,
        task_id=task.id,
        author_type="system",
        kind="task",
        body=f"{target.name} accepted a task: {task.title}",
        metadata={"task_id": str(task.id), "status": task.status},
    )
    await _publish_message(runtime, task_message, "task.created")

    if not request.run:
        return AgentRunResponse(task=task, user_message=user_message, status="queued", detail="Task recorded without starting a model run.")

    instructions = _build_instructions(target, member_agents)
    if referenced:
        instructions += (
            "\n\nThe user's message mentions your teammates: "
            + ", ".join(f"@{slug}" for slug in referenced)
            + ". Reference their expertise where useful; only delegate via your handoff syntax."
        )
    try:
        knowledge = await runtime.repository.search_knowledge(request.body[:300], limit=3)
    except Exception:
        knowledge = []  # Retrieval is an enhancement, never a run blocker.
    if knowledge:
        knowledge_lines = ["Relevant knowledge from the Cyclone vault:"]
        for entry in knowledge:
            excerpt = _read_vault_excerpt(runtime, entry["vault_path"])
            line = f"- {entry['title']} ({entry['vault_path']})"
            if excerpt:
                line += f"\n  {excerpt}"
            knowledge_lines.append(line)
        instructions += "\n\n" + "\n".join(knowledge_lines)
    response = await _start_agent_run(
        runtime,
        conversation_id=conversation_id,
        agent=target,
        task=task,
        input_text=request.body,
        instructions=instructions,
        member_agents=member_agents,
        provider_override=request.provider,
        model_override=request.model,
    )
    response.user_message = user_message
    return response


@app.get("/api/v1/tasks/{task_id}", response_model=TaskSummary, tags=["tasks"])
async def get_task(task_id: UUID, runtime: AppServices = Depends(services)) -> TaskSummary:
    try:
        return await runtime.repository.get_task(task_id)
    except NotFoundError as error:
        raise _not_found(error) from error


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


@app.get("/api/v1/runs/{run_id}/events", response_model=HermesRunEventsResponse, tags=["runs"])
async def documented_run_events_surface(run_id: str) -> HermesRunEventsResponse:
    """Advertise the Core-normalized run event API without pretending history exists.

    Real live activity is delivered through a conversation’s SSE endpoint. This
    endpoint returns an empty event set rather than a fabricated event log until
    Hermes run-event ingestion is enabled in the next vertical increment.
    """
    return HermesRunEventsResponse(run_id=run_id, events=[])


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
    await _publish_message(runtime, message, "approval.requested")
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
    await _publish_message(runtime, message, "approval.decided")
    await runtime.repository.add_audit_event(actor_type="human", actor_id=request.decided_by, action="approval.decided", target=approval.target, outcome=request.decision, metadata={"approval_id": str(approval.id), "capability": approval.capability})
    return approval


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
    await _publish_message(runtime, message, "automation.received")
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
    await _publish_message(runtime, message, "approval.requested")
    await runtime.repository.add_audit_event(actor_type="system", actor_id="cyclone-core", action="host_bridge.authorized", target=request.target, outcome="approval_required", metadata={"approval_id": str(approval.id), "capability": request.capability})
    return HostBridgeDecision(allowed=False, requires_approval=True, reason=decision.reason, approval=approval)
