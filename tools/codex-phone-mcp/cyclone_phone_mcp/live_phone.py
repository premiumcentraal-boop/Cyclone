"""Cloud connector facade. Native Codex MCP is deliberately unchanged."""
from __future__ import annotations

SESSION = "default-foreground"
DISPLAY = 0
READS = {"status", "devices", "observe", "screenshot", "locate", "inspect"}
ACTIONS = {"tap", "long-press", "type", "clear-text", "swipe", "scroll", "back", "home", "open-app"}
COMMANDS = READS | ACTIONS


def validate_request(request):
    if not isinstance(request, dict) or request.get("operation") not in COMMANDS:
        raise ValueError("Unsupported Live Phone operation")
    if set(request) - {"operation", "device", "goal", "element", "text", "direction", "package", "observation_id", "user_authorized"}:
        raise ValueError("Unexpected Live Phone parameter")
    for key, value in request.items():
        if key == "user_authorized":
            if type(value) is not bool:
                raise ValueError("Authorization must be boolean")
        elif not isinstance(value, str) or len(value) > (4000 if key == "text" else 240):
            raise ValueError("Invalid bounded parameter")
    return dict(request)
