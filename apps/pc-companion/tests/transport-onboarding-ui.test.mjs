import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";

const source = fs.readFileSync(new URL("../src/ui/transportOnboarding.ts", import.meta.url), "utf8");

test("transport onboarding UI exposes USB, Wireless and VMOS first-connect paths", () => {
  assert.match(source, /Connect phone/);
  assert.match(source, /Check USB/);
  assert.match(source, /Pair & connect/);
  assert.match(source, /Connect VMOS phone/);
  assert.match(source, /Developer options → USB debugging/);
  assert.match(source, /Wireless debugging/);
});

test("wireless pairing code is cleared before async submit and never logged", () => {
  const clearIndex = source.indexOf('code.input.value = ""');
  const submitIndex = source.indexOf("client.pairWireless");
  assert.ok(clearIndex > 0 && submitIndex > clearIndex);
  assert.doesNotMatch(source, /console\.(log|error|warn).*pair/i);
  assert.match(source, /type = "password"|field\("6-digit pairing code"/);
});

test("successful transport invokes normal Cyclone fleet rescan", () => {
  assert.match(source, /await service\.scanDevices\(\)/);
  assert.match(source, /transport route already refreshes DeviceFleetManager/);
});
