import test from "node:test";
import assert from "node:assert/strict";
import {
  buildInstagramPresetInvocation,
  INSTAGRAM_ANDROID_PACKAGE,
  INSTAGRAM_LAYER2_GOAL_MAX,
  INSTAGRAM_PRESETS,
  INSTAGRAM_PRESET_SOURCE_REF,
  queueInstagramPreset,
} from "../.test-dist/core/instagramPresets.js";
import { MockDesktopService } from "../.test-dist/services/mockDesktopService.js";

test("catalog mirrors every first-class Instagram task from the pinned source", () => {
  assert.equal(INSTAGRAM_PRESET_SOURCE_REF, "b909752df7af7a595714ed660af7cc971ec408d5");
  assert.deepEqual(
    INSTAGRAM_PRESETS.map((preset) => preset.sourceTaskType),
    ["doomscroll", "doomscroll-following", "cold-dms", "post"],
  );
  assert.equal(new Set(INSTAGRAM_PRESETS.map((preset) => preset.workspaceId)).size, 4);
});

test("warmup and Following presets retain pacing and verification semantics", () => {
  const warmup = buildInstagramPresetInvocation("warmup", {
    durationMinutes: 20,
    personality: "engaged",
    likeEnabled: true,
    commentEnabled: false,
  });
  assert.equal(warmup.appPackage, INSTAGRAM_ANDROID_PACKAGE);
  assert.match(warmup.goal, /Home for 20m/i);
  assert.match(warmup.goal, /engaged pacing/i);
  assert.match(warmup.goal, /verify like state/i);
  assert.match(warmup.goal, /No comments/i);
  assert.ok(warmup.goal.length <= INSTAGRAM_LAYER2_GOAL_MAX);

  const following = buildInstagramPresetInvocation("engage-following", {
    durationMinutes: 12,
    personality: "casual",
    likeEnabled: false,
  });
  assert.match(following.goal, /Following for 12m/i);
  assert.match(following.goal, /No likes/i);
});

test("cold DMs bind exact recipients, exact message and phone SEND gate", () => {
  const dm = buildInstagramPresetInvocation("cold-dms", {
    handles: ["@Alice", "bob"],
    message: "Hello from the reviewed preset",
    cycles: 1,
  });
  assert.match(dm.goal, /@alice,@bob/);
  assert.match(dm.goal, /Hello from the reviewed preset/);
  assert.match(dm.goal, /requires phone GATE\/human confirmation/i);
  assert.match(dm.goal, /sent-state/i);
  assert.ok(dm.goal.length <= INSTAGRAM_LAYER2_GOAL_MAX);
});

test("publish requires explicit confirmation while draft does not", () => {
  assert.throws(
    () => buildInstagramPresetInvocation("post", {
      destination: "publish",
      mediaInstructions: "the newest photo of the blue product",
      caption: "Launch day",
    }),
    /explicit confirmation/i,
  );

  const draft = buildInstagramPresetInvocation("post", {
    destination: "draft",
    mediaInstructions: "the newest photo of the blue product",
    caption: "Launch day",
  });
  assert.match(draft.goal, /Save DRAFT/i);
  assert.match(draft.goal, /never publish/i);
  assert.ok(draft.goal.length <= INSTAGRAM_LAYER2_GOAL_MAX);

  const publish = buildInstagramPresetInvocation("post", {
    destination: "publish",
    publishConfirmed: true,
    mediaInstructions: "the newest photo of the blue product",
    caption: "Launch day",
  });
  assert.match(publish.goal, /PUBLISH requires phone SEND\/GATE/i);
  assert.match(publish.goal, /verify posted-state/i);
});

test("oversized preset details fail instead of being silently truncated", () => {
  assert.throws(
    () => buildInstagramPresetInvocation("cold-dms", {
      handles: Array.from({ length: 20 }, (_, index) => `very_long_handle_${index}`),
      message: "This is an intentionally long but otherwise valid message that cannot be safely represented together with twenty recipients inside one bounded Layer 2 goal.",
    }),
    /500-character/i,
  );
});

test("queue registers a phone-side Instagram workspace then arms its goal", async () => {
  const service = new MockDesktopService(1);
  const invocation = buildInstagramPresetInvocation("warmup", {
    durationMinutes: 5,
    personality: "skimmer",
    likeEnabled: false,
  });
  const status = await queueInstagramPreset(service, "phone-1", invocation);
  const workspace = status.workspaces.find((item) => item.id === invocation.preset.workspaceId);
  assert.ok(workspace);
  assert.equal(workspace.appPackage, INSTAGRAM_ANDROID_PACKAGE);
  assert.ok(status.armed.includes(invocation.preset.workspaceId));
  assert.equal(status.goals?.[invocation.preset.workspaceId], invocation.goal);
});
