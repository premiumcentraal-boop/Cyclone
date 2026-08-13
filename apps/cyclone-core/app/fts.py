"""Full-text search term extraction for vault knowledge retrieval.

Hermes-side knowledge search uses Postgres ``websearch_to_tsquery``, which
ANDs *every* word in the query. Natural-language sentences (a full user
message) therefore almost never match a note's text. This module reduces a
free-text query to a small set of meaningful OR terms so retrieval matches
the way people actually ask.
"""

from __future__ import annotations

import re

_WORD_RE = re.compile(r"[a-zA-Z0-9]{3,}")
_STOPWORDS = frozenset(
    {
        "about", "according", "after", "again", "also", "and", "answer",
        "are", "been", "being", "between", "both", "could", "does", "doing",
        "done", "each", "else", "every", "for", "from", "get", "have",
        "having", "here", "into", "just", "more", "most", "much", "must",
        "only", "other", "our", "over", "please", "reply", "should", "some",
        "such", "tell", "than", "that", "the", "their", "them", "then",
        "there", "these", "they", "this", "those", "through", "under",
        "using", "value", "very", "want", "well", "were", "what", "when",
        "where", "which", "while", "who", "will", "with", "without", "word",
        "would", "you", "your",
    }
)


def fts_query_terms(text: str, max_terms: int = 12) -> str | None:
    """Return an OR-joined tsquery string for *text*, or None when empty.

    Terms are lowercased alphanumeric tokens of 3+ characters with common
    question filler removed, capped at *max_terms* in first-occurrence order.
    The result feeds ``to_tsquery('simple', ...)``.
    """
    terms: list[str] = []
    seen: set[str] = set()
    for word in _WORD_RE.findall(text.lower()):
        if word in _STOPWORDS or word in seen:
            continue
        seen.add(word)
        terms.append(word)
        if len(terms) >= max_terms:
            break
    if not terms:
        return None
    return " | ".join(terms)
