"""``AiStore``: the Manager service the Command Center owns (``center.ai``), made of the store and the loop."""
from __future__ import annotations

from . import toolsets  # noqa: F401 - registers every tool before a store binds them
from .loop import LoopMixin
from .store import StoreMixin


class AiStore(LoopMixin, StoreMixin):
    """The Command Center's AI project manager. ``center.ai`` is one of these."""
