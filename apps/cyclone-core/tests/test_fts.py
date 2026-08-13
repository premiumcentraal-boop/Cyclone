"""Unit tests for free-text retrieval term extraction."""

from app.fts import fts_query_terms


def test_query_terms_extract_meaningful_ors() -> None:
    terms = fts_query_terms("According to the Cyclone Lighthouse Directive, what is the Lighthouse wordmark color?")
    assert terms is not None
    assert "cyclone" in terms
    assert "lighthouse" in terms
    assert "directive" in terms
    assert "wordmark" in terms
    assert "color" in terms
    # Question filler must not appear.
    for filler in ("according", "what", "the", "answer", "only"):
        assert filler not in terms


def test_query_terms_deduplicate_and_cap() -> None:
    text = "lighthouse lighthouse lighthouse alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu"
    terms = fts_query_terms(text)
    assert terms is not None
    parts = terms.split(" | ")
    assert parts.count("lighthouse") == 1
    assert len(parts) == 12


def test_query_terms_drops_short_and_stop_tokens() -> None:
    assert fts_query_terms("is the a of to in") is None
    assert fts_query_terms("ab cd ef") is None


def test_query_terms_handle_empty_and_whitespace() -> None:
    assert fts_query_terms("") is None
    assert fts_query_terms("   ") is None
