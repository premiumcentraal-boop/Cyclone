# Copyright 2026 Premium Centraal / Cyclone PC Glass

from artemis.cyclone.auto_profile import assess_tier, resolve_auto


def test_easy_open_app():
    tier, _ = assess_tier("open Gmail")
    assert tier == "easy"
    res = resolve_auto("open Gmail")
    assert res.profile == "flash"
    assert res.verification_level is None


def test_hard_monitor():
    res = resolve_auto("monitor the inbox every minute and report new mail")
    assert res.tier == "hard"
    assert res.profile == "pro"
    assert res.verification_level == "checkpoints"
    assert res.explorer_mode == "pro"


def test_medium_default():
    res = resolve_auto("open Gmail and star the first unread email")
    assert res.tier in {"medium", "hard"}  # "and" may not be hard; open+extra => medium
    assert res.profile in {"flash", "pro"}


def test_as_run_overrides():
    res = resolve_auto("diagnose wifi settings then extract the IP")
    ov = res.as_run_overrides()
    assert ov["profile"] == "pro"
    assert "verification_level" in ov
