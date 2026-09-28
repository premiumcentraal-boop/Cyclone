# Copyright 2026 Cyclone contributors
# Licensed under the Apache License, Version 2.0.
"""Conservative completion contract for launch-only goals, never multi-step goals."""
import re

APP_PACKAGES = {
    "gmail": "com.google.android.gm", "mail": "com.google.android.gm",
    "chrome": "com.android.chrome", "chrome browser": "com.android.chrome",
    "settings": "com.android.settings", "android settings": "com.android.settings",
    "messages": "com.google.android.apps.messaging", "photos": "com.google.android.apps.photos",
    "youtube": "com.google.android.youtube", "maps": "com.google.android.apps.maps",
    "phone": "com.google.android.dialer", "camera": "com.google.android.GoogleCamera",
    "clock": "com.google.android.deskclock", "calculator": "com.google.android.calculator",
    "calendar": "com.google.android.calendar", "play store": "com.android.vending",
    "play-store": "com.android.vending", "playstore": "com.android.vending",
}


def launch_goal_package(goal: str) -> str | None:
    """Only explicit launch phrases with an optional stop clause qualify.

    Additional instructions, search, authentication, repeated launches and
    navigation goals fall back to the full agent loop.
    """
    match = re.fullmatch(
        r"(?:please )?(?:open|launch) (?:the )?([a-z -]+?)"
        r"(?: app| application)?(?: on (?:the |my )?(?:phone|pixel))?"
        r"(?: and stop(?: with (?:it|the app) visible)?)?[.!]?"
        r"(?: do not change or submit anything\.)?",
        goal.strip().lower(),
    )
    return APP_PACKAGES.get(match.group(1)) if match else None
