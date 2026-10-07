"""Plugins from GitHub (plan 50): install, run and keep Cyclone plugins packaged as ``cyclone.package/1`` release files.
The format, the archive rules, the settings subset and the index signature come from the Ports kit
(``tools/cyclone-ports-sdk``), never a copy, so the build Action, plugin authors and this runtime apply the same rules.
"""
from __future__ import annotations

from ..ports import kit

if kit is not None:
    from cyclone_ports import index as kindex
    from cyclone_ports import package as kpackage
else:  # pragma: no cover - the PC package always ships the kit
    kindex = kpackage = None

AVAILABLE = kit is not None
