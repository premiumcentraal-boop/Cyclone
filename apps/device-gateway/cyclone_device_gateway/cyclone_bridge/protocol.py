from __future__ import annotations

ALLOWED_OPS = {
    "trust.negotiate", "trust.begin", "trust.complete", "trust.session.begin", "trust.session.complete",
    "trust.rotate", "trust.revoke",
    "bridge.status", "observe.semantic", "observe.page_debug", "ui.search", "ui.element",
    "app_graph.get", "brain.recall", "action.execute", "teach.start", "teach.status",
    "teach.stop", "debug.snapshot", "pair.begin", "pair.complete", "pair.qr.complete", "pair.revoke",
    "manual.execute", "clipboard.get", "clipboard.set",
    "skill.compile", "skill.run", "skill.match",
}
UNAUTHENTICATED_OPS = {
    "trust.negotiate", "trust.begin", "trust.complete", "trust.session.begin", "trust.session.complete",
    "pair.begin", "pair.complete", "pair.qr.complete",
}
