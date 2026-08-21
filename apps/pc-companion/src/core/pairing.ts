export const PAIR_CODE_LENGTH = 4;

export function normalizePairCode(raw: string): string {
  return raw.toUpperCase().replace(/[^A-Z]/g, "").slice(0, PAIR_CODE_LENGTH);
}

export function updatePairCodeAt(code: string, index: number, raw: string): string {
  const chars = normalizePairCode(code).padEnd(PAIR_CODE_LENGTH, " ").split("");
  const next = normalizePairCode(raw).slice(-1);
  chars[index] = next || " ";
  return chars.join("").replace(/ /g, "").slice(0, PAIR_CODE_LENGTH);
}

export function pairSecondsRemaining(expiresAtEpochMs: number, nowEpochMs: number): number {
  return Math.max(0, Math.ceil((expiresAtEpochMs - nowEpochMs) / 1000));
}

export function isPairCodeComplete(code: string): boolean {
  return normalizePairCode(code).length === PAIR_CODE_LENGTH;
}
