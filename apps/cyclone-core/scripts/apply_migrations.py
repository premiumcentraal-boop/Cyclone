"""Apply numbered Core SQL migrations exactly once per database.

The stack mounts this helper only for explicit operator/developer migration runs;
it never silently edits a user database during a desktop rendering pass.
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path

import psycopg


async def main() -> int:
    database_url = os.environ["CYCLONE_DATABASE_URL"]
    migration_dir = Path(__file__).resolve().parents[1] / "db" / "migrations"
    async with await psycopg.AsyncConnection.connect(database_url) as connection:
        await connection.execute(
            """
            CREATE TABLE IF NOT EXISTS cyclone_schema_migrations (
                name TEXT PRIMARY KEY,
                applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """
        )
        for migration in sorted(migration_dir.glob("*.sql")):
            existing = await connection.execute(
                "SELECT 1 FROM cyclone_schema_migrations WHERE name = %s", (migration.name,)
            )
            if await existing.fetchone():
                continue
            await connection.execute(migration.read_text(encoding="utf-8"))
            await connection.execute(
                "INSERT INTO cyclone_schema_migrations (name) VALUES (%s)", (migration.name,)
            )
            await connection.commit()
            print(f"applied {migration.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
