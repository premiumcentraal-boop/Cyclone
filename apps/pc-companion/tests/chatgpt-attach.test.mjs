import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  CHATGPT_ATTACH_TITLE,
  CUSTOM_GPT_SETUP_HINTS,
  HANDOFF_PUBLIC_FIELDS,
  SECRET_FIELD_NAMES,
  assertHandoffSafe,
  buildAttachBlock,
  buildFleetHandoff,
  collectSecrets,
  emptyChatgptAttachConfig,
  handoffContainsForbidden,
  newPadDraft,
  publicControlApi,
} from "../.test-dist/core/chatgptAttach.js";
import { MockDesktopService } from "../.test-dist/services/mockDesktopService.js";

const secretPad = {
  id: "pad-1",
  label: "pad-1",
  ok: true,
  deviceId: "dev_vmos_pad1",
  serial: "localhost:63670",
  adb: "device",
  mobile: "running",
  sessionId: "sess-public",
  sessionToken: "tok-public-not-a-transport-secret",
  sessionSource: "control-api",
};

test("tab copy is ChatGPT Attach and lists Custom GPT setup hints", () => {
  assert.equal(CHATGPT_ATTACH_TITLE, "ChatGPT Attach");
  assert.ok(CUSTOM_GPT_SETUP_HINTS.some((item) => /OpenAPI/i.test(item)));
  assert.ok(CUSTOM_GPT_SETUP_HINTS.some((item) => /SESSION_TOKEN/.test(item)));
});

test("handoff fields are the public attach contract only", () => {
  assert.deepEqual([...HANDOFF_PUBLIC_FIELDS], [
    "DEVICE_ID", "SESSION_ID", "SESSION_TOKEN", "CONTROL_API", "MOBILE", "ADB", "GOAL", "NOTES",
  ]);
  assert.ok(SECRET_FIELD_NAMES.includes("connectKey"));
  assert.ok(SECRET_FIELD_NAMES.includes("accessKey"));
});

test("fleet handoff never includes SSH/VMOS secrets or serials", () => {
  const config = emptyChatgptAttachConfig();
  config.pads = [newPadDraft()];
  config.pads[0].connectKey = "SUPER-CONNECT-KEY-DO-NOT-LEAK";
  config.vmosApiKey = "VMOSACCESSKEY123456";
  const result = {
    ok: true,
    controlApi: "https://control.example/cloud",
    generatedAt: "2026-09-12 12:00:00",
    pads: [secretPad],
  };
  const text = buildFleetHandoff(result, "Open Settings", collectSecrets(config));
  assert.match(text, /CYCLONE_VMOS_ATTACH_v1/);
  assert.match(text, /DEVICE_ID: dev_vmos_pad1/);
  assert.match(text, /SESSION_TOKEN: tok-public-not-a-transport-secret/);
  assert.doesNotMatch(text, /SERIAL:/);
  assert.doesNotMatch(text, /connectKey/i);
  assert.doesNotMatch(text, /AccessKey\s*:/i);
  assert.doesNotMatch(text, /SUPER-CONNECT-KEY-DO-NOT-LEAK/);
  assert.doesNotMatch(text, /VMOSACCESSKEY123456/);
  assert.equal(handoffContainsForbidden(text, collectSecrets(config)).length, 0);
  assert.doesNotThrow(() => assertHandoffSafe(text, collectSecrets(config)));
});

test("failed pads stay in the summary without attach blocks or secrets", () => {
  const text = buildFleetHandoff({
    ok: false,
    controlApi: "",
    generatedAt: "now",
    pads: [{
      id: "pad-2",
      label: "pad-2",
      ok: false,
      deviceId: "",
      adb: "failed",
      mobile: "unknown",
      sessionId: "",
      sessionToken: "",
      sessionSource: "local-stub",
      error: "connectKey=should-strip ssh failed",
    }],
  }, "Wait");
  assert.match(text, /FAILED — \[redacted\]/);
  assert.doesNotMatch(text, /should-strip/);
  assert.doesNotMatch(text, /CYCLONE_VMOS_ATTACH_v1/);
});

test("attach block uses only the public field set", () => {
  const block = buildAttachBlock(secretPad, "https://control.example/cloud", "Observe");
  for (const field of HANDOFF_PUBLIC_FIELDS) assert.match(block, new RegExp(`${field}:`));
  assert.doesNotMatch(block, /LABEL:/);
  assert.doesNotMatch(block, /SERIAL:/);
});

test("public control API falls back to loopback cloud stub then placeholder", () => {
  assert.equal(publicControlApi(" https://mine.example/cloud/ "), "https://mine.example/cloud");
  assert.equal(publicControlApi("", 8791), "http://127.0.0.1:8791/cloud");
  assert.equal(publicControlApi(""), "https://CONTROL_API_HOST_PLACEHOLDER");
});

test("bundled Custom GPT pack has OpenAPI Actions and no VMOS AccessKey samples", () => {
  const pack = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../src-tauri/resources/chatgpt-attach");
  const openapi = fs.readFileSync(path.join(pack, "openapi-cloud-control.yaml"), "utf8");
  const instructions = fs.readFileSync(path.join(pack, "CUSTOM_GPT_INSTRUCTIONS.md"), "utf8");
  assert.match(openapi, /operationId: observe/);
  assert.match(openapi, /operationId: tap/);
  assert.match(openapi, /PhoneToolExecutor/);
  assert.doesNotMatch(openapi, /simulateTouch/);
  assert.match(instructions, /CYCLONE_VMOS_ATTACH_v1/);
  assert.doesNotMatch(instructions, /AccessKey:/);
});

test("mock service can save pads, sync, and copy a safe handoff", async () => {
  const service = new MockDesktopService(1);
  const saved = await service.saveChatgptAttachConfig({
    controlApiBase: "https://control.example/cloud",
    defaultGoal: "Prove observe",
    pads: [{ ...newPadDraft(), connectKey: "PAD-SECRET-KEY" }],
  });
  assert.equal(saved.pads[0].hasConnectKey, true);
  assert.equal(saved.pads[0].connectKey, undefined);
  const synced = await service.syncChatgptAttachFleet();
  assert.equal(synced.ok, true);
  assert.equal(synced.pads[0].ok, true);
  const markdown = buildFleetHandoff(synced, saved.defaultGoal, ["PAD-SECRET-KEY"]);
  const copied = await service.copyChatgptHandoff(markdown);
  assert.equal(copied.ok, true);
  assert.doesNotMatch(copied.markdown, /PAD-SECRET-KEY/);
});
