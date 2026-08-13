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


def _initials(name: str) -> str:
    words = [word for word in name.strip().split() if word]
    return "".join(word[0] for word in words[:2]).upper() or "T"


class TelegramWorker:
    def __init__(self, runtime: Any, token: str, allowed_users: list[int] | None = None) -> None:
        self.runtime = runtime
        self.token = token
        self.allowed_users = set(allowed_users or [])
        self.base = API_BASE.format(token=token)
        self._offset: int | None = None
        self._active_conversations: set[UUID] = set()
        self._subscriptions: dict[UUID, Any] = {}

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

    # ------------------------------------------------------------- dispatch
    async def handle_update(self, update: dict[str, Any]) -> None:
        message = update.get("message") or {}
        text = (message.get("text") or "").strip()
        chat = message.get("chat") or {}
        chat_id = chat.get("id")
        update_id = update.get("update_id")
        if update_id is not None:
            self._offset = update_id + 1
        if not chat_id or not text or chat.get("type") != "private":
            return
        if self.allowed_users and chat_id not in self.allowed_users:
            logger.info("telegram: ignoring chat %s (not in allowed users)", chat_id)
            return

        sender = message.get("from") or {}
        display_name = sender.get("first_name") or chat.get("first_name") or "Telegram user"
        user_id = await self._ensure_user(chat_id, display_name)
        conversation_id = await self._ensure_conversation(chat_id, display_name)
        if conversation_id is None:
            await self.send_message(chat_id, "Cyclone could not prepare a conversation for you. Please try again.")
            return

        await self._subscribe(conversation_id, chat_id)
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
            return existing
        conversation = await self.runtime.repository.create_conversation(
            title=display_name,
            kind="telegram",
            hermes_conversation_key=key,
            agent_slugs=["chief"],
            project_key=None,
        )
        return conversation.id

    async def _subscribe(self, conversation_id: UUID, chat_id: int) -> None:
        if conversation_id in self._subscriptions:
            return
        subscription = await self.runtime.event_bus.subscribe(conversation_id)
        self._subscriptions[conversation_id] = subscription
        asyncio.create_task(self._forward_loop(conversation_id, chat_id, subscription))

    async def _forward_loop(self, conversation_id: UUID, chat_id: int, subscription: Any) -> None:
        """Forward agent/system messages from the conversation into Telegram."""
        try:
            while conversation_id in self._subscriptions:
                try:
                    envelope = await asyncio.wait_for(subscription.get(), timeout=30)
                except asyncio.TimeoutError:
                    continue
                if envelope.type not in {"message.created", "approval.requested"}:
                    continue
                payload = envelope.payload
                author_type = str(payload.get("author_type", ""))
                if author_type == "human":
                    continue
                body = str(payload.get("body", "")).strip()
                if not body:
                    continue
                author_name = str(payload.get("author_name") or "Agent")
                kind = str(payload.get("kind", "message"))
                if kind in {"activity", "task"}:
                    continue
                prefix = "" if kind == "message" else f"({kind}) "
                await self.send_message(chat_id, f"<b>{_escape(author_name)}</b>\n{prefix}{_escape(body)}")
        except Exception:  # pragma: no cover - defensive
            logger.exception("telegram: forward loop ended for %s", conversation_id)
        finally:
            self._subscriptions.pop(conversation_id, None)

    # ----------------------------------------------------------------- main
    async def run(self) -> None:
        me = await self.get_me()
        if not me:
            logger.error("telegram: bot token rejected by Telegram (getMe failed); worker disabled.")
            return
        logger.info("telegram: connected as @%s (%s)", me.get("username"), me.get("first_name"))
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
