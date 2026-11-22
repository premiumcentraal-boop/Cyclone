"""The synthetic secret corpus (plan 76 test-data policy, rule 2).

Every value is obviously fake and follows the shape Cyclone refuses: a secret keyword followed by ``:`` or ``=``.
None imitates a real provider's key format, so repository secret scanning never mistakes them for a leak.
Tests prove each entry is refused at every place that accepts text (``test_platform_secret_corpus.py``).
"""
from __future__ import annotations

CORPUS: tuple[str, ...] = (
    "password: example-not-a-real-password",
    "Password = horse-battery-staple-example",
    "my password:example-inline",
    "passcode: 000000-example",
    "passwd=example-only",
    "pin: 0000",
    "otp: 123456",
    "OTP=654321",
    "token: example-token-not-real",
    "secret: example-secret-value",
    "api_key = example-not-real",
    "API-KEY: example-not-real",
    "apikey=example",
    "Authorization: Bearer example-not-real",
    "cookie: session=example",
    "cvv: 123",
    "credentials = example-only",
    "typed_text: example",
    # Plurals: the corpus found these accepted before stage 0 fixed the rule.
    "passwords: example-a, example-b",
    "credentials: example-two",
    "tokens: example-one",
    "secrets = example",
    "api_keys: example",
    "cookies: example",
)
