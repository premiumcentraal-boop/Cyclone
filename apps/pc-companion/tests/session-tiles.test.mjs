import test from "node:test";
import assert from "node:assert/strict";
import {
  applySessionEvent,
  bindSessionTile,
  isSessionFabricEvent,
  markSessionInventory,
  parseFleetWsEvent,
  readExactSessionSnapshotHeaders,
  sessionDisplayLabel,
} from "../.test-dist/core/sessionTiles.js";
import { MockDesktopService } from "../.test-dist/services/mockDesktopService.js";

test("session tiles bind to session_id and never invent display 0 for named workspaces", () => {
  const foreground = bindSessionTile("phone-1", {
    sessionId: "default-foreground",
    displayId: 0,
    state: "FOREGROUND",
    executable: true,
  });
  assert.equal(foreground.sessionId, "default-foreground");
  assert.equal(foreground.kind, "foreground");
  assert.equal(foreground.displayId, 0);

  const named = bindSessionTile("phone-1", {
    sessionId: "workspace-mail",
    displayId: 2,
    state: "RUNNING",
    targetPackage: "com.google.android.gm",
  });
  assert.equal(named.sessionId, "workspace-mail");
  assert.equal(named.kind, "workspace");
  assert.equal(named.displayId, 2);

  const missingDisplay = bindSessionTile("phone-1", {
    sessionId: "workspace-maps",
    state: "RUNNING",
  });
  assert.equal(missingDisplay.kind, "workspace");
  assert.equal(missingDisplay.displayId, undefined);
  assert.notEqual(missingDisplay.displayId, 0);
  assert.equal(sessionDisplayLabel(missingDisplay), "unknown (not display 0)");

  const zeroRewrite = bindSessionTile("phone-1", {
    sessionId: "workspace-chat",
    displayId: 0,
    state: "RUNNING",
  });
  assert.equal(zeroRewrite.kind, "workspace");
  assert.equal(zeroRewrite.displayId, undefined);
  assert.match(sessionDisplayLabel(zeroRewrite), /not display 0/);
});

test("session.added adds a tile and session.removed removes it", () => {
  let tiles = [];
  const added = parseFleetWsEvent({
    event: "session.added",
    payload: { sessionId: "workspace-chrome", displayId: 2, deviceId: "phone-1" },
  });
  assert.ok(added);
  assert.equal(isSessionFabricEvent(added), true);
  tiles = applySessionEvent(tiles, added);
  assert.equal(tiles.length, 1);
  assert.equal(tiles[0].sessionId, "workspace-chrome");
  assert.equal(tiles[0].displayId, 2);
  assert.equal(tiles[0].deviceId, "phone-1");

  const removed = parseFleetWsEvent({ event: "session.removed", sessionId: "workspace-chrome", deviceId: "phone-1" });
  tiles = applySessionEvent(tiles, removed);
  assert.equal(tiles.length, 0);
});

test("mock fleet emits session.added and session.removed without a gateway", async () => {
  const service = new MockDesktopService(1);
  const events = [];
  const unwatch = service.watchFleet((event) => { if (event) events.push(event); });
  const listed = await service.listDeviceSessions("phone-1");
  assert.ok(listed.sessions.some((session) => session.sessionId === "default-foreground"));
  assert.ok(listed.sessions.some((session) => session.sessionId === "workspace-phone-1" && session.displayId === 2));

  const started = await service.startDeviceSession("phone-1", "com.android.chrome");
  assert.equal(started.session.displayId > 0, true);
  assert.notEqual(started.session.displayId, 0);
  assert.ok(events.some((event) => event.event === "session.added" && event.sessionId === started.session.sessionId));

  await service.stopDeviceSession("phone-1", started.session.sessionId);
  assert.ok(events.some((event) => event.event === "session.removed" && event.sessionId === started.session.sessionId));
  unwatch();
});

test("exact-session snapshot refuses foreground-substituted frames", () => {
  assert.throws(
    () => readExactSessionSnapshotHeaders({ get: () => null }),
    /unproven background preview/,
  );
  assert.throws(
    () => readExactSessionSnapshotHeaders({
      get(name) {
        if (name === "X-Cyclone-Foreground-Substitution") return "false";
        if (name === "X-Cyclone-Display-Id") return "0";
        return null;
      },
    }),
    /invalid display identity/,
  );
  const ok = readExactSessionSnapshotHeaders({
    get(name) {
      if (name === "X-Cyclone-Foreground-Substitution") return "false";
      if (name === "X-Cyclone-Display-Id") return "2";
      return null;
    },
  });
  assert.equal(ok.displayId, 2);
});

test("yield_ai and take_human control kinds are sent", async () => {
  const service = new MockDesktopService(4);
  const take = await service.sendControl("phone-1", { type: "take_human" });
  assert.equal(take.ok, true);
  assert.equal(take.verification, "HUMAN_HAS_CONTROL");
  assert.equal(take.inputOwner, "HUMAN");
  const yieldAi = await service.sendControl("phone-1", { type: "yield_ai" });
  assert.equal(yieldAi.ok, true);
  assert.equal(yieldAi.verification, "AI_HAS_CONTROL");
  assert.equal(yieldAi.inputOwner, "AI");
  const locked = await service.sendControl("phone-3", { type: "yield_ai" });
  assert.equal(locked.ok, false);
  assert.equal(locked.verification, "PHONE_LOCKED");
});

test("extra workspace tiles are inventory, not extra hot Ask slots", () => {
  const tiles = markSessionInventory([
    bindSessionTile("phone-1", { sessionId: "default-foreground", displayId: 0, state: "FOREGROUND" }),
    bindSessionTile("phone-1", { sessionId: "workspace-a", displayId: 2, state: "RUNNING" }),
    bindSessionTile("phone-1", { sessionId: "workspace-b", displayId: 3, state: "RUNNING" }),
  ]);
  assert.equal(tiles[0].inventory, false);
  assert.equal(tiles[1].hotAsk, true);
  assert.equal(tiles[1].inventory, false);
  assert.equal(tiles[2].inventory, true);
  assert.equal(tiles[2].hotAsk, false);
});
