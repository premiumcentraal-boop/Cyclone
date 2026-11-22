import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { applyOps, diffOps, flattenBlocks, nestBlocks, spansOf, units } from "../.test-dist/services/pageops.js";

const PARITY = JSON.parse(readFileSync(new URL("./fixtures/pageops-parity.json", import.meta.url), "utf8"));

/** A tree without versions or depths, for comparing content. */
function strip(blocks) {
  return blocks.map((b) => {
    const out = {};
    for (const [k, v] of Object.entries(b)) if (k !== "v" && k !== "children" && k !== "depth") out[k] = v;
    if (b.children?.length) out.children = strip(b.children);
    return out;
  });
}

test("Glass applies the gateway's operations to the same page (80 shared cases)", () => {
  for (const [i, c] of PARITY.cases.entries()) {
    assert.deepEqual(strip(applyOps(c.old, c.ops)), c.new, `case ${i}`);
  }
});

test("Glass's own diff reaches the same page and back on every shared case", () => {
  for (const [i, c] of PARITY.cases.entries()) {
    const ops = diffOps(c.old, c.new);
    const reached = applyOps(c.old, ops);
    assert.deepEqual(strip(reached), c.new, `case ${i}: forward`);
    assert.deepEqual(strip(applyOps(reached, diffOps(reached, strip(c.old)))), strip(c.old), `case ${i}: back`);
    for (const op of ops) if (op.op !== "insert" && op.op !== "page" && c.old.some(function has(b) { return b.id === op.id || (b.children ?? []).some(has); })) {
      assert.equal(typeof op.v, "number", `case ${i}: an operation on an existing block names its version`);
    }
  }
});

test("the editor's flat list keeps a nested page's shape", () => {
  const tree = [
    { id: "b1", type: "bullet", v: 3, text: [{ t: "parent" }], children: [
      { id: "b2", type: "p", v: 1, text: [{ t: "child" }], children: [{ id: "b3", type: "todo", v: 1, checked: true, text: [] }] },
    ] },
    { id: "b4", type: "divider", v: 1 },
  ];
  const flat = flattenBlocks(tree);
  assert.deepEqual(flat.map((b) => [b.id, b.depth]), [["b1", 0], ["b2", 1], ["b3", 2], ["b4", 0]]);
  assert.deepEqual(nestBlocks(flat), tree);
  // A block deeper than its neighbour allows is lifted to one level below it; nothing nests under a divider.
  assert.deepEqual(nestBlocks([{ id: "x1", type: "p", text: [], depth: 0 }, { id: "x2", type: "p", text: [], depth: 4 }])[0].children[0].id, "x2");
  assert.deepEqual(nestBlocks([{ id: "d1", type: "divider", depth: 0 }, { id: "x3", type: "p", text: [], depth: 1 }]).map((b) => b.id), ["d1", "x3"]);
});

test("text positions count characters, and a mention as one", () => {
  const spans = [{ t: "a😀b" }, { ref: { kind: "page", id: "pg_abcdefgh", label: "Other" } }, { t: "c", b: true }];
  assert.equal(units(spans).length, 5);
  assert.deepEqual(spansOf(units(spans)), spans);
  const ops = diffOps([{ id: "b1", type: "p", v: 2, text: spans }], [{ id: "b1", type: "p", text: [{ t: "a😀X" }, spans[1], spans[2]] }]);
  assert.deepEqual(ops, [{ op: "text_delete", id: "b1", v: 2, at: 2, len: 1 }, { op: "text_insert", id: "b1", v: 2, at: 2, text: [{ t: "X" }] }]);
});

test("a block that changes kind is replaced, and a page change carries the title", () => {
  const ops = diffOps([{ id: "b1", type: "p", v: 4, text: [{ t: "---" }] }], [{ id: "b1", type: "divider" }], { title: "New name" });
  assert.deepEqual(ops.map((o) => o.op), ["delete", "insert", "page"]);
  assert.equal(ops[0].v, 4);
  assert.deepEqual(applyOps([{ id: "b1", type: "p", v: 4, text: [] }], ops), [{ id: "b1", type: "divider" }]);
});
