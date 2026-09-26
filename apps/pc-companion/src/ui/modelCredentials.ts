import type { ModelCredentials } from "../services/types.js";

// Session-only. Cleared on page reload. Never written to disk, storage, or
// sent anywhere except as part of a task/command/scene request the person
// explicitly triggered - matches the product rule that OpenRouter keys are
// never persisted (see AGENTS.md).
let cached: ModelCredentials | null = null;

/** Returns cached credentials, or prompts once and caches for this session.
 * Returns null if the person cancels any prompt. */
export function ensureCredentials(): ModelCredentials | null {
  if (cached) return cached;
  const model = window.prompt("OpenRouter model id (e.g. anthropic/claude-sonnet-4.5)", "")?.trim();
  if (!model) return null;
  const providerRaw = window.prompt("Provider(s) to allow, comma-separated", "")?.trim();
  if (!providerRaw) return null;
  const apiKey = window.prompt("OpenRouter API key (kept in this browser tab only, never saved)", "")?.trim();
  if (!apiKey) return null;
  const providers = providerRaw.split(",").map((p) => p.trim()).filter(Boolean);
  cached = { model, providers, apiKey };
  return cached;
}

export function clearCredentials(): void {
  cached = null;
}

export function currentCredentials(): ModelCredentials | null {
  return cached;
}
