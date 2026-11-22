"""Page operations (plan 76, stage 2, run 2a): every edit to a page is a typed operation.

A page is a tree of blocks (a text block may hold ``children``). Every block carries ``v``, its own version. Glass, the AI
and every other writer send a batch of operations; the gateway applies the batch to one page in one transaction, all or
nothing.

Operations:
- ``insert`` {block, parent?, after?}: a new block (its own id) under ``parent`` (a block id, or the page) after
  ``after`` (a sibling id), or first.
- ``delete`` {id, v}: a block and everything nested in it.
- ``move`` {id, v, parent?, after?}: a block (with its children) to a new place.
- ``set_type`` {id, v, type}: a text block becomes another text type; its words and children stay.
- ``set`` {id, v, props}: any other field of a block (a to-do's tick, a callout's icon, a view's source…).
- ``text_insert`` {id, v, at, text}: pieces of text (``{t, marks…}`` or ``{ref}``) at a position in a block's text.
- ``text_delete`` {id, v, at, len}: a range of a block's text.
- ``mark`` {id, v, from, to, mark, on}: bold, italic, code or strike on or off over a range.
- ``page`` {title?, icon?}: the page's own title and icon.

Positions in a text count characters, and a mention (``{ref}``) counts as one.

**Versions.** An operation on an existing block names the version it was made against (``v``). If the block has changed
since, the whole batch is refused with :class:`OpConflict`, and the writer falls back to merging (Glass keeps its
three-way merge for this). Every block a batch changes gains one version, whatever the number of operations on it.

:func:`diff_ops` computes the operations that turn one tree into another, so a writer that holds a whole new page (the
AI's proposal, an import) still writes through operations.
"""
from __future__ import annotations

import copy
import json
from typing import Any, Iterator

from .center import CommandError
from .pages import MARKS, TEXT_BLOCKS, PageConflict, _new  # noqa: F401 - _new is used by callers making ids

OPS = ("insert", "delete", "move", "set_type", "set", "text_insert", "text_delete", "mark", "page")
MAX_OPS = 2_000
#: Fields an operation may not change through ``set``: they have their own operations.
STRUCTURAL = {"id", "type", "text", "children", "v"}


class OpConflict(PageConflict):
    """The batch was made against an older version of a block (or a block that is gone). Nothing was applied."""

    def __init__(self, message: str, *, block_id: str | None = None, index: int | None = None) -> None:
        super().__init__(message)
        self.block_id = block_id
        self.index = index


# ---------------------------------------------------------------------------------------------------------- text units

def units(spans: list[dict[str, Any]]) -> list[tuple[Any, ...]]:
    """A block's text as units: one per character (with its marks), one per mention."""
    out: list[tuple[Any, ...]] = []
    for span in spans:
        if "ref" in span:
            out.append(("r", json.dumps(span["ref"], sort_keys=True)))
            continue
        marks = tuple(m for m in MARKS if span.get(m) is True)
        out.extend(("t", ch, marks) for ch in span.get("t", ""))
    return out


def spans_of(units_: list[tuple[Any, ...]]) -> list[dict[str, Any]]:
    """Units back to spans, merging neighbours with the same marks (the canonical form)."""
    out: list[dict[str, Any]] = []
    for unit in units_:
        if unit[0] == "r":
            out.append({"ref": json.loads(unit[1])})
            continue
        _, ch, marks = unit
        last = out[-1] if out else None
        if last is not None and "t" in last and tuple(m for m in MARKS if last.get(m)) == marks:
            last["t"] += ch
        else:
            piece: dict[str, Any] = {"t": ch}
            for m in marks:
                piece[m] = True
            out.append(piece)
    return out


def normalize_spans(spans: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return spans_of(units(spans))


# ---------------------------------------------------------------------------------------------------------- the tree

def _walk(blocks: list[dict[str, Any]], parent: str | None = None) -> Iterator[tuple[dict[str, Any], list[dict[str, Any]], str | None]]:
    for block in blocks:
        yield block, blocks, parent
        yield from _walk(block.get("children") or [], block["id"])


def _find(blocks: list[dict[str, Any]], block_id: Any) -> tuple[dict[str, Any], list[dict[str, Any]], str | None] | None:
    for found in _walk(blocks):
        if found[0]["id"] == block_id:
            return found
    return None


def _container(blocks: list[dict[str, Any]], parent: Any) -> list[dict[str, Any]]:
    if parent is None:
        return blocks
    found = _find(blocks, parent)
    if found is None:
        raise OpConflict("The block to nest under is gone.", block_id=parent if isinstance(parent, str) else None)
    block = found[0]
    if block["type"] not in TEXT_BLOCKS:
        raise CommandError("Only text blocks hold other blocks.")
    return block.setdefault("children", [])


def _place(container: list[dict[str, Any]], block: dict[str, Any], after: Any) -> None:
    if after is None:
        container.insert(0, block)
        return
    for index, sibling in enumerate(container):
        if sibling["id"] == after:
            container.insert(index + 1, block)
            return
    raise OpConflict("The block to place it after is gone or moved.", block_id=after if isinstance(after, str) else None)


def _prune(blocks: list[dict[str, Any]]) -> None:
    """Drop empty ``children`` lists, so a tree has one written form."""
    for block, _, _ in list(_walk(blocks)):
        if "children" in block and not block["children"]:
            del block["children"]


def _contains(block: dict[str, Any], target: Any) -> bool:
    return any(b["id"] == target for b, _, _ in _walk([block]))


# ---------------------------------------------------------------------------------------------------------- applying

def apply_ops(blocks: list[dict[str, Any]], ops: Any, *, check_versions: bool = True) -> tuple[list[dict[str, Any]], dict[str, Any], set[str]]:
    """Apply a batch to a copy of ``blocks``. Returns (new blocks, page fields to change, ids of changed blocks).

    Raises :class:`OpConflict` when an operation was made against an older version, :class:`CommandError` when one is
    malformed. Either way nothing is returned and the caller stores nothing. Validation of the result (types, sizes,
    secrets) is the caller's: ``validate_blocks``.
    """
    if not isinstance(ops, list) or not ops:
        raise CommandError("Send {ops: [...]}, at least one operation.")
    if len(ops) > MAX_OPS:
        raise CommandError(f"Send at most {MAX_OPS} operations at once.")
    tree = copy.deepcopy(blocks)
    start = {b["id"]: b.get("v", 1) for b, _, _ in _walk(tree)}
    created: set[str] = set()
    touched: set[str] = set()
    page: dict[str, Any] = {}
    for index, op in enumerate(ops):
        try:
            _apply_one(tree, op, start, created, touched, page, check_versions)
        except OpConflict as exc:
            exc.index = index
            raise
    _prune(tree)
    for block, _, _ in _walk(tree):
        if block["id"] in created:
            block["v"] = 1
        elif block["id"] in touched:
            block["v"] = start.get(block["id"], 1) + 1
    return tree, page, touched | created


def _target(tree: list[dict[str, Any]], op: dict[str, Any], start: dict[str, int], created: set[str], check: bool) -> tuple[dict[str, Any], list[dict[str, Any]], str | None]:
    block_id = op.get("id")
    found = _find(tree, block_id)
    if found is None:
        raise OpConflict("That block is gone: it was deleted elsewhere.", block_id=block_id if isinstance(block_id, str) else None)
    if check and block_id not in created:
        expected = op.get("v")
        if type(expected) is not int:
            raise CommandError(f"{op.get('op')} names the version of the block it was made on (v).")
        if expected != start.get(block_id):
            raise OpConflict("That block changed elsewhere since you opened the page.", block_id=block_id)
    return found


def _apply_one(tree: list[dict[str, Any]], op: Any, start: dict[str, int], created: set[str], touched: set[str],
               page: dict[str, Any], check: bool) -> None:
    if not isinstance(op, dict) or op.get("op") not in OPS:
        raise CommandError(f"An operation is one of: {', '.join(OPS)}.")
    kind = op["op"]
    if kind == "page":
        _keys(op, {"op", "title", "icon"})
        for key in ("title", "icon"):
            if key in op:
                page[key] = op[key]
        return
    if kind == "insert":
        _keys(op, {"op", "block", "parent", "after"})
        block = op.get("block")
        if not isinstance(block, dict) or not isinstance(block.get("id"), str):
            raise CommandError("insert carries a block with its own id.")
        block = copy.deepcopy(block)
        new_ids = [b["id"] for b, _, _ in _walk([block])]
        existing = {b["id"] for b, _, _ in _walk(tree)}
        if any(i in existing for i in new_ids):
            raise CommandError("A block with that id is already on the page.")
        _place(_container(tree, op.get("parent")), block, op.get("after"))
        created.update(new_ids)
        return
    if kind == "delete":
        _keys(op, {"op", "id", "v"})
        block, siblings, _ = _target(tree, op, start, created, check)
        siblings.remove(block)
        touched.update(b["id"] for b, _, _ in _walk([block]))
        return
    if kind == "move":
        _keys(op, {"op", "id", "v", "parent", "after"})
        block, siblings, _ = _target(tree, op, start, created, check)
        parent, after = op.get("parent"), op.get("after")
        if parent is not None and _contains(block, parent):
            raise CommandError("A block cannot move inside itself.")
        if after == block["id"]:
            raise CommandError("A block cannot move after itself.")
        siblings.remove(block)
        _place(_container(tree, parent), block, after)
        touched.add(block["id"])
        return
    if kind == "set_type":
        _keys(op, {"op", "id", "v", "type"})
        block, _, _ = _target(tree, op, start, created, check)
        new_type = op.get("type")
        if block["type"] not in TEXT_BLOCKS or new_type not in TEXT_BLOCKS:
            raise CommandError("Only a text block turns into another text type.")
        if new_type == "todo":
            block["checked"] = bool(block.get("checked")) if block["type"] == "todo" else False
        else:
            block.pop("checked", None)
        if new_type == "callout":
            block.setdefault("icon", "💡")
        else:
            block.pop("icon", None)
        block["type"] = new_type
        touched.add(block["id"])
        return
    if kind == "set":
        _keys(op, {"op", "id", "v", "props"})
        block, _, _ = _target(tree, op, start, created, check)
        props = op.get("props")
        if not isinstance(props, dict) or not props or STRUCTURAL & set(props):
            raise CommandError("set changes a block's other fields (not id, type, text, children or v).")
        for key, value in props.items():
            if value is None:
                block.pop(key, None)
            else:
                block[key] = copy.deepcopy(value)
        touched.add(block["id"])
        return
    # Text operations.
    block, _, _ = _target(tree, op, start, created, check)
    if block["type"] not in TEXT_BLOCKS:
        raise CommandError("Only text blocks have text.")
    current = units(block.get("text") or [])
    if kind == "text_insert":
        _keys(op, {"op", "id", "v", "at", "text"})
        at, pieces = op.get("at"), op.get("text")
        if type(at) is not int or not 0 <= at <= len(current):
            raise CommandError("text_insert's position is inside the block's text.")
        if not isinstance(pieces, list) or not pieces:
            raise CommandError("text_insert carries pieces of text.")
        current[at:at] = units(_pieces(pieces))
    elif kind == "text_delete":
        _keys(op, {"op", "id", "v", "at", "len"})
        at, length = op.get("at"), op.get("len")
        if type(at) is not int or type(length) is not int or length < 1 or at < 0 or at + length > len(current):
            raise CommandError("text_delete's range is inside the block's text.")
        del current[at:at + length]
    else:  # mark
        _keys(op, {"op", "id", "v", "from", "to", "mark", "on"})
        start_at, end, mark, on = op.get("from"), op.get("to"), op.get("mark"), op.get("on")
        if type(start_at) is not int or type(end) is not int or not 0 <= start_at < end <= len(current):
            raise CommandError("mark's range is inside the block's text.")
        if mark not in MARKS or type(on) is not bool:
            raise CommandError(f"mark is one of {', '.join(MARKS)}, turned on or off.")
        for i in range(start_at, end):
            unit = current[i]
            if unit[0] == "t":
                marks = set(unit[2]) | {mark} if on else set(unit[2]) - {mark}
                current[i] = ("t", unit[1], tuple(m for m in MARKS if m in marks))
    block["text"] = spans_of(current)
    touched.add(block["id"])


def _pieces(value: list[Any]) -> list[dict[str, Any]]:
    out = []
    for piece in value:
        if not isinstance(piece, dict) or not (("t" in piece and isinstance(piece["t"], str)) or "ref" in piece):
            raise CommandError("A piece of text is {t, marks…} or {ref}.")
        out.append(piece)
    return out


def _keys(op: dict[str, Any], allowed: set[str]) -> None:
    extra = set(op) - allowed
    if extra:
        raise CommandError(f"{op.get('op')}: unknown field {sorted(extra)[0]}.")


# ---------------------------------------------------------------------------------------------------------- diffing

def _content(block: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in block.items() if k not in ("children", "v")}


def diff_ops(old: list[dict[str, Any]], new: list[dict[str, Any]], *, title: str | None = None, icon: str | None = None) -> list[dict[str, Any]]:
    """The operations that turn the tree ``old`` into ``new`` (versions from ``old``). Applying them to ``old`` gives
    ``new`` exactly, apart from the versions the batch raises."""
    ops: list[dict[str, Any]] = []
    work = copy.deepcopy(old)
    versions = {b["id"]: b.get("v", 1) for b, _, _ in _walk(old)}
    new_ids = {b["id"] for b, _, _ in _walk(new)}
    inserted: set[str] = set()

    def emit(op: dict[str, Any]) -> None:
        if op.get("id") in versions and op["op"] not in ("insert", "page") and op.get("id") not in inserted:
            op["v"] = versions[op["id"]]
        ops.append(op)
        _apply_one(work, op, versions, inserted, set(), {}, False)

    # A block that keeps its id but changes kind (a paragraph turned into a divider) is replaced: deleted, then inserted.
    old_types = {b["id"]: b["type"] for b, _, _ in _walk(old)}
    replaced = {b["id"] for b, _, _ in _walk(new) if b["id"] in old_types and b["type"] != old_types[b["id"]]
                and not (b["type"] in TEXT_BLOCKS and old_types[b["id"]] in TEXT_BLOCKS)}
    # Deletes first, outermost only (deleting a block takes its children with it).
    for block, _, _ in list(_walk(old)):
        if (block["id"] not in new_ids or block["id"] in replaced) and _find(work, block["id"]) is not None:
            emit({"op": "delete", "id": block["id"]})

    def visit(blocks: list[dict[str, Any]], parent: str | None) -> None:
        previous: str | None = None
        for block in blocks:
            found = _find(work, block["id"])
            if found is None:
                fresh = {k: copy.deepcopy(v) for k, v in block.items() if k not in ("children", "v")}
                emit({"op": "insert", "block": fresh, "parent": parent, "after": previous})
                inserted.add(block["id"])
            else:
                current, siblings, current_parent = found
                at = siblings.index(current)
                current_after = siblings[at - 1]["id"] if at > 0 else None
                if current_parent != parent or current_after != previous:
                    emit({"op": "move", "id": block["id"], "parent": parent, "after": previous})
                _content_ops(emit, current, block)
            visit(block.get("children") or [], block["id"])
            previous = block["id"]

    visit(new, None)
    page: dict[str, Any] = {}
    if title is not None:
        page["title"] = title
    if icon is not None:
        page["icon"] = icon
    if page:
        ops.append({"op": "page", **page})
    return ops


def _content_ops(emit: Any, current: dict[str, Any], target: dict[str, Any]) -> None:
    if _content(current) == _content(target):
        return
    if current["type"] != target["type"]:
        emit({"op": "set_type", "id": current["id"], "type": target["type"]})  # both text: other kinds are replaced
    if current["type"] in TEXT_BLOCKS:
        old_u, new_u = units(current.get("text") or []), units(target.get("text") or [])
        if old_u != new_u:
            prefix = 0
            while prefix < min(len(old_u), len(new_u)) and old_u[prefix] == new_u[prefix]:
                prefix += 1
            suffix = 0
            while (suffix < min(len(old_u), len(new_u)) - prefix
                   and old_u[len(old_u) - 1 - suffix] == new_u[len(new_u) - 1 - suffix]):
                suffix += 1
            removed = len(old_u) - prefix - suffix
            if removed:
                emit({"op": "text_delete", "id": current["id"], "at": prefix, "len": removed})
            added = new_u[prefix:len(new_u) - suffix]
            if added:
                emit({"op": "text_insert", "id": current["id"], "at": prefix, "text": spans_of(added)})
    props: dict[str, Any] = {}
    for key in set(_content(current)) | set(_content(target)):
        if key in STRUCTURAL:
            continue
        if current.get(key) != target.get(key):
            props[key] = copy.deepcopy(target.get(key))
    if props:
        emit({"op": "set", "id": current["id"], "props": props})
