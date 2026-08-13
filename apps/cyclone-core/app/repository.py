"""PostgreSQL repository for Cyclone's user-facing product state.

This module intentionally avoids ORM magic. Its queries map directly to the
small published schema and make each persistence boundary auditable.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any, AsyncIterator
from uuid import UUID

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from .contracts import (
    AgentSummary,
    Approval,
    ArtifactResponse,
    ComputerSessionResponse,
    ConversationDetail,
    ConversationMember,
    ConversationSummary,
    InboxItem,
    Mention,
    Message,
    ReactionResponse,
    TaskSummary,
)
from .fts import fts_query_terms


class NotFoundError(LookupError):
    """Raised where the API should return a stable resource-not-found response."""


class Repository:
    def __init__(self, database_url: str) -> None:
        self._database_url = database_url
        self._pool: AsyncConnectionPool | None = None

    async def open(self) -> None:
        if self._pool is None:
            self._pool = AsyncConnectionPool(
                conninfo=self._database_url,
                kwargs={"row_factory": dict_row},
                min_size=1,
                max_size=8,
                open=False,
            )
            await self._pool.open()
            await self._pool.wait(timeout=15)

    async def close(self) -> None:
        if self._pool is not None:
            await self._pool.close()
            self._pool = None

    @asynccontextmanager
    async def connection(self) -> AsyncIterator[psycopg.AsyncConnection[Any]]:
        if self._pool is None:
            raise RuntimeError("Repository was used before startup")
        async with self._pool.connection() as connection:
            yield connection

    async def ping(self) -> bool:
        try:
            async with self.connection() as connection:
                await connection.execute("SELECT 1")
            return True
        except Exception:
            return False

    @staticmethod
    def _agent(row: dict[str, Any]) -> AgentSummary:
        return AgentSummary(
            id=row["id"], slug=row["slug"], name=row["name"], role=row["role"],
            description=row["description"], avatar_color=row["avatar_color"],
            avatar_shape=row.get("avatar_shape"),
            status=row["status"], provider=row["provider"], model=row["model"],
            hermes_profile=row["hermes_profile"], workspace_path=row["workspace_path"],
        )

    @staticmethod
    def _computer_session(row: dict[str, Any]) -> ComputerSessionResponse:
        return ComputerSessionResponse(
            id=str(row["id"]),
            agent_id=str(row["agent_id"]),
            status=row["status"],
            instruction=row["instruction"],
            stream_url=row["stream_url"],
            recent_frame_url=row["recent_frame_url"],
            owner=row["owner_type"],
            updated_at=row["updated_at"],
        )

    @staticmethod
    def _task(row: dict[str, Any]) -> TaskSummary:
        return TaskSummary(
            id=row["id"], conversation_id=row["conversation_id"], parent_task_id=row["parent_task_id"],
            owner_agent_id=row["owner_agent_id"], title=row["title"], objective=row["objective"],
            status=row["status"], priority=row["priority"], hermes_run_id=row["hermes_run_id"],
            result_summary=row["result_summary"], verification_criteria=row["verification_criteria"],
            created_at=row["created_at"], started_at=row["started_at"], completed_at=row["completed_at"],
            updated_at=row["updated_at"],
        )

    @staticmethod
    def _approval(row: dict[str, Any]) -> Approval:
        return Approval(
            id=row["id"], conversation_id=row["conversation_id"], task_id=row["task_id"],
            requested_by_agent_id=row["requested_by_agent_id"], capability=row["capability"],
            target=row["target"], scope=row["scope"], expected_effect=row["expected_effect"],
            policy_reason=row["policy_reason"], status=row["status"], decided_by=row["decided_by"],
            decided_at=row["decided_at"], expires_at=row["expires_at"], created_at=row["created_at"],
        )

    @staticmethod
    def _message(row: dict[str, Any]) -> Message:
        return Message(
            id=row["id"], conversation_id=row["conversation_id"], task_id=row["task_id"],
            reply_to_message_id=row.get("reply_to_message_id"),
            author_type=row["author_type"], author_agent_id=row["author_agent_id"], author_name=row["author_name"],
            kind=row["kind"], body=row["body"], metadata=row["metadata"], source=row["source"],
            mentions=row.get("mentions") or [],
            created_at=row["created_at"],
        )

    @staticmethod
    def _mention(row: dict[str, Any]) -> Mention:
        return Mention(
            id=row["id"], mention_type=row["mention_type"],
            target_agent_id=row.get("target_agent_id"), target_slug=row.get("target_slug"),
            position_start=row.get("position_start"), position_end=row.get("position_end"),
        )

    async def list_agents(self) -> list[AgentSummary]:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, slug, name, role, description, avatar_color, avatar_shape, status,
                       provider, model, hermes_profile, workspace_path
                FROM agents ORDER BY name
            """)
            return [self._agent(row) for row in await result.fetchall()]

    async def get_agent_by_slug(self, slug: str) -> AgentSummary:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, slug, name, role, description, avatar_color, avatar_shape, status,
                       provider, model, hermes_profile, workspace_path
                FROM agents WHERE slug = %s
            """, (slug,))
            row = await result.fetchone()
        if row is None:
            raise NotFoundError(f"Agent '{slug}' was not found")
        return self._agent(row)

    async def get_agent_by_id(self, agent_id: UUID) -> AgentSummary:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, slug, name, role, description, avatar_color, avatar_shape, status,
                       provider, model, hermes_profile, workspace_path
                FROM agents WHERE id = %s
            """, (agent_id,))
            row = await result.fetchone()
        if row is None:
            raise NotFoundError("Agent was not found")
        return self._agent(row)

    async def create_agent(
        self,
        *,
        slug: str,
        name: str,
        role: str,
        description: str,
        avatar_color: str,
        avatar_shape: str | None,
        provider: str | None,
        model: str | None,
        hermes_profile: str,
        workspace_path: str,
    ) -> AgentSummary:
        async with self.connection() as connection:
            result = await connection.execute(
                """
                INSERT INTO agents
                  (slug, name, role, description, avatar_color, avatar_shape,
                   provider, model, hermes_profile, workspace_path)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                RETURNING id, slug, name, role, description, avatar_color, avatar_shape, status,
                          provider, model, hermes_profile, workspace_path
                """,
                (slug, name, role, description, avatar_color, avatar_shape,
                 provider, model, hermes_profile, workspace_path),
            )
            row = await result.fetchone()
            await connection.commit()
        if row is None:
            raise NotFoundError("Agent could not be created")
        return self._agent(row)

    async def set_agent_status(self, agent_id: UUID, status: str) -> None:
        async with self.connection() as connection:
            await connection.execute("UPDATE agents SET status = %s WHERE id = %s", (status, agent_id))
            await connection.commit()

    async def get_latest_computer_session(self, agent_id: UUID) -> ComputerSessionResponse:
        async with self.connection() as connection:
            result = await connection.execute(
                """
                SELECT id, agent_id, status, instruction, stream_url, recent_frame_url, owner_type, updated_at
                FROM computer_sessions
                WHERE agent_id = %s
                ORDER BY updated_at DESC
                LIMIT 1
                """,
                (agent_id,),
            )
            row = await result.fetchone()
        if row is None:
            raise NotFoundError("No live or recent computer session exists for this agent")
        return self._computer_session(row)

    async def set_computer_session_owner(
        self, session_id: UUID, owner: str
    ) -> ComputerSessionResponse:
        async with self.connection() as connection:
            result = await connection.execute(
                """
                UPDATE computer_sessions
                SET owner_type = %s
                WHERE id = %s
                RETURNING id, agent_id, status, instruction, stream_url, recent_frame_url, owner_type, updated_at
                """,
                (owner, session_id),
            )
            row = await result.fetchone()
            await connection.commit()
        if row is None:
            raise NotFoundError("Computer session was not found")
        return self._computer_session(row)

    async def list_conversations(self) -> list[ConversationSummary]:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT c.id, c.title, c.kind, c.project_key, c.updated_at, latest.body AS latest_preview
                FROM conversations AS c
                LEFT JOIN LATERAL (
                    SELECT body FROM messages WHERE conversation_id = c.id
                    ORDER BY created_at DESC LIMIT 1
                ) AS latest ON TRUE
                ORDER BY c.updated_at DESC
            """)
            rows = await result.fetchall()
        return [ConversationSummary(
            id=row["id"], title=row["title"], kind=row["kind"], project_key=row["project_key"],
            updated_at=row["updated_at"], latest_preview=row["latest_preview"],
        ) for row in rows]

    async def create_conversation(self, *, title: str, kind: str, project_key: str | None, agent_slugs: list[str], hermes_conversation_key: str | None = None) -> ConversationDetail:
        async with self.connection() as connection:
            agent_result = await connection.execute("""
                SELECT id, slug, name, role, description, avatar_color, avatar_shape, status,
                       provider, model, hermes_profile, workspace_path
                FROM agents WHERE slug = ANY(%s)
            """, (agent_slugs,))
            agents = [self._agent(row) for row in await agent_result.fetchall()]
            missing = sorted(set(agent_slugs) - {agent.slug for agent in agents})
            if missing:
                raise NotFoundError(f"Unknown agent slugs: {', '.join(missing)}")
            id_result = await connection.execute("SELECT gen_random_uuid() AS id")
            generated = await id_result.fetchone()
            key = hermes_conversation_key or f"cyclone-{generated['id']}"
            created = await connection.execute("""
                INSERT INTO conversations (title, kind, project_key, hermes_conversation_key)
                VALUES (%s, %s, %s, %s) RETURNING id
            """, (title, kind, project_key, key))
            conversation = await created.fetchone()
            for agent in agents:
                await connection.execute("""
                    INSERT INTO conversation_members
                      (conversation_id, agent_id, member_type, display_name, member_role)
                    VALUES (%s, %s, 'agent', %s, %s)
                """, (conversation["id"], agent.id, agent.name, "chief" if agent.slug == "chief" else "member"))
            await connection.commit()
        return await self.get_conversation(conversation["id"])

    async def get_conversation(self, conversation_id: UUID, message_limit: int = 200) -> ConversationDetail:
        async with self.connection() as connection:
            details = await connection.execute("""
                SELECT id, title, kind, project_key, hermes_conversation_key, created_at, updated_at
                FROM conversations WHERE id = %s
            """, (conversation_id,))
            conversation = await details.fetchone()
            if conversation is None:
                raise NotFoundError("Conversation was not found")
            members_result = await connection.execute("""
                SELECT cm.display_name, cm.member_type, cm.member_role,
                       a.id AS agent_id, a.slug, a.name, a.role, a.description,
                       a.avatar_color, a.avatar_shape, a.status, a.provider, a.model, a.hermes_profile, a.workspace_path
                FROM conversation_members cm LEFT JOIN agents a ON a.id = cm.agent_id
                WHERE cm.conversation_id = %s ORDER BY cm.created_at
            """, (conversation_id,))
            members: list[ConversationMember] = []
            for row in await members_result.fetchall():
                agent = self._agent({
                    "id": row["agent_id"], "slug": row["slug"], "name": row["name"], "role": row["role"],
                    "description": row["description"], "avatar_color": row["avatar_color"], "avatar_shape": row["avatar_shape"], "status": row["status"],
                    "provider": row["provider"], "model": row["model"], "hermes_profile": row["hermes_profile"],
                    "workspace_path": row["workspace_path"],
                }) if row["agent_id"] else None
                members.append(ConversationMember(
                    display_name=row["display_name"], member_type=row["member_type"],
                    member_role=row["member_role"], agent=agent,
                ))
            messages_result = await connection.execute("""
                SELECT m.id, m.conversation_id, m.task_id, m.author_type, m.author_agent_id, m.reply_to_message_id,
                       COALESCE(a.name, CASE WHEN m.author_type = 'human' THEN 'You'
                         WHEN m.author_type = 'automation' THEN 'Automation' ELSE 'Cyclone' END) AS author_name,
                       m.kind, m.body, m.metadata, m.source, m.created_at
                FROM messages m LEFT JOIN agents a ON a.id = m.author_agent_id
                WHERE m.conversation_id = %s ORDER BY m.created_at ASC LIMIT %s
            """, (conversation_id, message_limit))
            message_rows = await messages_result.fetchall()
            message_ids = [row["id"] for row in message_rows]
        mention_map = await self.get_mentions_for_messages(message_ids)
        messages = [self._message({**row, "mentions": mention_map.get(row["id"], [])}) for row in message_rows]
        return ConversationDetail(
            id=conversation["id"], title=conversation["title"], kind=conversation["kind"],
            project_key=conversation["project_key"], hermes_conversation_key=conversation["hermes_conversation_key"],
            created_at=conversation["created_at"], updated_at=conversation["updated_at"],
            members=members, messages=messages,
        )

    async def add_message(self, *, conversation_id: UUID, author_type: str, body: str, kind: str = "message", author_agent_id: UUID | None = None, task_id: UUID | None = None, metadata: dict[str, Any] | None = None, source: str = "cyclone", reply_to_message_id: UUID | None = None) -> Message:
        async with self.connection() as connection:
            result = await connection.execute("""
                INSERT INTO messages
                  (conversation_id, task_id, author_type, author_agent_id, kind, body, metadata, source, reply_to_message_id)
                VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb, %s, %s)
                RETURNING id, conversation_id, task_id, author_type, author_agent_id, reply_to_message_id,
                          kind, body, metadata, source, created_at
            """, (conversation_id, task_id, author_type, author_agent_id, kind, body, psycopg.types.json.Jsonb(metadata or {}), source, reply_to_message_id))
            row = await result.fetchone()
            await connection.execute("UPDATE conversations SET updated_at = now() WHERE id = %s", (conversation_id,))
            await connection.commit()
        author_name = "You" if author_type == "human" else "Automation" if author_type == "automation" else "Cyclone"
        if author_agent_id:
            author_name = (await self.get_agent_by_id(author_agent_id)).name
        return self._message({**row, "author_name": author_name})

    async def add_mentions(self, message_id: UUID, mentions: list[dict[str, Any]]) -> list[Mention]:
        """Persist semantic mention objects for a message.

        Each dict: {mention_type, target_agent_id?, target_slug?, position_start?, position_end?}
        """
        if not mentions:
            return []
        async with self.connection() as connection:
            rows = []
            for mention in mentions:
                result = await connection.execute("""
                    INSERT INTO message_mentions
                      (message_id, mention_type, target_agent_id, target_slug, position_start, position_end)
                    VALUES (%s, %s, %s, %s, %s, %s)
                    RETURNING id, mention_type, target_agent_id, target_slug, position_start, position_end
                """, (
                    message_id,
                    mention["mention_type"],
                    mention.get("target_agent_id"),
                    mention.get("target_slug"),
                    mention.get("position_start"),
                    mention.get("position_end"),
                ))
                rows.append(await result.fetchone())
            await connection.commit()
        return [self._mention(row) for row in rows]

    async def get_mentions_for_messages(self, message_ids: list[UUID]) -> dict[UUID, list[Mention]]:
        if not message_ids:
            return {}
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, message_id, mention_type, target_agent_id, target_slug, position_start, position_end
                FROM message_mentions WHERE message_id = ANY(%s) ORDER BY position_start NULLS LAST
            """, (message_ids,))
            rows = await result.fetchall()
        grouped: dict[UUID, list[Mention]] = {}
        for row in rows:
            grouped.setdefault(row["message_id"], []).append(self._mention(row))
        return grouped

    async def create_task(self, *, conversation_id: UUID, owner_agent_id: UUID | None, title: str, objective: str, status: str = "queued", parent_task_id: UUID | None = None, verification_criteria: str | None = None) -> TaskSummary:
        async with self.connection() as connection:
            result = await connection.execute("""
                INSERT INTO tasks
                  (conversation_id, parent_task_id, owner_agent_id, title, objective, status, verification_criteria)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                RETURNING id, conversation_id, parent_task_id, owner_agent_id, title,
                          objective, status, priority, hermes_run_id, result_summary,
                          verification_criteria, created_at, started_at, completed_at, updated_at
            """, (conversation_id, parent_task_id, owner_agent_id, title, objective, status, verification_criteria))
            row = await result.fetchone()
            await connection.commit()
        return self._task(row)

    async def get_task(self, task_id: UUID) -> TaskSummary:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, conversation_id, parent_task_id, owner_agent_id, title, objective,
                       status, priority, hermes_run_id, result_summary, verification_criteria,
                       created_at, started_at, completed_at, updated_at FROM tasks WHERE id = %s
            """, (task_id,))
            row = await result.fetchone()
        if row is None:
            raise NotFoundError("Task was not found")
        return self._task(row)

    async def set_task_run(self, task_id: UUID, hermes_run_id: str) -> TaskSummary:
        async with self.connection() as connection:
            result = await connection.execute("""
                UPDATE tasks SET hermes_run_id = %s, status = 'running', started_at = COALESCE(started_at, now())
                WHERE id = %s
                RETURNING id, conversation_id, parent_task_id, owner_agent_id, title, objective,
                          status, priority, hermes_run_id, result_summary, verification_criteria,
                          created_at, started_at, completed_at, updated_at
            """, (hermes_run_id, task_id))
            row = await result.fetchone()
            await connection.commit()
        if row is None:
            raise NotFoundError("Task was not found")
        return self._task(row)

    async def set_task_terminal(self, task_id: UUID, *, status: str, result_summary: str | None) -> TaskSummary:
        async with self.connection() as connection:
            result = await connection.execute("""
                UPDATE tasks SET status = %s, result_summary = %s, completed_at = now() WHERE id = %s
                RETURNING id, conversation_id, parent_task_id, owner_agent_id, title, objective,
                          status, priority, hermes_run_id, result_summary, verification_criteria,
                          created_at, started_at, completed_at, updated_at
            """, (status, result_summary, task_id))
            row = await result.fetchone()
            await connection.commit()
        if row is None:
            raise NotFoundError("Task was not found")
        return self._task(row)

    async def create_approval(self, *, conversation_id: UUID, task_id: UUID | None, requested_by_agent_id: UUID | None, capability: str, target: str, scope: dict[str, Any], expected_effect: str, policy_reason: str, expires_at: datetime | None) -> Approval:
        async with self.connection() as connection:
            result = await connection.execute("""
                INSERT INTO approvals
                  (conversation_id, task_id, requested_by_agent_id, capability, target, scope,
                   expected_effect, policy_reason, expires_at)
                VALUES (%s, %s, %s, %s, %s, %s::jsonb, %s, %s, %s)
                RETURNING id, conversation_id, task_id, requested_by_agent_id, capability, target,
                          scope, expected_effect, policy_reason, status, decided_by, decided_at, expires_at, created_at
            """, (conversation_id, task_id, requested_by_agent_id, capability, target, psycopg.types.json.Jsonb(scope), expected_effect, policy_reason, expires_at))
            row = await result.fetchone()
            await connection.commit()
        return self._approval(row)

    async def decide_approval(self, approval_id: UUID, *, decision: str, decided_by: str) -> Approval:
        async with self.connection() as connection:
            result = await connection.execute("""
                UPDATE approvals SET status = %s, decided_by = %s, decided_at = now()
                WHERE id = %s AND status = 'pending'
                RETURNING id, conversation_id, task_id, requested_by_agent_id, capability, target,
                          scope, expected_effect, policy_reason, status, decided_by, decided_at, expires_at, created_at
            """, (decision, decided_by, approval_id))
            row = await result.fetchone()
            await connection.commit()
        if row is None:
            raise NotFoundError("Pending approval was not found")
        return self._approval(row)

    async def find_automation_event(self, external_event_id: str) -> UUID | None:
        async with self.connection() as connection:
            result = await connection.execute("SELECT id FROM automation_events WHERE external_event_id = %s", (external_event_id,))
            row = await result.fetchone()
        return row["id"] if row else None

    async def record_automation_event(self, *, external_event_id: str, event_type: str, payload: dict[str, Any], routine_slug: str | None) -> tuple[UUID, UUID | None]:
        async with self.connection() as connection:
            routine_id = None
            if routine_slug:
                routine_result = await connection.execute("SELECT id FROM routines WHERE slug = %s", (routine_slug,))
                routine = await routine_result.fetchone()
                if routine:
                    routine_id = routine["id"]
            result = await connection.execute("""
                INSERT INTO automation_events (routine_id, external_event_id, event_type, payload)
                VALUES (%s, %s, %s, %s::jsonb) RETURNING id, routine_id
            """, (routine_id, external_event_id, event_type, psycopg.types.json.Jsonb(payload)))
            row = await result.fetchone()
            await connection.commit()
        return row["id"], row["routine_id"]

    async def add_audit_event(self, *, actor_type: str, actor_id: str, action: str, target: str, outcome: str, metadata: dict[str, Any] | None = None) -> None:
        async with self.connection() as connection:
            await connection.execute("""
                INSERT INTO audit_events (actor_type, actor_id, action, target, outcome, metadata)
                VALUES (%s, %s, %s, %s, %s, %s::jsonb)
            """, (actor_type, actor_id, action, target, outcome, psycopg.types.json.Jsonb(metadata or {})))
            await connection.commit()

    async def create_knowledge_entry(self, *, vault_path: str, title: str, category: str, project_key: str | None, agent_id: UUID | None, content: str, content_fingerprint: str, source_conversation_id: UUID | None) -> dict[str, Any]:
        async with self.connection() as connection:
            result = await connection.execute("""
                INSERT INTO knowledge_entries
                  (vault_path, title, category, project_key, agent_id, keywords, content_fingerprint, source_conversation_id)
                VALUES (%s, %s, %s, %s, %s, to_tsvector('simple', %s), %s, %s)
                ON CONFLICT (vault_path) DO UPDATE SET title = EXCLUDED.title, category = EXCLUDED.category,
                    project_key = EXCLUDED.project_key, agent_id = EXCLUDED.agent_id, keywords = EXCLUDED.keywords,
                    content_fingerprint = EXCLUDED.content_fingerprint, source_conversation_id = EXCLUDED.source_conversation_id,
                    updated_at = now()
                RETURNING id, vault_path, title, category, project_key, agent_id, created_at, updated_at
            """, (vault_path, title, category, project_key, agent_id, f"{title}\n{content}", content_fingerprint, source_conversation_id))
            row = await result.fetchone()
            await connection.commit()
        return row

    async def search_knowledge(self, query: str, limit: int = 10) -> list[dict[str, Any]]:
        query_terms = fts_query_terms(query)
        if not query_terms:
            return []
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, vault_path, title, category, project_key, agent_id, created_at, updated_at
                FROM knowledge_entries WHERE keywords @@ to_tsquery('simple', %s)
                ORDER BY ts_rank(keywords, to_tsquery('simple', %s)) DESC, updated_at DESC LIMIT %s
            """, (query_terms, query_terms, limit))
            return await result.fetchall()

    async def default_conversation_id(self) -> UUID:
        async with self.connection() as connection:
            result = await connection.execute("SELECT id FROM conversations WHERE hermes_conversation_key = 'cyclone-welcome-chief'")
            row = await result.fetchone()
        if row is None:
            raise NotFoundError("Cyclone Welcome conversation was not found")
        return row["id"]

    async def mark_automation_processed(self, event_id: UUID) -> None:
        async with self.connection() as connection:
            await connection.execute("UPDATE automation_events SET status = 'processed', processed_at = now() WHERE id = %s", (event_id,))
            await connection.commit()

    async def status_counts(self) -> dict[str, int]:
        async with self.connection() as connection:
            result = await connection.execute("SELECT status::text, count(*) AS total FROM tasks GROUP BY status")
            return {row["status"]: row["total"] for row in await result.fetchall()}

    async def create_handoff(
        self,
        *,
        task_id: UUID,
        from_agent_id: UUID,
        to_agent_id: UUID,
        summary: str,
        acceptance_criteria: str | None = None,
    ) -> dict[str, Any]:
        """Record a durable agent-to-agent delegation event."""
        async with self.connection() as connection:
            result = await connection.execute(
                """
                INSERT INTO handoffs (task_id, from_agent_id, to_agent_id, summary, acceptance_criteria)
                VALUES (%s, %s, %s, %s, %s)
                RETURNING id, task_id, from_agent_id, to_agent_id, summary, acceptance_criteria, created_at
                """,
                (task_id, from_agent_id, to_agent_id, summary, acceptance_criteria),
            )
            row = await result.fetchone()
            await connection.commit()
        return row

    async def handoff_depth(self, task_id: UUID, limit: int = 12) -> int:
        """Count handoffs in *task_id*'s ancestor chain (delegation-loop guard)."""
        async with self.connection() as connection:
            chain: list[UUID] = []
            current: UUID | None = task_id
            while current is not None and len(chain) < limit:
                chain.append(current)
                result = await connection.execute(
                    "SELECT parent_task_id FROM tasks WHERE id = %s", (current,)
                )
                row = await result.fetchone()
                current = row["parent_task_id"] if row else None
            if not chain:
                return 0
            result = await connection.execute(
                "SELECT count(*) AS total FROM handoffs WHERE task_id = ANY(%s)", (chain,)
            )
            row = await result.fetchone()
        return int(row["total"])

    # --- Agent inbox -------------------------------------------------------

    async def enqueue_inbox_item(
        self,
        *,
        agent_id: UUID,
        event_type: str,
        conversation_id: UUID | None = None,
        message_id: UUID | None = None,
        task_id: UUID | None = None,
        source_agent_id: UUID | None = None,
        payload: dict[str, Any] | None = None,
    ) -> InboxItem:
        async with self.connection() as connection:
            result = await connection.execute("""
                INSERT INTO agent_inbox
                  (agent_id, event_type, conversation_id, message_id, task_id, source_agent_id, payload)
                VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb)
                RETURNING id, agent_id, event_type, conversation_id, message_id, task_id,
                          source_agent_id, payload, status, attempts, created_at, delivered_at
            """, (agent_id, event_type, conversation_id, message_id, task_id, source_agent_id, psycopg.types.json.Jsonb(payload or {})))
            row = await result.fetchone()
            await connection.commit()
        return InboxItem(**row)

    async def list_agent_inbox(self, agent_id: UUID, limit: int = 50, only_pending: bool = False) -> list[InboxItem]:
        async with self.connection() as connection:
            query = """
                SELECT id, agent_id, event_type, conversation_id, message_id, task_id,
                       source_agent_id, payload, status, attempts, created_at, delivered_at
                FROM agent_inbox WHERE agent_id = %s
            """
            params: list[Any] = [agent_id]
            if only_pending:
                query += " AND status IN ('pending', 'processing')"
            query += " ORDER BY created_at DESC LIMIT %s"
            params.append(limit)
            result = await connection.execute(query, params)
            return [InboxItem(**row) for row in await result.fetchall()]

    async def claim_inbox_item(self, item_id: UUID, max_attempts: int = 3) -> InboxItem | None:
        """Atomically claim a pending item; returns None when not claimable."""
        async with self.connection() as connection:
            result = await connection.execute("""
                UPDATE agent_inbox
                SET status = 'processing', attempts = attempts + 1, delivered_at = now()
                WHERE id = %s AND status = 'pending' AND attempts < %s
                RETURNING id, agent_id, event_type, conversation_id, message_id, task_id,
                          source_agent_id, payload, status, attempts, created_at, delivered_at
            """, (item_id, max_attempts))
            row = await result.fetchone()
            await connection.commit()
        return InboxItem(**row) if row else None

    async def mark_inbox_terminal(self, item_id: UUID, status: str, error: str | None = None) -> None:
        async with self.connection() as connection:
            payload_extra = {"error": error} if error else {}
            await connection.execute(
                "UPDATE agent_inbox SET status = %s, payload = payload || %s::jsonb WHERE id = %s",
                (status, psycopg.types.json.Jsonb(payload_extra), item_id),
            )
            await connection.commit()

    async def pending_inbox_items(self, limit: int = 100) -> list[InboxItem]:
        """Recovery sweep source: items that never reached a terminal state."""
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, agent_id, event_type, conversation_id, message_id, task_id,
                       source_agent_id, payload, status, attempts, created_at, delivered_at
                FROM agent_inbox
                WHERE status IN ('pending', 'processing')
                ORDER BY created_at ASC LIMIT %s
            """, (limit,))
            return [InboxItem(**row) for row in await result.fetchall()]

    # --- Reactions ---------------------------------------------------------

    async def add_reaction(self, *, message_id: UUID, actor_type: str, actor_agent_id: UUID | None, emoji: str) -> ReactionResponse | None:
        async with self.connection() as connection:
            result = await connection.execute("""
                INSERT INTO reactions (message_id, actor_type, actor_agent_id, emoji)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (message_id, actor_type, actor_agent_id, emoji) DO NOTHING
                RETURNING id, message_id, actor_type, actor_agent_id, emoji, created_at
            """, (message_id, actor_type, actor_agent_id, emoji))
            row = await result.fetchone()
            await connection.commit()
        return ReactionResponse(**row) if row else None

    async def remove_reaction(self, *, message_id: UUID, actor_type: str, actor_agent_id: UUID | None, emoji: str) -> None:
        async with self.connection() as connection:
            await connection.execute(
                "DELETE FROM reactions WHERE message_id = %s AND actor_type = %s AND actor_agent_id IS NOT DISTINCT FROM %s AND emoji = %s",
                (message_id, actor_type, actor_agent_id, emoji),
            )
            await connection.commit()

    async def list_reactions(self, message_id: UUID) -> list[ReactionResponse]:
        async with self.connection() as connection:
            result = await connection.execute(
                "SELECT id, message_id, actor_type, actor_agent_id, emoji, created_at FROM reactions WHERE message_id = %s",
                (message_id,),
            )
            return [ReactionResponse(**row) for row in await result.fetchall()]

    # --- Task dependencies -------------------------------------------------

    async def add_task_dependency(self, task_id: UUID, depends_on_task_id: UUID) -> None:
        async with self.connection() as connection:
            await connection.execute(
                "INSERT INTO task_dependencies (task_id, depends_on_task_id) VALUES (%s, %s) ON CONFLICT DO NOTHING",
                (task_id, depends_on_task_id),
            )
            await connection.commit()

    async def list_task_dependencies(self, task_id: UUID) -> list[UUID]:
        async with self.connection() as connection:
            result = await connection.execute(
                "SELECT depends_on_task_id FROM task_dependencies WHERE task_id = %s",
                (task_id,),
            )
            return [row["depends_on_task_id"] for row in await result.fetchall()]

    # --- Artifacts ---------------------------------------------------------

    async def create_artifact(self, *, task_id: UUID | None, conversation_id: UUID | None, created_by_agent_id: UUID | None, type_: str, path: str) -> ArtifactResponse:
        async with self.connection() as connection:
            result = await connection.execute("""
                INSERT INTO artifacts (task_id, conversation_id, created_by_agent_id, type, path)
                VALUES (%s, %s, %s, %s, %s)
                RETURNING id, task_id, conversation_id, created_by_agent_id, type, path, created_at
            """, (task_id, conversation_id, created_by_agent_id, type_, path))
            row = await result.fetchone()
            await connection.commit()
        return ArtifactResponse(**row)

    async def list_artifacts(self, task_id: UUID) -> list[ArtifactResponse]:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, task_id, conversation_id, created_by_agent_id, type, path, created_at
                FROM artifacts WHERE task_id = %s ORDER BY created_at
            """, (task_id,))
            return [ArtifactResponse(**row) for row in await result.fetchall()]

    # --- Membership --------------------------------------------------------

    async def add_conversation_member(self, conversation_id: UUID, agent: AgentSummary, member_role: str = "member") -> None:
        async with self.connection() as connection:
            await connection.execute("""
                INSERT INTO conversation_members (conversation_id, agent_id, member_type, display_name, member_role)
                VALUES (%s, %s, 'agent', %s, %s)
                ON CONFLICT (conversation_id, member_type, display_name) DO NOTHING
            """, (conversation_id, agent.id, agent.name, member_role))
            await connection.commit()

    async def remove_conversation_member(self, conversation_id: UUID, agent_id: UUID) -> bool:
        async with self.connection() as connection:
            result = await connection.execute(
                "DELETE FROM conversation_members WHERE conversation_id = %s AND agent_id = %s AND member_type = 'agent'",
                (conversation_id, agent_id),
            )
            await connection.commit()
            return result.rowcount > 0

    # --- Task state --------------------------------------------------------

    async def set_task_status(self, task_id: UUID, status: str) -> TaskSummary:
        async with self.connection() as connection:
            result = await connection.execute("""
                UPDATE tasks SET status = %s,
                  completed_at = CASE WHEN %s IN ('completed', 'failed', 'cancelled') THEN COALESCE(completed_at, now()) ELSE completed_at END
                WHERE id = %s
                RETURNING id, conversation_id, parent_task_id, owner_agent_id, title, objective,
                          status, priority, hermes_run_id, result_summary, verification_criteria,
                          created_at, started_at, completed_at, updated_at
            """, (status, status, task_id))
            row = await result.fetchone()
            await connection.commit()
        if row is None:
            raise NotFoundError("Task was not found")
        return self._task(row)

    async def list_telegram_conversations(self) -> list[tuple[UUID, str]]:
        """(id, hermes_conversation_key) for every Telegram channel conversation."""
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, hermes_conversation_key FROM conversations
                WHERE kind = 'telegram' AND hermes_conversation_key IS NOT NULL
                ORDER BY created_at ASC
            """)
            rows = await result.fetchall()
        return [(row["id"], row["hermes_conversation_key"]) for row in rows]

    async def get_active_task(self, conversation_id: UUID) -> TaskSummary | None:
        """Most recent non-terminal task in the conversation (routing signal)."""
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, conversation_id, parent_task_id, owner_agent_id, title, objective,
                       status, priority, hermes_run_id, result_summary, verification_criteria,
                       created_at, started_at, completed_at, updated_at
                FROM tasks
                WHERE conversation_id = %s AND status NOT IN ('completed', 'failed', 'cancelled')
                ORDER BY created_at DESC LIMIT 1
            """, (conversation_id,))
            row = await result.fetchone()
        return self._task(row) if row else None

    async def list_agent_routines(self, agent_id: UUID) -> list[dict[str, Any]]:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, slug, name, description, owner_agent_id, instructions,
                       n8n_workflow_id, trigger_config, enabled, created_at, updated_at
                FROM routines WHERE owner_agent_id = %s ORDER BY created_at DESC
            """, (agent_id,))
            return await result.fetchall()

    async def upsert_telegram_user(self, *, chat_id: int, display_name: str, initials: str) -> UUID:
        """Create or update the Cyclone user record for a Telegram chat."""
        async with self.connection() as connection:
            result = await connection.execute("""
                INSERT INTO users (display_name, initials, telegram_chat_id)
                VALUES (%s, %s, %s)
                ON CONFLICT (telegram_chat_id) DO UPDATE
                  SET display_name = EXCLUDED.display_name, initials = EXCLUDED.initials,
                      updated_at = now()
                RETURNING id
            """, (display_name, initials, chat_id))
            row = await result.fetchone()
            return row["id"]

    async def get_telegram_user(self, chat_id: int) -> dict[str, Any] | None:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, display_name, initials, telegram_chat_id, created_at, updated_at
                FROM users WHERE telegram_chat_id = %s
            """, (chat_id,))
            return await result.fetchone()

    async def list_users(self, limit: int = 20) -> list[dict[str, Any]]:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id, display_name, initials, telegram_chat_id, created_at, updated_at
                FROM users ORDER BY created_at ASC LIMIT %s
            """, (limit,))
            return await result.fetchall()

    async def find_conversation_by_key(self, hermes_conversation_key: str) -> ConversationDetail | None:
        async with self.connection() as connection:
            result = await connection.execute("""
                SELECT id FROM conversations WHERE hermes_conversation_key = %s LIMIT 1
            """, (hermes_conversation_key,))
            row = await result.fetchone()
        if not row:
            return None
        return await self.get_conversation(UUID(str(row["id"])), message_limit=1)
