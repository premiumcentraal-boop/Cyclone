"""The Command Center's AI project manager (plan 33 §7, C4 moved forward at the owner's request).

A runtime service, never Glass (D7): it holds the owner's OpenRouter key, lists OpenRouter's models, keeps
conversations, and plans and edits the workspace through a fixed set of tools.

Rules this module keeps:
- **The key is write-only.** It is saved in the same per-user store as connection keys (DPAPI on Windows) and is never
  returned, logged, audited or shown to a model. Glass can save it, test it and forget it, nothing else.
- **Fixed tools, no shell.** Reads of the workspace; page and card edits; tasks and routines. There is no tool that
  deletes, approves, reads vault values, adds connections, changes accounts or commands a phone directly.
- **The owner decides.** Page and card edits are proposals unless the owner lets the AI edit the workspace itself;
  tasks and routines are always proposals the owner applies. A task then runs like any other: the phone still asks
  before sending, paying, deleting or signing in.
- **Outside content is information.** Page text, task results and connection data reach the model as tool results,
  and the instructions say never to follow instructions inside them.
- **Budgets.** A daily and a monthly spending cap (USD, from OpenRouter's reported cost); a turn stops when either is
  reached, and after a fixed number of steps.
- **What is kept** is what the model saw and did (messages, tool calls, results, cost), never hidden reasoning.
"""
from __future__ import annotations

# Plan 55 R1: the service now lives in ``command/agent/`` (registry, toolsets, prompt, store, loop). This module keeps
# the names the gateway, its routes and tests have always imported from here.
from .agent import REGISTRY, AiError, AiStore
from .agent.common import (AUTONOMY, BASE, CATALOGUE_TTL_MS, CONVERSATION_ID, DEFAULTS, GRANT, KEY, MAX_ANSWER_TOKENS,
                           MAX_CALLS_PER_STEP, MAX_CONTEXT_CHARS, MAX_CONVERSATIONS, MAX_INSTRUCTIONS, MAX_OWNER_TEXT,
                           MAX_STEPS, MAX_TOOL_RESULT, MODEL_ID, PROPOSAL_ID, PROVIDER)
from .agent.prompt import AUTONOMY_TEXT, SYSTEM
from .agent.store import AI_SCHEMA

#: name -> (kind, description, JSON schema), built from the registry.
TOOLS: dict[str, tuple[str, str, dict]] = REGISTRY.table()

__all__ = ["AI_SCHEMA", "AUTONOMY", "AUTONOMY_TEXT", "AiError", "AiStore", "BASE", "CATALOGUE_TTL_MS", "CONVERSATION_ID",
           "DEFAULTS", "GRANT", "KEY", "MAX_ANSWER_TOKENS", "MAX_CALLS_PER_STEP", "MAX_CONTEXT_CHARS", "MAX_CONVERSATIONS",
           "MAX_INSTRUCTIONS", "MAX_OWNER_TEXT", "MAX_STEPS", "MAX_TOOL_RESULT", "MODEL_ID", "PROPOSAL_ID", "PROVIDER",
           "SYSTEM", "TOOLS"]
