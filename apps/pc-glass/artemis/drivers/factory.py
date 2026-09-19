# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Device Driver Factory & Registry."""

import os
from typing import TYPE_CHECKING

from adbutils import AdbClient

from artemis.config import settings
from artemis.drivers.android.adb_driver import AndroidAdbDriver
from artemis.drivers.base import BaseDeviceDriver
from artemis.drivers.mock.mock_driver import MockDeviceDriver
from artemis.utils.logger import get_logger

if TYPE_CHECKING:
    from artemis.context import ArtemisContext

logger = get_logger(__name__)

_TRUTHY = frozenset({"1", "true", "yes", "on"})


def cyclone_connected() -> bool:
    """True when PC Glass must use Cyclone gateway/MCP (not raw ADB)."""
    for key in ("CYCLONE_CONNECTED", "ARTEMIS_CYCLONE_GATEWAY"):
        if (os.environ.get(key) or "").strip().lower() in _TRUTHY:
            return True
    if (os.environ.get("CYCLONE_DEVICE_GATEWAY_URL") or "").strip():
        return True
    if (os.environ.get("CYCLONE_SESSION_ID") or "").strip():
        return True
    return False


def create_driver(ctx: "ArtemisContext") -> BaseDeviceDriver:
    """Instantiates the appropriate BaseDeviceDriver based on the runtime context."""

    # 0. Cyclone-connected mode: gateway/MCP phone_* only (no ADB authority)
    if cyclone_connected():
        from artemis.drivers.cyclone.gateway_driver import CycloneGatewayDriver

        logger.info("create_driver: selecting CycloneGatewayDriver (CYCLONE connected)")
        env_device = (os.environ.get("CYCLONE_DEVICE_ID") or "").strip() or None
        ctx_device = ctx.device.device_id if ctx.device else None
        # Prefer explicit Cyclone id; ADB serials are resolved inside the driver.
        device_id = env_device if (env_device and env_device.startswith("dev_")) else ctx_device
        return CycloneGatewayDriver(
            device_id=device_id,
            width=ctx.device.device_width if ctx.device else 1080,
            height=ctx.device.device_height if ctx.device else 2400,
        )
    # 1. Cloud mode check. Cloud devices are reached through the gateway's
    # RemoteUIAutomatorClient; ARTEMIS_HIERARCHY_BACKEND does not apply there
    # because the Accessibility Helper needs a local adb forward.
    if os.environ.get("ARTEMIS_CLOUD_MODE") == "1":
        if ctx.adb_client is None:
            from cloud_service.virtualization import RemoteAdbClient

            ctx.adb_client = RemoteAdbClient()
        if ctx.ui_adb_client is None:
            from cloud_service.virtualization import RemoteUIAutomatorClient

            ctx.ui_adb_client = RemoteUIAutomatorClient(adb_client=ctx.adb_client)

    # 2. Mock mode check
    if (
        getattr(ctx.device, "mobile_platform", None) == "mock"
        or os.environ.get("ARTEMIS_MOCK_DRIVER") == "1"
    ):
        return MockDeviceDriver(
            device_id=ctx.device.device_id if ctx.device else "mock-device",
            width=ctx.device.device_width if ctx.device else 1080,
            height=ctx.device.device_height if ctx.device else 2400,
        )

    # 3. Default Android ADB driver
    if ctx.adb_client is None:
        ctx.adb_client = AdbClient(
            host=settings.ADB_HOST or "localhost", port=settings.ADB_PORT or 5037
        )

    return AndroidAdbDriver(
        device_id=ctx.device.device_id,
        adb_client=ctx.adb_client,
        ui_adb_client=getattr(ctx, "ui_adb_client", None),
        width=ctx.device.device_width,
        height=ctx.device.device_height,
    )


def get_driver(ctx: "ArtemisContext") -> BaseDeviceDriver:
    """Cached accessor for device driver in the current context."""
    if not hasattr(ctx, "_active_driver") or getattr(ctx, "_active_driver") is None:
        driver = create_driver(ctx)
        setattr(ctx, "_active_driver", driver)
    return getattr(ctx, "_active_driver")
