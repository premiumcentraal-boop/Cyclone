import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { parseDictionary, parseModels, waitingLine, whereLine } from "../.test-dist/services/dictionary.js";
import { dictionaryView } from "../.test-dist/ui/dictionaryView.js";
import { startSheet } from "../.test-dist/ui/missionPanel.js";

const ANCHOR = { kind: "view", roomKey: "screen:list:0123456789abcdef", screenTitle: "Messages", position: 0, siblings: ["General", "Requests"], groups: [], rowShape: null, searchable: false, searchLabel: null };
const LIST = { ...ANCHOR, kind: "list", position: null, siblings: [], groups: ["Today"], rowShape: "2 texts · image", searchable: true, searchLabel: "Search" };
const set = (over) => ({
  id: "set:messages", kind: "conversation", name: "Messages", shownName: "Messages", nameProof: "lexicon", aliases: [], parentId: null,
  path: "Conversation › Messages", status: "confirmed", redirectTo: null, anchors: [LIST], markers: [], observations: 3, days: 1,
  versions: ["402.0"], missedPasses: 0, note: null, failedGates: [], waiting: null, ...over,
});
const DICT = {
  placeId: "package:com.instagram.android", appLabel: "Instagram", currentVersion: "402.0", passes: 2, updatedAt: 1,
  coreKinds: [{ wire: "person", label: "Person" }, { wire: "conversation", label: "Conversation" }],
  entries: [
    set({}),
    set({ id: "set:primary", name: "Primary", shownName: "Primary", parentId: "set:messages", path: "Conversation › Messages › Primary", anchors: [ANCHOR, { ...LIST }] }),
    set({ id: "set:requests", name: "Requests", shownName: "Requests", parentId: "set:messages", path: "Conversation › Messages › Requests", status: "candidate", observations: 1,
      failedGates: ["seen_twice"], waiting: "wait: seen once so far", anchors: [{ ...ANCHOR, position: 2, siblings: ["Primary", "General"] }] }),
    set({ id: "set:followers", kind: "person", name: "followers", shownName: "Followers", path: "Person › Followers", markers: ["Remove"], anchors: [{ ...ANCHOR, screenTitle: null, siblings: ["following"] }] }),
    set({ id: "set:old", name: "Old", shownName: "Old", status: "merged", redirectTo: "set:messages" }),
    { ...set({}), id: "not-a-set" },
    { ...set({}), id: "set:bad", status: "exploded" },
  ],
  truncated: false,
  audit: [{ at: 1, action: "confirmed", entryId: "set:primary", detail: "“Primary”: named by the app, own place on screen, seen twice", by: "gate" }],
  health: { orphans: [], nearDuplicates: [["set:primary", "set:requests"]], tooDeep: [], tooWide: [], staleCandidates: [], notSeenInVersion: [] },
  jev: { summary: "Agreed 3 of 4 · 0.2 s", answered: 4, agreed: 3, sureAnswered: 0, sureAgreed: 0 },
  glossary: "Dictionary of Instagram (the app's own groups; ids are stable):\n  set:messages = Conversation › Messages",
};

test("the dictionary reply is read strictly", () => {
  const view = parseDictionary(DICT);
  assert.deepEqual(view.sets.map((s) => s.id), ["set:messages", "set:primary", "set:requests", "set:followers", "set:old"]);
  assert.equal(whereLine(view.sets[1]), "a category on “Messages” next to “General”, “Requests” · a list (rows: 2 texts · image) · find one: “Search” · grouped Today");
  assert.equal(whereLine(view.sets[3]), "a category next to “following” · rows show “Remove”");
  assert.equal(waitingLine(view.sets[2]), "seen once so far");
  assert.equal(parseModels({ active: { id: "anthropic/claude-fable-5.1", label: "Claude Fable 5.1", vision: true, key: "sk" }, models: [{ id: "bad id!", label: "x" }] }).models.length, 0);
});

test("the Dictionary tab shows the tree, why a set waits, health and what an agent reads", () => {
  installMiniDom();
  const edits = [];
  const state = { showHidden: false, renaming: null };
  const paint = () => dictionaryView(parseDictionary(DICT), state, { edit: (...args) => edits.push(args) }, () => undefined);
  const root = paint();
  const rows = root.querySelectorAll(".dictionary-set");
  assert.deepEqual(rows.map((r) => r.dataset.setId), ["set:messages", "set:primary", "set:requests", "set:followers"], "merged sets are hidden by default");
  assert.equal(rows[1].style.marginLeft, "20px", "sub-categories are indented under their parent");
  assert.match(rows[2].textContent, /Waiting: seen once so far/);
  assert.match(root.textContent, /“Primary” and “Requests” look alike/);
  assert.match(root.textContent, /never keeps who is in a group/);
  assert.match(root.querySelector(".dictionary-glossary").textContent, /set:messages = Conversation › Messages/);
  const lock = rows[0].querySelectorAll("button").find((b) => b.textContent === "Lock");
  lock.click();
  assert.deepEqual(edits.at(-1), ["lock", "set:messages"]);
  const confirm = rows[2].querySelectorAll("button").find((b) => b.textContent === "Confirm");
  confirm.click();
  assert.deepEqual(edits.at(-1), ["confirm", "set:requests"]);
  const merge = rows[2].querySelector(".dictionary-merge");
  merge.value = "set:primary";
  merge.dispatchEvent({ type: "change" });
  assert.deepEqual(edits.at(-1), ["merge", "set:requests", { into: "set:primary" }]);
  const move = rows[3].querySelector(".dictionary-move");
  move.value = "-";
  move.dispatchEvent({ type: "change" });
  assert.deepEqual(edits.at(-1), ["move", "set:followers", { parentId: null }]);
  state.showHidden = true;
  const all = paint();
  const old = all.querySelectorAll(".dictionary-set").find((r) => r.dataset.setId === "set:old");
  assert.match(old.textContent, /Same as Messages/);
  old.querySelectorAll("button").find((b) => b.textContent === "Undo merge").click();
  assert.deepEqual(edits.at(-1), ["unmerge", "set:old"]);
});

test("an empty dictionary explains itself in plain words", () => {
  installMiniDom();
  const root = dictionaryView(parseDictionary({ ...DICT, entries: [] }), { showHidden: false, renaming: null }, { edit() {} }, () => undefined);
  assert.match(root.textContent, /No groups yet/);
  assert.match(root.textContent, /never keeps who is in them/);
});

test("the start sheet offers the phone's models, the phone's own first", async () => {
  installMiniDom();
  let started = null;
  const models = Promise.resolve({ active: { id: "anthropic/claude-fable-5.1", label: "Claude Fable 5.1", vision: true },
    models: [{ id: "anthropic/claude-fable-5.1", label: "Claude Fable 5.1", vision: true }, { id: "z-ai/glm-5.3-flash", label: "GLM 5.3 Flash", vision: false }] });
  const sheet = startSheet({ appLabel: "Instagram", models, onStart: (m) => { started = m; }, onCancel() {} });
  await models;
  await new Promise((r) => setTimeout(r, 0));
  const select = sheet.querySelector(".model-select");
  const labels = select.querySelectorAll("option").map((o) => o.textContent);
  assert.deepEqual(labels, ["Phone's model · Claude Fable 5.1", "GLM 5.3 Flash"]);
  assert.match(sheet.textContent, /Your key stays on the phone/);
  sheet.querySelectorAll("button").find((b) => b.textContent === "Start mapping").click();
  assert.equal(started.model, "phone");
  select.value = "z-ai/glm-5.3-flash";
  select.dispatchEvent({ type: "change" });
  sheet.querySelectorAll("button").find((b) => b.textContent === "Start mapping").click();
  assert.equal(started.model, "z-ai/glm-5.3-flash");
});
