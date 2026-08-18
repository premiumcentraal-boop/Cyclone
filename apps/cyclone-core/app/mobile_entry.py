"""Cyclone Core ASGI entrypoint with the Agent-3 mobile runtime installed.

Keeping the mobile router and MCP registration in a separate entry module
avoids invasive edits to the existing desktop/Core route file while the three
mobile branches develop in parallel.
"""

from .main import _cyclone_mcp, app
from .mobile_api import mobile_devices, mobile_takeovers, router as mobile_router
from .mobile_mcp import register_mobile_mcp_tools

register_mobile_mcp_tools(
    _cyclone_mcp,
    lambda: app.state.services,
    mobile_devices,
    mobile_takeovers,
)
app.include_router(mobile_router)
app.state.mobile_devices = mobile_devices
app.state.mobile_takeovers = mobile_takeovers

__all__ = ["app"]
