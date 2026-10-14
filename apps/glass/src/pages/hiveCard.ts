/**
 * Vault → Hive (plan 59, build 2, run 2a): which phones share Cyclone's collective store, and its key.
 *
 * Shown only while the vault is unlocked, because the hive key is opened with the vault key.
 * - **Start the hive:** makes the hive key here and keeps it on the PC, wrapped by the vault key.
 * - **Add to hive:** seals the hive key to that phone's trusted device key. The PC relays it when the phone is reachable.
 * - **Remove from hive:** the phone wipes its hive key when it next connects. This tab then makes a new hive key and
 *   seals it to the phones that stay; that happens by itself as soon as the hive needs it.
 * - **After a key change** (run 2b) it re-encrypts every item still under an older key, so a phone that joins later
 *   can read them.
 */
import type { GlassContext } from "../app.js";
import { actionButton, card, chip, loadingState } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import { deliveryApi, type Phone } from "../services/delivery.js";
import { hiveApi, newHiveKey, openHiveKey, sealHiveKey, type HiveMember, type HiveState } from "../services/hive.js";
import { hiveItemsApi, openHiveKeys, openItem, sealItem, wipe } from "../services/hiveItems.js";

export interface HiveCard {
  element: HTMLElement;
  refresh(): Promise<void>;
}

const STATE_LABEL: Record<HiveMember["state"], [string, "success" | "accent" | "warning" | "neutral"]> = {
  member: ["In the hive", "success"], joining: ["Joining", "accent"], leaving: ["Leaving", "warning"], left: ["Left", "neutral"],
};

export function createHiveCard(ctx: GlassContext, vk: () => CryptoKey | null, say: (text: string, tone?: "ok" | "error") => void): HiveCard {
  const element = card("cc-card vault-card hive-card");
  let busy = false;

  async function refresh(): Promise<void> {
    let state: HiveState;
    let phones: Phone[];
    try {
      [state, phones] = await Promise.all([hiveApi.get(ctx.client), deliveryApi.phones(ctx.client)]);
    } catch (error) {
      setChildren(element, el("h2", "card-title", "Hive"), el("p", "cc-hint", (error as Error).message));
      return;
    }
    const key = vk();
    if (key && state.exists && !busy) state = await keepTheHiveCurrent(key, state, phones);
    render(state, phones);
  }

  /** A phone left: make a new key. Phones without the current key: seal it to them. Both need the vault unlocked. */
  async function keepTheHiveCurrent(key: CryptoKey, state: HiveState, phones: Phone[]): Promise<HiveState> {
    busy = true;
    try {
      if (state.rotationNeeded) {
        const made = await newHiveKey(key, state.keyVersion + 1);
        made.raw.fill(0);
        state = await hiveApi.putKey(ctx.client, state.keyVersion + 1, made.wrapped);
        say("A phone left the hive, so it has a new key. Sending it to the phones that stay.");
      }
      if (state.staleItems > 0 && !state.rotationNeeded) {
        // Run 2b: items written before the key changed are re-encrypted under the new key, so a phone that joins now
        // can read them. Same version (time and writer), newer key.
        const keys = await openHiveKeys(key, state);
        try {
          const current = keys.get(state.keyVersion)!;
          for (const item of await hiveItemsApi.list(ctx.client)) {
            if (item.keyVersion >= state.keyVersion) continue;
            const body = await openItem(keys, item);
            await hiveItemsApi.put(ctx.client, await sealItem(current, state.keyVersion, { itemId: item.itemId, at: item.at, by: item.by, gone: item.gone }, body));
          }
        } finally {
          wipe(keys);
        }
        state = await hiveApi.get(ctx.client);
      }
      if (state.needsKey.length) {
        const raw = await openHiveKey(key, state);
        try {
          for (const deviceId of state.needsKey) {
            const phone = phones.find((p) => p.deviceId === deviceId);
            if (!phone?.key?.trusted) continue;
            state = await hiveApi.add(ctx.client, deviceId, await sealHiveKey({ deviceId, publicKey: phone.key.publicKey, fingerprint: phone.key.fingerprint },
              state.keyVersion, raw));
          }
        } finally {
          raw.fill(0);
        }
      }
    } catch (error) {
      say((error as Error).message || "The hive key couldn't be updated.", "error");
    } finally {
      busy = false;
    }
    return state;
  }

  async function act(label: string, action: () => Promise<unknown>): Promise<void> {
    try {
      await action();
      say(`${label}.`);
    } catch (error) {
      say((error as Error).message || `${label} failed.`, "error");
    }
    await refresh();
  }

  function render(state: HiveState, phones: Phone[]): void {
    const key = vk();
    const parts: HTMLElement[] = [
      el("h2", "card-title", "Hive"),
      el("p", "cc-hint", "Phones in the hive share one store of accounts and verified skills, synced through this PC even while Glass is closed. "
        + "Only your phones and this browser can read it; the PC keeps it locked."),
    ];
    if (!key) {
      parts.push(el("p", "cc-hint", "Unlock the vault to start the hive or add a phone."));
      return setChildren(element, ...parts);
    }
    if (!state.exists) {
      const start = actionButton("Start the hive", { variant: "primary" });
      start.addEventListener("click", () => void act("The hive is started", async () => {
        const made = await newHiveKey(key, 1);
        made.raw.fill(0);
        await hiveApi.putKey(ctx.client, 1, made.wrapped);
      }));
      parts.push(start);
      return setChildren(element, ...parts);
    }
    parts.push(el("p", "cc-sub", `Hive key version ${state.keyVersion} · ${state.items} ${state.items === 1 ? "account" : "accounts"}`));
    const members = new Map(state.members.map((m) => [m.deviceId, m]));
    const ids = [...new Set([...phones.map((p) => p.deviceId), ...state.members.filter((m) => m.state !== "left").map((m) => m.deviceId)])];
    for (const id of ids) {
      const phone = phones.find((p) => p.deviceId === id);
      const member = members.get(id);
      const row = el("div", "vault-phone hive-phone");
      row.dataset.id = id;
      const head = el("div", "cc-row");
      head.append(el("strong", undefined, member?.name ?? phone?.name ?? id));
      if (member && member.state !== "left") head.append(chip(...STATE_LABEL[member.state]));
      if (member?.kind === "rented") head.append(chip("Rented", "neutral"));
      row.append(head);
      if (member?.syncedAt) row.append(el("span", "cc-sub", `Last synced ${relativeTime(member.syncedAt)}`));
      if (member?.note) row.append(el("p", "cc-hint", member.note));
      const inHive = member && (member.state === "member" || member.state === "joining");
      if (inHive) {
        const remove = actionButton("Remove from hive", { variant: "ghost" });
        remove.addEventListener("click", () => {
          if (remove.dataset.confirm !== "1") {
            remove.dataset.confirm = "1";
            remove.replaceChildren(el("span", "btn-label", "Remove: its hive copy is wiped and the key replaced"));
            return;
          }
          void act("Removed from the hive", () => hiveApi.remove(ctx.client, id));
        });
        row.append(remove);
      } else if (member?.state !== "leaving") {
        if (!phone?.key?.trusted) row.append(el("p", "cc-hint", "Trust this phone's key first (Phones, above)."));
        else {
          const add = actionButton("Add to hive", { variant: "primary" });
          add.addEventListener("click", () => void act("Sent the hive key; it reaches the phone when it's connected", async () => {
            const raw = await openHiveKey(key, state);
            try {
              await hiveApi.add(ctx.client, id, await sealHiveKey({ deviceId: id, publicKey: phone.key!.publicKey, fingerprint: phone.key!.fingerprint },
                state.keyVersion, raw));
            } finally {
              raw.fill(0);
            }
          }));
          row.append(add);
        }
      }
      parts.push(row);
    }
    if (!ids.length) parts.push(el("p", "cc-hint", "No phone is connected."));
    setChildren(element, ...parts);
  }

  setChildren(element, el("h2", "card-title", "Hive"), loadingState("Loading the hive…"));
  void refresh();
  return { element, refresh };
}
