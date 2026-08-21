from __future__ import annotations

ALLOWED_OPS = {
    "bridge.status", "observe.semantic", "observe.page_debug", "ui.search", "ui.element",
    "app_graph.get", "brain.recall", "action.execute", "teach.start", "teach.status",
    "teach.stop", "debug.snapshot", "pair.begin", "pair.complete", "pair.revoke",
    "manual.execute", "clipboard.get", "clipboard.set",
}
UNAUTHENTICATED_OPS = {"pair.begin", "pair.complete"}
