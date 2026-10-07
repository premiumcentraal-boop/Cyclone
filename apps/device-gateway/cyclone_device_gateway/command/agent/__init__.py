"""The Glass Manager's runtime service (plan 54 design, plan 55 R1 layout; structure after Hermes Agent, MIT).

    common.py     provider, limits, AiError, argument checks
    registry.py   tools register themselves with a kind and a strict schema; no shell, file or network powers
    toolsets/     one module per toolset (workspace today)
    prompt.py     the system prompt in tiers
    store.py      settings, the write-only key, spending, models, conversations, proposals
    loop.py       one turn: ask the model, run tools, ask again
    service.py    AiStore = the store + the loop

It runs in the gateway, never in Glass: Glass shows what it says and sends what the owner types.
"""
from .common import AiError
from .registry import REGISTRY
from .service import AiStore

__all__ = ["AiError", "AiStore", "REGISTRY"]
