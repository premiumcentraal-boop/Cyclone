"""Unit tests for the Telegram channel helpers."""

from app.telegram import _agent_message, _approval_choice, _chat_id, _chunk_text, _command, _escape


def test_approval_choice_maps_telegram_replies() -> None:
    assert _approval_choice("allow once") == "once"
    assert _approval_choice("Allow") == "once"
    assert _approval_choice("yes") == "once"
    assert _approval_choice("allow session") == "session"
    assert _approval_choice("always allow") == "always"
    assert _approval_choice("deny") == "deny"
    assert _approval_choice("No.") == "deny"
    assert _approval_choice("what is the plan?") is None


def test_chunk_text_keeps_telegram_limit() -> None:
    long_text = "x" * 9000
    chunks = _chunk_text(long_text)
    assert len(chunks) > 1
    assert all(len(chunk) <= 3500 for chunk in chunks)
    assert "".join(chunks) == long_text


def test_escape_html() -> None:
    assert _escape("a <b> & 'c'") == "a &lt;b&gt; &amp; 'c'"


def test_direct_agent_command_uses_the_normal_mention_routing_path() -> None:
    assert _command("/agent@cycloneapp_bot research compare the proposals") == (
        "agent", "research compare the proposals"
    )
    assert _agent_message("@research compare the proposals") == "@research compare the proposals"
    assert _agent_message("research") is None


def test_home_channel_is_only_a_numeric_chat_id() -> None:
    assert _chat_id("7690834361") == 7690834361
    assert _chat_id("not-a-chat") is None
