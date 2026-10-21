"""Cyclone version strings, compared without guessing.

A version looks like 5.0.0-alpha.151.dev1. The numbers are compared in order. An alpha or dev build sorts before the
release of the same numbers, and a higher alpha or dev number sorts later. Anything else is refused, so a typo can never
pass a version gate by accident.
"""

from __future__ import annotations

import re

_PATTERN = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+))?(?:\.dev(\d+))?$")
_RELEASE = 10**9  # a release (no alpha tag) is newer than every alpha of the same numbers


class VersionError(ValueError):
    """The text is not a Cyclone version."""


def parse(version: str) -> tuple[int, int, int, int, int]:
    match = _PATTERN.match(version.strip())
    if not match:
        raise VersionError(f"not a Cyclone version: {version!r}")
    major, minor, patch, alpha, dev = match.groups()
    alpha_key = int(alpha) if alpha is not None else _RELEASE
    return int(major), int(minor), int(patch), alpha_key, int(dev or 0)


def at_least(installed: str, required: str) -> bool:
    """True when [installed] is the same version as [required] or newer."""
    return parse(installed) >= parse(required)
