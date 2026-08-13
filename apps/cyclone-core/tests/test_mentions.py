"""Unit tests for semantic mention parsing and delegation syntax."""

from app.mentions import crew_context_text, parse_handoffs, parse_mentions, resolve_addressed_slug


def test_parse_mentions_orders_and_deduplicates() -> None:
    text = "Can @research verify this while @chief watches? Thanks @research."
    assert parse_mentions(text) == ["research", "chief"]


def test_parse_mentions_ignores_email_addresses_and_standalone_ats() -> None:
    text = "Email me@example.com or mention @chief; plain @ is not a mention."
    assert parse_mentions(text) == ["chief"]


def test_parse_mentions_is_case_insensitive_and_slug_shaped() -> None:
    assert parse_mentions("@RESEARCH and @chief") == ["research", "chief"]
    assert parse_mentions("@Not-A-Slug!") == ["not-a-slug"]


def test_parse_handoff_extracts_summary_and_criteria() -> None:
    text = "I ran the search.\n@HANDOFF @research: compare the top three vendors | include pricing"
    handoffs = parse_handoffs(text)
    assert len(handoffs) == 1
    assert handoffs[0].to_slug == "research"
    assert handoffs[0].summary == "compare the top three vendors"
    assert handoffs[0].acceptance_criteria == "include pricing"


def test_parse_handoff_requires_a_summary() -> None:
    assert parse_handoffs("@HANDOFF @research:") == []
    assert parse_handoffs("plain mention of @research") == []


def test_parse_multiple_handoffs_in_one_result() -> None:
    text = (
        "Done.\n"
        "@HANDOFF @research: gather sources | primary only\n"
        "@HANDOFF @chief: sign off"
    )
    handoffs = parse_handoffs(text)
    assert [handoff.to_slug for handoff in handoffs] == ["research", "chief"]
    assert handoffs[1].acceptance_criteria is None


def test_crew_context_text_lists_teammates_and_delegation_rule() -> None:
    text = crew_context_text([("research", "Evidence specialist")])
    assert "@research" in text
    assert "Evidence specialist" in text
    assert "@HANDOFF @slug:" in text


def test_parse_mentions_does_not_match_handoff_token_as_a_slug() -> None:
    # @HANDOFF is delegation syntax, not a teammate reference.
    assert parse_mentions("@HANDOFF @research: do the thing") == ["research"]


def test_resolve_addressed_slug_requires_a_leading_mention() -> None:
    members = {"chief", "research"}
    assert resolve_addressed_slug("@research please verify this", members) == "research"
    # Inline mentions are references, not addressing.
    assert resolve_addressed_slug("Chief: delegate to @research", members) is None
    assert resolve_addressed_slug("Hi @research - could you check?", members) is None


def test_resolve_addressed_slug_ignores_non_members_and_reserved_tokens() -> None:
    members = {"chief"}
    assert resolve_addressed_slug("@outsider do this", members) is None
    assert resolve_addressed_slug("@handoff @chief: do this", members) is None
    assert resolve_addressed_slug("@CHIEF status please", members) == "chief"
