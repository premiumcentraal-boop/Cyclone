/**
 * Page operations in Glass (plan 76, stage 2, run 2a): the mirror of the gateway's `command/pageops.py`.
 *
 * Glass never sends a whole page. The page view keeps the page as the gateway last had it (a tree of versioned blocks),
 * the editor edits a flat copy where each block carries its `depth`, and a save sends the operations that turn the one
 * into the other (`diffOps`). The gateway applies them to the page in one transaction, checking each block's version.
 *
 * Text positions count characters, and a mention counts as one, as on the gateway.
 */
import type { Block, Span, TextBlock } from "./pages.js";
import { TEXT_TYPES } from "./pages.js";

export type PageOp =
  | { op: "insert"; block: Record<string, unknown>; parent: string | null; after: string | null }
  | { op: "delete"; id: string; v?: number }
  | { op: "move"; id: string; v?: number; parent: string | null; after: string | null }
  | { op: "set_type"; id: string; v?: number; type: string }
  | { op: "set"; id: string; v?: number; props: Record<string, unknown> }
  | { op: "text_insert"; id: string; v?: number; at: number; text: Span[] }
  | { op: "text_delete"; id: string; v?: number; at: number; len: number }
  | { op: "mark"; id: string; v?: number; from: number; to: number; mark: Mark; on: boolean }
  | { op: "page"; title?: string; icon?: string };

const MARKS = ["b", "i", "c", "s"] as const;
type Mark = (typeof MARKS)[number];
/** Fields with their own operations; `set` never carries them. `depth` is the editor's own and never leaves Glass. */
const STRUCTURAL = new Set(["id", "type", "text", "children", "v", "depth"]);

type Unit = ["t", string, string] | ["r", string];
type Node = Record<string, unknown> & { id: string; type: string; children?: Node[]; v?: number };

const isText = (type: unknown): boolean => TEXT_TYPES.includes(type as TextBlock["type"]);

function sortedJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(sortedJson).join(",")}]`;
  if (value && typeof value === "object") {
    const obj = value as Record<string, unknown>;
    return `{${Object.keys(obj).sort().map((k) => `${JSON.stringify(k)}:${sortedJson(obj[k])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

// ------------------------------------------------------------------------------------------------ text units

export function units(spans: Span[]): Unit[] {
  const out: Unit[] = [];
  for (const span of spans) {
    if ("ref" in span) {
      out.push(["r", sortedJson(span.ref)]);
      continue;
    }
    const marks = MARKS.filter((m) => (span as Record<string, unknown>)[m] === true).join("");
    for (const ch of Array.from(span.t ?? "")) out.push(["t", ch, marks]);
  }
  return out;
}

export function spansOf(list: Unit[]): Span[] {
  const out: Span[] = [];
  for (const unit of list) {
    if (unit[0] === "r") {
      out.push({ ref: JSON.parse(unit[1]) });
      continue;
    }
    const [, ch, marks] = unit;
    const last = out[out.length - 1] as Record<string, unknown> | undefined;
    if (last && "t" in last && MARKS.filter((m) => last[m] === true).join("") === marks) {
      last.t = String(last.t) + ch;
    } else {
      const piece: Record<string, unknown> = { t: ch };
      for (const m of marks) piece[m] = true;
      out.push(piece as Span);
    }
  }
  return out;
}

const sameUnit = (a: Unit, b: Unit): boolean => a[0] === b[0] && a[1] === b[1] && a[2] === b[2];

// ------------------------------------------------------------------------------------------------ the tree

/** The editor's flat list: every block, nested ones included, in reading order, each with its `depth`. */
export function flattenBlocks(tree: Block[], depth = 0): Block[] {
  const out: Block[] = [];
  for (const block of tree) {
    const { children, ...rest } = block as Block & { children?: Block[] };
    out.push({ ...rest, depth } as Block);
    if (children?.length) out.push(...flattenBlocks(children, depth + 1));
  }
  return out;
}

/** Back from the flat list to the tree: a block belongs to the nearest text block above it one level up. */
export function nestBlocks(flat: Block[]): Block[] {
  const root: Block[] = [];
  const stack: Array<{ depth: number; block: Block & { children?: Block[] } }> = [];
  for (const raw of flat) {
    const { depth: wanted = 0, ...rest } = raw as Block & { depth?: number };
    const block = { ...rest } as Block & { children?: Block[] };
    delete block.children;
    let depth = Math.max(0, wanted);
    while (stack.length && stack[stack.length - 1].depth >= depth) stack.pop();
    const parent = stack[stack.length - 1];
    if (parent && isText(parent.block.type)) {
      depth = parent.depth + 1;
      (parent.block.children ??= []).push(block);
    } else {
      depth = 0;
      stack.length = 0;
      root.push(block);
    }
    stack.push({ depth, block });
  }
  return root;
}

function walk(tree: Node[], parent: string | null = null, out: Array<[Node, Node[], string | null]> = []): Array<[Node, Node[], string | null]> {
  for (const block of tree) {
    out.push([block, tree, parent]);
    walk(block.children ?? [], block.id, out);
  }
  return out;
}

const find = (tree: Node[], id: unknown) => walk(tree).find(([b]) => b.id === id) ?? null;

function container(tree: Node[], parent: string | null): Node[] {
  if (parent === null) return tree;
  const found = find(tree, parent);
  if (!found) throw new Error("The block to nest under is gone.");
  return (found[0].children ??= []);
}

function place(list: Node[], block: Node, after: string | null): void {
  if (after === null) {
    list.unshift(block);
    return;
  }
  const at = list.findIndex((b) => b.id === after);
  if (at < 0) throw new Error("The block to place it after is gone.");
  list.splice(at + 1, 0, block);
}

function prune(tree: Node[]): void {
  for (const [block] of walk(tree)) if (block.children && !block.children.length) delete block.children;
}

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

// ------------------------------------------------------------------------------------------------ applying (tests, and checks)

/** Applies operations like the gateway does, without its version checks: Glass uses it to check its own diffs. */
export function applyOps(tree: Block[], ops: PageOp[]): Block[] {
  const work = clone(tree) as unknown as Node[];
  for (const op of ops) applyOne(work, op);
  prune(work);
  return work as unknown as Block[];
}

function applyOne(work: Node[], op: PageOp): void {
  if (op.op === "page") return;
  if (op.op === "insert") {
    place(container(work, op.parent), clone(op.block) as Node, op.after);
    return;
  }
  const found = find(work, op.id);
  if (!found) throw new Error("That block is gone.");
  const [block, siblings] = found;
  if (op.op === "delete") {
    siblings.splice(siblings.indexOf(block), 1);
  } else if (op.op === "move") {
    siblings.splice(siblings.indexOf(block), 1);
    place(container(work, op.parent), block, op.after);
  } else if (op.op === "set_type") {
    if (op.type === "todo") block.checked = block.type === "todo" ? Boolean(block.checked) : false;
    else delete block.checked;
    if (op.type === "callout") block.icon ??= "💡";
    else delete block.icon;
    block.type = op.type;
  } else if (op.op === "set") {
    for (const [k, v] of Object.entries(op.props)) {
      if (v === null) delete block[k];
      else block[k] = clone(v);
    }
  } else {
    const current = units((block.text as Span[]) ?? []);
    if (op.op === "text_insert") current.splice(op.at, 0, ...units(op.text));
    else if (op.op === "text_delete") current.splice(op.at, op.len);
    else {
      for (let i = op.from; i < op.to; i += 1) {
        const u = current[i];
        if (u[0] !== "t") continue;
        const marks = new Set(u[2].split("").filter(Boolean));
        if (op.on) marks.add(op.mark);
        else marks.delete(op.mark);
        current[i] = ["t", u[1], MARKS.filter((m) => marks.has(m)).join("")];
      }
    }
    block.text = spansOf(current);
  }
}

// ------------------------------------------------------------------------------------------------ diffing

function content(block: Node): string {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(block)) if (k !== "children" && k !== "v" && k !== "depth") out[k] = v;
  return sortedJson(out);
}

/**
 * The operations that turn `oldTree` (as the gateway has it, with versions) into `newTree` (the editor's, nested).
 * Operations on existing blocks carry the version from `oldTree`, so a block changed elsewhere refuses the batch.
 */
export function diffOps(oldTree: Block[], newTree: Block[], page: { title?: string; icon?: string } = {}): PageOp[] {
  const ops: PageOp[] = [];
  const work = clone(oldTree) as unknown as Node[];
  const versions = new Map(walk(oldTree as unknown as Node[]).map(([b]) => [b.id, b.v ?? 1]));
  const inserted = new Set<string>();
  const newIds = new Set(walk(newTree as unknown as Node[]).map(([b]) => b.id));

  const emit = (op: PageOp): void => {
    if ("id" in op && versions.has(op.id) && !inserted.has(op.id)) (op as { v?: number }).v = versions.get(op.id);
    ops.push(op);
    applyOne(work, op);
  };

  // A block that keeps its id but changes kind (a paragraph turned into a divider) is replaced: deleted, then inserted.
  const oldTypes = new Map(walk(oldTree as unknown as Node[]).map(([b]) => [b.id, b.type]));
  const replaced = new Set(walk(newTree as unknown as Node[])
    .filter(([b]) => oldTypes.has(b.id) && oldTypes.get(b.id) !== b.type && !(isText(b.type) && isText(oldTypes.get(b.id))))
    .map(([b]) => b.id));
  for (const [block] of walk(oldTree as unknown as Node[])) {
    if ((!newIds.has(block.id) || replaced.has(block.id)) && find(work, block.id)) emit({ op: "delete", id: block.id });
  }

  const visit = (blocks: Node[], parent: string | null): void => {
    let previous: string | null = null;
    for (const block of blocks) {
      const found = find(work, block.id);
      if (!found) {
        const fresh: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(block)) if (k !== "children" && k !== "v" && k !== "depth") fresh[k] = clone(v);
        emit({ op: "insert", block: fresh, parent, after: previous });
        inserted.add(block.id);
      } else {
        const [current, siblings, currentParent] = found;
        const at = siblings.indexOf(current);
        const currentAfter = at > 0 ? siblings[at - 1].id : null;
        if (currentParent !== parent || currentAfter !== previous) emit({ op: "move", id: block.id, parent, after: previous });
        contentOps(emit, current, block);
      }
      visit(block.children ?? [], block.id);
      previous = block.id;
    }
  };
  visit(newTree as unknown as Node[], null);
  if (page.title !== undefined || page.icon !== undefined) ops.push({ op: "page", ...page });
  return ops;
}

function contentOps(emit: (op: PageOp) => void, current: Node, target: Node): void {
  if (content(current) === content(target)) return;
  if (current.type !== target.type) emit({ op: "set_type", id: current.id, type: target.type });  // both text: others are replaced
  if (isText(current.type)) {
    const a = units((current.text as Span[]) ?? []);
    const b = units((target.text as Span[]) ?? []);
    let prefix = 0;
    while (prefix < Math.min(a.length, b.length) && sameUnit(a[prefix], b[prefix])) prefix += 1;
    let suffix = 0;
    while (suffix < Math.min(a.length, b.length) - prefix && sameUnit(a[a.length - 1 - suffix], b[b.length - 1 - suffix])) suffix += 1;
    const removed = a.length - prefix - suffix;
    if (removed > 0) emit({ op: "text_delete", id: current.id, at: prefix, len: removed });
    const added = b.slice(prefix, b.length - suffix);
    if (added.length) emit({ op: "text_insert", id: current.id, at: prefix, text: spansOf(added) });
  }
  const props: Record<string, unknown> = {};
  for (const key of new Set([...Object.keys(current), ...Object.keys(target)])) {
    if (STRUCTURAL.has(key)) continue;
    if (sortedJson(current[key]) !== sortedJson(target[key])) props[key] = target[key] === undefined ? null : clone(target[key]);
  }
  if (Object.keys(props).length) emit({ op: "set", id: current.id, props });
}
