"""Safe, human-readable Obsidian vault writes.

The vault is the durable source a human can open in Obsidian. Database indexing
is done by the repository after a write; this module never writes chat logs by
default and does not auto-promote arbitrary conversation text.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
import re
import unicodedata


ALLOWED_CATEGORIES = {
    "Agents", "Projects", "People", "Research", "Decisions", "Knowledge",
    "Routines", "Sessions", "Tasks", "Skills", "System", "Inbox", "Archive",
}
_SLUG_RE = re.compile(r"[^a-z0-9]+")


@dataclass(frozen=True)
class VaultEntry:
    vault_path: str
    title: str
    category: str
    content: str


def _slugify(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii").lower()
    slug = _SLUG_RE.sub("-", normalized).strip("-")
    if not slug or slug in {".", ".."}:
        raise ValueError("title must contain at least one safe filename character")
    return slug[:100]


class VaultMemoryService:
    def __init__(self, vault_root: Path) -> None:
        self.vault_root = vault_root

    def bootstrap(self) -> None:
        for category in sorted(ALLOWED_CATEGORIES):
            (self.vault_root / category).mkdir(parents=True, exist_ok=True)

    def write(
        self,
        *,
        title: str,
        category: str,
        content: str,
        project_key: str | None = None,
        agent_slug: str | None = None,
    ) -> VaultEntry:
        if category not in ALLOWED_CATEGORIES:
            raise ValueError("category is not part of the Cyclone vault allowlist")
        if "/" in title or "\\" in title or ".." in title:
            raise ValueError("title must not contain path separators or traversal segments")
        slug = _slugify(title)
        self.bootstrap()
        date_prefix = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        relative = Path(category) / f"{date_prefix}-{slug}.md"
        destination = (self.vault_root / relative).resolve()
        root = self.vault_root.resolve()
        try:
            destination.relative_to(root)
        except ValueError as error:
            raise ValueError("title resolves outside the vault") from error
        frontmatter = ["---", f"title: {title}", f"category: {category}", f"created: {datetime.now(timezone.utc).isoformat()}"]
        if project_key:
            frontmatter.append(f"project: {project_key}")
        if agent_slug:
            frontmatter.append(f"agent: {agent_slug}")
        frontmatter.append("---")
        destination.write_text("\n".join(frontmatter) + "\n\n" + content.strip() + "\n", encoding="utf-8")
        return VaultEntry(vault_path=relative.as_posix(), title=title, category=category, content=content)
