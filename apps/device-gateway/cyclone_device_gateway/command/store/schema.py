"""The Command Center's migrations, in order. Append only: never edit or reorder an entry once it has shipped.

Migration 1 adopts the schema as it stood at alpha 155, when each store still created its own tables
(``CREATE TABLE IF NOT EXISTS`` and column checks). It changes nothing; it records the starting point, so every later
change to the file is a numbered migration here.
"""
from __future__ import annotations

from .migrations import Migration

MIGRATIONS: tuple[Migration, ...] = (
    Migration(1, "adopt the alpha 155 schema"),
)
