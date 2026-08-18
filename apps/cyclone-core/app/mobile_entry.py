"""Cyclone Core ASGI entrypoint with the Agent-3 mobile runtime installed.

Keeping the mobile router in a separate entry module avoids invasive edits to
the existing desktop/Core route file while the three mobile branches develop
in parallel.
"""

from .main import app
from .mobile_api import mobile_devices, router as mobile_router

app.include_router(mobile_router)
app.state.mobile_devices = mobile_devices

__all__ = ["app"]
