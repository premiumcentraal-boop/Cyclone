"""Telegram ingress: the operator's remote channel into the Cyclone agent mesh.

One bot, one pipeline: a Telegram message becomes a real human message in a
real Cyclone conversation, goes through the same mention routing / wake path
as the desktop UI, and agent results flow back into the same Telegram chat.
No separate Telegram agent architecture (DESIGN: Telegram uses the same
Cyclone/Hermes agent network).
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any
from uuid import UUID

import httpx

logger = logging.getLogger("cyclone.telegram")

API_BASE = "https://api.telegram.org/bot{token}"
MAX_MESSAGE_LENGTH = 3500
BOT_COMMANDS = [
    {"command": "help", "description": "How to direct work"},
    {"command": "agents", "description": "Show the live agent roster"},
    {"command": "agent", "description": "Ask a specific agent"},
]


def _initials(name: str) -> str:
    words = [word for word in name.strip().split() if word]
    return "".join(word[0] for word in words[:2]).upper() or "T"


class TelegramWorker:
    def __init__(
        self,
        runtime: Any,
        token: str,
        allowed_users: list[int] | None = None,
        home_channel: str | None = None,
    ) -> None:
        self.runtime = runtime
        self.token = token
        self.allowed_users = set(allowed_users or [])
        self.home_channel = _chat_id(home_channel)
        self.base = API_BASE.format(token=token)
        self._offset: int | None = None
        self._active_conversations: set[UUID] = set()
        self._subscriptions: dict[UUID, Any] = {}
        # conversation_id -> run_id awaiting a human decision (Telegram answers).
        self._pending_approvals: dict[UUID, str] = {}

    # ------------------------------------------------------------------ api
    async def _api(self, method: str, **payload: Any) -> dict[str, Any]:
        async with httpx.AsyncClient(timeout=25.0) as client:
            response = await client.post(f"{self.base}/{method}", json=payload)
        if response.status_code >= 400:
            logger.warning("telegram %s failed: HTTP %s %s", method, response.status_code, response.text[:200])
            return {"ok": False}
        return response.json()

    async def get_me(self) -> dict[str, Any] | None:
        result = await self._api("getMe")
        return result.get("result") if result.get("ok") else None

    async def send_message(self, chat_id: int, text: str, reply_to: int | None = None) -> None:
        for chunk in _chunk_text(text):
            await self._api(
                "sendMessage",
                chat_id=chat_id,
                text=chunk,
                parse_mode="HTML",
                disable_web_page_preview=True,
                **( {"reply_to_message_id": reply_to} if reply_to else {}),
            )

    async def get_updates(self) -> list[dict[str, Any]]:
        payload: dict[str, Any] = {"timeout": 25, "allowed_updates": ["message"]}
        if self._offset is not None:
            payload["offset"] = self._offset
        result = await self._api("getUpdates", **payload)
        return result.get("result", []) if result.get("ok") else []

    async def _register_commands(self) -> None:
        """Expose the real Cyclone controls in Telegram's command picker."""
        await self._api("setMyCommands", commands=BOT_COMMANDS)

    # ------------------------------------------------------------- dispatch
    async def handle_update(self, update: dict[str, Any]) -> None:
        message = update.get("message") or {}
        text = (message.get("text") or "").strip()
        chat = message.get("chat") or {}
        chat_id = chat.get("id")
        sender = message.get("from") or {}
        sender_id = sender.get("id")
        update_id = update.get("update_id")
        if update_id is not None:
            self._offset = update_id + 1
        if not chat_id or not text or chat.get("type") != "private":
            return
        # A private chat id usually matches a user id, but authorization must
        # always be tied to the Telegram sender identity, not the chat shell.
        if self.allowed_users and sender_id not in self.allowed_users:
            logger.info("telegram: ignoring sender %s in chat %s (not authorized)", sender_id, chat_id)
            return

        command, arguments = _command(text)
        if command in {"start", "help"}:
            await self.send_message(chat_id, _help_text())
            return
        if command == "agents":
            await self.send_message(chat_id, await self._agent_roster_text())
            return
        if command in {"agent", "ask"}:
            text = _agent_message(arguments)
            if text is None:
                await self.send_message(chat_id, "Use /agent <agent> <request>. Send /agents to see the live roster.")
                return
        elif command:
            await self.send_message(chat_id, "I don't know that command. Send /help for the available controls.")
            return

        display_name = sender.get("first_name") or chat.get("first_name") or "Telegram user"
        user_id = await self._ensure_user(chat_id, display_name)
        conversation_id = await self._ensure_conversation(chat_id, display_name)
        if conversation_id is None:
            await self.send_message(chat_id, "Cyclone could not prepare a conversation for you. Please try again.")
            return

        await self._subscribe(conversation_id, chat_id)
        pending_run = self._pending_approvals.get(conversation_id)
        choice = _approval_choice(text)
        if pending_run and choice:
            try:
                await self.runtime.hermes.resolve_run_approval(pending_run, choice)
            except Exception:  # pragma: no cover - defensive
                logger.exception("telegram: failed to resolve run approval")
                await self.send_message(chat_id, "Cyclone could not send your answer. Try again.")
                return
            self._pending_approvals.pop(conversation_id, None)
            label = {"once": "Allow once", "session": "Allow this session", "always": "Always allow", "deny": "Deny"}[choice]
            await self.send_message(chat_id, f"Answer sent: {label}. The agent continues.")
            return
        try:
            from .main import create_message_and_start_agent  # noqa: PLC0415 - deferred: avoids import cycle
            from .contracts import CreateMessageRequest  # noqa: PLC0415

            request = CreateMessageRequest(body=text, agent_slug="chief", run=True)
            response = await create_message_and_start_agent(conversation_id, request, self.runtime)
            if response.status == "blocked":
                await self.send_message(chat_id, f"Cyclone is not ready yet: {response.detail}")
        except Exception as error:  # pragma: no cover - defensive
            logger.exception("telegram: failed to dispatch message")
            await self.send_message(chat_id, "Cyclone hit an error handling that message. Check the desktop app.")

    async def _ensure_user(self, chat_id: int, display_name: str) -> UUID:
        return await self.runtime.repository.upsert_telegram_user(
            chat_id=chat_id, display_name=display_name, initials=_initials(display_name)
        )

    async def _ensure_conversation(self, chat_id: int, display_name: str) -> UUID | None:
        key = f"telegram-{chat_id}"
        existing = await self.runtime.repository.find_conversation_by_key(key)
        if existing:
            await self._sync_agent_roster(existing)
            return existing
        agents = await self.runtime.repository.list_agents()
        if not agents:
            logger.error("telegram: cannot create chat %s without any Cyclone agents", chat_id)
            return None
        conversation = await self.runtime.repository.create_conversation(
            title=display_name,
            kind="telegram",
            hermes_conversation_key=key,
            # This is the operator's control room, not a Chief-only inbox.
            # Members are real persisted agents, so @slug uses the exact same
            # semantic routing and task ownership as the desktop crew.
            agent_slugs=[agent.slug for agent in agents],
            project_key=None,
        )
        return conversation.id

    async def _sync_agent_roster(self, conversation_id: UUID) -> None:
        """Keep the remote control room aligned with real Cyclone agents."""
        conversation = await self.runtime.repository.get_conversation(conversation_id, message_limit=1)
        existing_ids = {member.agent.id for member in conversation.members if member.agent is not None}
        for agent in await self.runtime.repository.list_agents():
            if agent.id not in existing_ids:
                await self.runtime.repository.add_conversation_member(conversation_id, agent)

    async def _agent_roster_text(self) -> str:
        agents = await self.runtime.repository.list_agents()
        if not agents:
            return "Cyclone has no agents yet. Create one in the desktop app first."
        lines = ["<b>Live Cyclone agents</b>"]
        for agent in agents:
            role = f" — {agent.role}" if agent.role else ""
            lines.append(f"• <code>@{_escape(agent.slug)}</code> — {_escape(agent.name)}{_escape(role)}")
        lines.append("\nAsk one directly: <code>@research compare these options</code>")
        lines.append("Or use: <code>/agent research compare these options</code>")
        return "\n".join(lines)

    async def _subscribe(self, conversation_id: UUID, chat_id: int) -> None:
        if conversation_id in self._subscriptions:
            return
        subscription = await self.runtime.event_bus.subscribe(conversation_id)
        self._subscriptions[conversation_id] = subscription
        asyncio.create_task(self._forward_loop(conversation_id, chat_id, subscription))

    async def restore_subscriptions(self) -> None:
        """Re-attach the forwarders to every existing Telegram conversation.

        Subscriptions are in-memory; after a restart the worker must find the
        conversation rows (kind='telegram', key 'telegram-<chat_id>') and
        resume forwarding, otherwise agent results never reach the phone.
        """
        try:
            rows = await self.runtime.repository.list_telegram_conversations()
        except Exception:  # pragma: no cover - defensive
            logger.exception("telegram: could not list telegram conversations")
            return
        for conversation_id, key in rows:
            if not key.startswith("telegram-"):
                continue
            try:
                chat_id = int(key.removeprefix("telegram-"))
            except ValueError:
                continue
            if self.allowed_users and chat_id not in self.allowed_users:
                logger.info("telegram: not restoring unauthorized chat %s", chat_id)
                continue
            if conversation_id in self._subscriptions:
                continue
            try:
                await self._sync_agent_roster(conversation_id)
                await self._subscribe(conversation_id, chat_id)
                logger.info("telegram: restored forwarder for conversation %s (chat %s)", conversation_id, chat_id)
            except Exception:  # pragma: no cover - defensive
                logger.exception("telegram: failed to restore forwarder for %s", conversation_id)

    async def _forward_loop(self, conversation_id: UUID, chat_id: int, subscription: Any) -> None:
        """Forward agent/system messages from the conversation into Telegram."""
        try:
            while conversation_id in self._subscriptions:
                try:
                    envelope = await asyncio.wait_for(subscription.get(), timeout=30)
                except asyncio.TimeoutError:
                    continue
                if envelope.type not in {
                    "message.created",
                    "agent.run.completed",
                    "agent.run.started",
                    "agent.run.blocked",
                    "approval.requested",
                    "approval.decided",
                    "handoff.created",
                }:
                    continue
                payload = envelope.payload
                if not isinstance(payload, dict):
                    continue
                author_type = str(payload.get("author_type", ""))
                if author_type == "human":
                    continue
                body = str(payload.get("body", "")).strip()
                if not body:
                    continue
                author_name = str(payload.get("author_name") or "Agent")
                kind = str(payload.get("kind", "message"))
                # Progress noise stays quiet; system activity (e.g. blocked
                # runs) is important enough to surface.
                if kind in {"activity", "task"} and author_type != "system":
                    continue
                if envelope.type == "approval.requested":
                    metadata = payload.get("metadata") if isinstance(payload.get("metadata"), dict) else {}
                    run_id = str(metadata.get("hermes_run_id") or "")
                    if run_id:
                        self._pending_approvals[conversation_id] = run_id
                    body = f"{body}\n\nReply with one of: allow once · allow session · always allow · deny"
                prefix = "" if kind == "message" else f"({kind}) "
                try:
                    await self.send_message(chat_id, f"<b>{_escape(author_name)}</b>\n{prefix}{_escape(body)}")
                except Exception:  # pragma: no cover - defensive
                    logger.exception("telegram: failed to forward a message to chat %s", chat_id)
        except Exception:  # pragma: no cover - defensive
            logger.exception("telegram: forward loop ended for %s", conversation_id)
        finally:
            self._subscriptions.pop(conversation_id, None)

    async def _restore_home_channel(self) -> None:
        """Restore the designated Super User's remote control room after restart."""
        if self.home_channel is None:
            return
        if self.allowed_users and self.home_channel not in self.allowed_users:
            logger.warning("telegram: home channel is not an authorized Super User; not subscribing")
            return
        key = f"telegram-{self.home_channel}"
        conversation_id = await self.runtime.repository.find_conversation_by_key(key)
        if conversation_id is None:
            logger.info("telegram: home channel %s will be initialized on its first message", self.home_channel)
            return
        await self._sync_agent_roster(conversation_id)
        await self._subscribe(conversation_id, self.home_channel)

    # ----------------------------------------------------------------- main
    async def run(self) -> None:
        me = await self.get_me()
        if not me:
            logger.error("telegram: bot token rejected by Telegram (getMe failed); worker disabled.")
            return
        logger.info("telegram: connected as @%s (%s)", me.get("username"), me.get("first_name"))
        await self._register_commands()
        await self.restore_subscriptions()
        await self._restore_home_channel()
        while True:
            try:
                updates = await self.get_updates()
                for update in updates:
                    await self.handle_update(update)
            except httpx.HTTPError:
                logger.warning("telegram: network error; retrying in 10s")
                await asyncio.sleep(10)
            except Exception:  # pragma: no cover - defensive
                logger.exception("telegram: poll loop error; retrying in 10s")
                await asyncio.sleep(10)
            await asyncio.sleep(0.5)


def _approval_choice(text: str) -> str | None:
    """Map a Telegram reply to a run-approval choice (or None when not one)."""
    normalized = text.strip().lower().rstrip(".!")
    aliases = {
        "allow once": "once", "allow": "once", "approve": "once", "yes": "once", "approve once": "once",
        "allow session": "session", "this session": "session", "for this session": "session",
        "always allow": "always", "always": "always", "allow always": "always",
        "deny": "deny", "no": "deny", "denied": "deny", "block": "deny",
    }
    return aliases.get(normalized)


def _chat_id(value: str | None) -> int | None:
    """Parse an explicitly configured Telegram home channel safely."""
    if value is None:
        return None
    try:
        return int(value.strip())
    except ValueError:
        logger.warning("telegram: TELEGRAM_HOME_CHANNEL is not a numeric chat id")
        return None


def _command(text: str) -> tuple[str | None, str]:
    """Return a normalized Telegram command and its untouched arguments."""
    if not text.startswith("/"):
        return None, text
    head, _, arguments = text[1:].partition(" ")
    command = head.partition("@")[0].lower()
    return command, arguments.strip()


def _agent_message(arguments: str) -> str | None:
    """Translate /agent agent-name request into the normal @mention path."""
    slug, separator, request = arguments.partition(" ")
    slug = slug.lstrip("@").strip().lower()
    if not slug or not separator or not request.strip():
        return None
    return f"@{slug} {request.strip()}"


def _help_text() -> str:
    return (
        "<b>Cyclone control room</b>\n\n"
        "Write naturally and Chief will coordinate, or address any real agent directly:\n"
        "<code>@research compare the options and hand the implementation to @developer</code>\n\n"
        "Commands:\n"
        "• <code>/agents</code> — live agents and their roles\n"
        "• <code>/agent &lt;agent&gt; &lt;request&gt;</code> — ask an agent directly\n"
        "• <code>/help</code> — show this guide"
    )


def _chunk_text(text: str) -> list[str]:
    if len(text) <= MAX_MESSAGE_LENGTH:
        return [text]
    chunks: list[str] = []
    while len(text) > MAX_MESSAGE_LENGTH:
        cut = text.rfind("\n", 0, MAX_MESSAGE_LENGTH)
        if cut < MAX_MESSAGE_LENGTH // 2:
            cut = MAX_MESSAGE_LENGTH
        chunks.append(text[:cut])
        text = text[cut:]
    if text:
        chunks.append(text)
    return chunks


def _escape(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def serialize_payload(payload: dict[str, Any]) -> str:
    """Stable JSON for debugging payloads — never includes the bot token."""
    return json.dumps(payload, default=str)[:400]
