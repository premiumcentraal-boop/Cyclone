"""Run async tests without requiring a globally installed pytest plugin.

The container image installs pytest-asyncio for standard execution. This small
hook keeps host preflight tests runnable using the Hermes runtime's stripped
pytest environment as well.
"""

from __future__ import annotations

import asyncio
import inspect

import pytest


def pytest_configure(config: pytest.Config) -> None:
    config.addinivalue_line("markers", "asyncio: execute test coroutine with asyncio.run")


def pytest_pyfunc_call(pyfuncitem: pytest.Function) -> bool | None:
    test_function = pyfuncitem.obj
    if not inspect.iscoroutinefunction(test_function):
        return None
    kwargs = {name: pyfuncitem.funcargs[name] for name in pyfuncitem._fixtureinfo.argnames}
    asyncio.run(test_function(**kwargs))
    return True
