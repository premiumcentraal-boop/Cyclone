"""Plan 51 K4: the published connector schemas are valid and describe every published test vector."""
from __future__ import annotations

import json
from pathlib import Path

import jsonschema
import pytest

SCHEMAS = Path(__file__).resolve().parents[3] / "tools" / "cyclone-connector-sdk" / "schemas"


def _load(name: str) -> dict:
    return json.loads((SCHEMAS / name).read_text(encoding="utf-8"))


def _validator(name: str) -> jsonschema.Draft202012Validator:
    schema = _load(name)
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema)


def test_every_schema_is_a_valid_schema():
    names = sorted(p.name for p in SCHEMAS.glob("*.schema.json"))
    assert "answer.schema.json" in names and "request.schema.json" in names
    for name in names:
        _validator(name)


VECTORS = _load("vectors.json")


@pytest.mark.parametrize("case", VECTORS["cases"], ids=[c["name"] for c in VECTORS["cases"]])
def test_every_vector_matches_its_schemas(case):
    if "request" in case:
        _validator("request.schema.json").validate(case["request"])
    answer = json.loads(json.dumps(case["answer"]))
    if not answer["ok"]:
        answer["error"].setdefault("message", "a plain reason")
    _validator("answer.schema.json").validate(answer)
    if answer["ok"]:
        assert "schema" in case, f"{case['name']} needs a result schema"
        _validator(case["schema"]).validate(answer["result"])


def test_the_schemas_refuse_what_cyclone_never_sends():
    profiles = _validator("profiles.result.schema.json")
    good = next(c for c in VECTORS["cases"] if c.get("schema") == "profiles.result.schema.json")["answer"]["result"]
    bad = json.loads(json.dumps(good))
    bad["profiles"][1]["vault"] = "x"
    with pytest.raises(jsonschema.ValidationError):
        profiles.validate(bad)
    with pytest.raises(jsonschema.ValidationError):
        _validator("answer.schema.json").validate({"ok": False, "error": {"code": "MADE_UP", "message": "x"}})
