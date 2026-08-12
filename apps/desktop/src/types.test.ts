import { describe, expect, it } from "vitest";
import { parseMessage } from "./types";
import type { Agent, Message } from "./types";

const chief: Agent = {
  id: "chief-id",
  slug: "chief",
  name: "Chief",
  role: "Coordinator",
  description: "Coordinates work.",
  avatar_color: "#70B7A7",
  avatar_shape: "round",
  status: "idle",
  hermes_profile: "default",
  workspace_path: "/workspace",
};

const researcher: Agent = {
  ...chief,
  id: "research-id",
  slug: "research",
  name: "Research",
  role: "Evidence specialist",
  avatar_color: "#6665E1",
  avatar_shape: "triangle",
};

function message(body: string, metadata: Record<string, unknown>): Message {
  return {
    id: "message-id",
    conversation_id: "conversation-id",
    author_type: "human",
    author_name: "You",
    kind: "message",
    body,
    metadata,
    source: "cyclone",
    created_at: "2026-08-12T12:00:00.000Z",
  };
}

describe("parseMessage", () => {
  it("turns persisted semantic agent ids into inline mention segments at their textual position", () => {
    const parsed = parseMessage(
      message("Please ask @Chief to pair with @Research before review.", {
        mention_agent_ids: ["chief-id", "research-id"],
      }),
      [chief, researcher],
    );

    expect(parsed.text).toEqual([
      { type: "text", text: "Please ask " },
      { type: "agent_mention", agentId: "chief-id", fallbackName: "Chief" },
      { type: "text", text: " to pair with " },
      { type: "agent_mention", agentId: "research-id", fallbackName: "Research" },
      { type: "text", text: " before review." },
    ]);
  });

  it("does not invent a computer preview when a message has no computer session metadata", () => {
    const parsed = parseMessage(message("Work is queued.", {}), [chief]);
    expect(parsed.computer).toBeUndefined();
  });
});
