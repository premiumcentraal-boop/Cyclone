"""The Command Center's store layer (plan 76, stage 0).

New platform code reaches SQLite only through :class:`Database`, and changes the schema only through a numbered
:class:`Migration` in :data:`MIGRATIONS`. The modules written before plan 76 still use the connection directly; the
store guard (``scripts/ci/tests/test_platform_store_guard.py``) holds their raw SQL at its recorded count, so it can
only go down as later stages move them here.
"""
from __future__ import annotations

from .database import Database
from .migrations import Migration, MigrationError, Migrator
from .schema import MIGRATIONS

__all__ = ["Database", "MIGRATIONS", "Migration", "MigrationError", "Migrator"]
