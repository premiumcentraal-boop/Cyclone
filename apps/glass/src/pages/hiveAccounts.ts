/**
 * Vault → Hive accounts (plan 59, build 2, run 2b): every account in the hive, opened here with the hive key while
 * the vault is unlocked.
 *
 * - Each account shows its binding: the profile, and the phone it is on.
 * - **Bind** picks a profile on any phone in the hive. **Delete** moves it to the 30-day trash, with **Restore** and
 *   **Delete forever**. Every change goes to the hive as a new version, and every phone gets it when it next syncs.
 *   Nothing here touches the real account in the app.
 * - **Earlier versions**: the PC keeps the last ten. Using one writes it back as the newest.
 * - **Add from the vault**: logins that carry an identity card (build 1a) are put in the hive, so the phones have them too.
 */
import type { GlassContext } from "../app.js";
import { actionButton, card, chip, loadingState, segmented } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { relativeTime } from "../ui/format.js";
import { appName, identitiesApi } from "../services/identities.js";
import { hiveApi, type HiveState } from "../services/hive.js";
import {
  GLASS, TRASH_MS, accountFrom, edited, hiveItemsApi, newAccountId, openHiveKeys, openItem, sealAccount, sealTombstone, wipe,
  type HiveAccount, type HiveItem,
} from "../services/hiveItems.js";
import { profilesApi } from "../services/profiles.js";
import { IDENTITY_LABELS, type OpenItem } from "../services/vault.js";

type Show = "active" | "trash";
const REVEAL_MS = 30_000;

export interface HiveAccountsCard {
  element: HTMLElement;
  refresh(): Promise<void>;
}

export function createHiveAccounts(ctx: GlassContext, vk: () => CryptoKey | null, vaultItems: () => OpenItem[],
                                   say: (text: string, tone?: "ok" | "error") => void): HiveAccountsCard {
  const element = card("cc-card vault-card hive-accounts");
  let show: Show = "active";
  let state: HiveState | null = null;
  let accounts: HiveAccount[] = [];
  let unreadable = 0;
  let revealed: string | null = null;
  let history: { id: string; versions: HiveAccount[] } | null = null;
  const places: Array<{ value: string; label: string; device: string; deviceName: string; profile: string; profileLabel: string }> = [];
  const tabs = segmented<Show>([{ id: "active", label: "Accounts" }, { id: "trash", label: "Trash" }], show, (id) => {
    show = id;
    render();
  });

  /** Runs [block] with every hive key version open; they are wiped after. */
  async function withKeys<T>(block: (keys: Map<number, Uint8Array>, s: HiveState) => Promise<T>): Promise<T> {
    const key = vk();
    if (!key) throw new Error("Unlock the vault first.");
    const s = state ?? await hiveApi.get(ctx.client);
    const keys = await openHiveKeys(key, s);
    try {
      return await block(keys, s);
    } finally {
      wipe(keys);
    }
  }

  async function opened(keys: Map<number, Uint8Array>, items: HiveItem[]): Promise<HiveAccount[]> {
    const out: HiveAccount[] = [];
    unreadable = 0;
    for (const item of items) {
      try {
        const account = accountFrom(await openItem(keys, item), item);
        if (account) out.push(account);
        else unreadable++;
      } catch {
        unreadable++;
      }
    }
    return out;
  }

  async function refresh(): Promise<void> {
    if (!vk()) return render();
    try {
      state = await hiveApi.get(ctx.client);
      if (!state.exists) return render();
      accounts = await withKeys(async (keys) => opened(keys, await hiveItemsApi.list(ctx.client)));
      await loadPlaces(state);
    } catch (error) {
      say((error as Error).message || "The hive accounts couldn't be read.", "error");
    }
    render();
  }

  async function loadPlaces(s: HiveState): Promise<void> {
    places.length = 0;
    for (const m of s.members.filter((x) => x.state === "member")) {
      try {
        for (const p of (await profilesApi.list(ctx.client, m.deviceId)).profiles.filter((x) => !x.inTrash)) {
          places.push({ value: `${m.deviceId}|${p.id}`, label: `${p.label} on ${m.name}`, device: m.deviceId, deviceName: m.name, profile: p.id, profileLabel: p.label });
        }
      } catch {
        // A phone that isn't reachable just offers no profiles right now.
      }
    }
  }

  async function write(next: HiveAccount, label: string): Promise<void> {
    try {
      await withKeys(async (keys, s) => {
        const result = await hiveItemsApi.put(ctx.client, await sealAccount(keys.get(s.keyVersion)!, s.keyVersion, next));
        if (result === "gone") throw new Error("That account was deleted forever.");
      });
      say(`${label}. Every phone gets it when it next syncs.`, "ok");
    } catch (error) {
      say((error as Error).message || `${label} failed.`, "error");
    }
    await refresh();
  }

  async function purge(a: HiveAccount): Promise<void> {
    try {
      await withKeys(async (keys, s) => {
        await hiveItemsApi.put(ctx.client, await sealTombstone(keys.get(s.keyVersion)!, s.keyVersion, a.id, Math.max(Date.now(), a.updatedAtMs + 1)));
      });
      say("Deleted for good on every phone. The real account is untouched.", "ok");
    } catch (error) {
      say((error as Error).message || "Delete forever failed.", "error");
    }
    await refresh();
  }

  async function showHistory(a: HiveAccount): Promise<void> {
    try {
      const versions = await withKeys(async (keys) => opened(keys, await hiveItemsApi.history(ctx.client, a.id)));
      history = { id: a.id, versions };
    } catch (error) {
      say((error as Error).message || "Earlier versions couldn't be read.", "error");
    }
    render();
  }

  /** Vault logins with an identity card that the hive doesn't have yet (matched by app and handle). */
  async function addFromVault(): Promise<void> {
    try {
      const pc = (await identitiesApi.list(ctx.client)).identities;
      const have = new Set(accounts.map((a) => `${a.app}|${a.handle.toLowerCase()}`));
      let added = 0;
      for (const item of vaultItems()) {
        if (item.kind !== "login" || !item.identity || !item.accountId) continue;
        const record = pc.find((i) => i.accountId === item.accountId);
        if (!record || have.has(`${record.service}|${record.handle.toLowerCase()}`)) continue;
        const device = record.deviceId && record.profile ? record.deviceId : null;
        const now = Date.now();
        const account: HiveAccount = {
          id: newAccountId(), app: record.service, handle: record.handle, card: { ...item.identity }, password: item.secret,
          profileId: device ? record.profile!.id : null, profileLabel: device ? record.profile!.label : null, wasProfileLabel: record.wasProfile,
          createdAtMs: now, updatedAtMs: now, trashedAtMs: null, origin: record.origin, boundDevice: device,
          boundDeviceName: device ? ctx.devices.find((d) => d.id === device)?.name ?? null : null, updatedBy: GLASS,
        };
        await withKeys(async (keys, s) => { await hiveItemsApi.put(ctx.client, await sealAccount(keys.get(s.keyVersion)!, s.keyVersion, account)); });
        have.add(`${account.app}|${account.handle.toLowerCase()}`);
        added++;
      }
      say(added ? `Added ${added} ${added === 1 ? "account" : "accounts"} from the vault to the hive.` : "Every vault identity is already in the hive.", "ok");
    } catch (error) {
      say((error as Error).message || "Adding from the vault failed.", "error");
    }
    await refresh();
  }

  function badge(a: HiveAccount): [string, "accent" | "warning"] {
    if (a.profileId) {
      const profile = a.profileId === "main" ? "Main" : a.profileLabel || "A profile";
      return [`${profile} on ${a.boundDeviceName ?? a.boundDevice ?? "a phone"}`, "accent"];
    }
    return [a.wasProfileLabel ? `Not bound (was ${a.wasProfileLabel})` : "Not bound", "warning"];
  }

  function row(a: HiveAccount): HTMLElement {
    const box = el("div", "vault-phone hive-account");
    box.dataset.id = a.id;
    const head = el("div", "cc-row");
    head.append(el("strong", undefined, `${appName(a.app)} · ${a.handle || "account"}`), chip(...badge(a)));
    box.append(head, el("span", "cc-sub", `Changed ${relativeTime(a.updatedAtMs)} by ${a.updatedBy === GLASS ? "Glass" : a.boundDevice === a.updatedBy ? a.boundDeviceName ?? a.updatedBy : a.updatedBy}`));
    if (revealed === a.id) {
      const details = el("div", "hive-details");
      for (const [key, label] of IDENTITY_LABELS) if (a.card[key]) details.append(el("div", "cc-sub", `${label}: ${a.card[key]}`));
      if (a.password) details.append(el("div", "cc-sub mono", `Password: ${a.password}`));
      box.append(details);
    }
    const actions = el("div", "cc-row");
    if (a.trashedAtMs) {
      const days = Math.max(0, Math.ceil((a.trashedAtMs + TRASH_MS - Date.now()) / 86_400_000));
      actions.append(el("span", "cc-sub", `Deleted for good in ${days} day${days === 1 ? "" : "s"}`));
      const restore = actionButton("Restore", { variant: "secondary" });
      restore.addEventListener("click", () => void write(edited(a, { trashedAtMs: null }), "Restored"));
      const forever = actionButton("Delete forever", { variant: "danger" });
      forever.addEventListener("click", () => {
        if (forever.dataset.confirm !== "1") {
          forever.dataset.confirm = "1";
          forever.replaceChildren(el("span", "btn-label", "Delete Cyclone's copy on every phone? (The real account stays.)"));
          return;
        }
        void purge(a);
      });
      actions.append(restore, forever);
    } else {
      const details = actionButton(revealed === a.id ? "Hide" : "Details", { variant: "ghost" });
      details.addEventListener("click", () => {
        revealed = revealed === a.id ? null : a.id;
        if (revealed) setTimeout(() => { if (revealed === a.id) { revealed = null; render(); } }, REVEAL_MS);
        render();
      });
      const bind = el("select", "cc-input hive-bind");
      bind.setAttribute("aria-label", "Bind to profile");
      const first = el("option", undefined, a.profileId ? "Move to…" : "Bind to…");
      first.value = "";
      bind.append(first);
      for (const p of places) {
        if (p.device === a.boundDevice && p.profile === a.profileId) continue;
        const o = el("option", undefined, p.label);
        o.value = p.value;
        bind.append(o);
      }
      if (a.profileId) {
        const o = el("option", undefined, "Not bound");
        o.value = "__unbind";
        bind.append(o);
      }
      bind.addEventListener("change", () => {
        const value = bind.value;
        if (!value) return;
        if (value === "__unbind") {
          return void write(edited(a, { profileId: null, profileLabel: null, wasProfileLabel: a.profileLabel, boundDevice: null, boundDeviceName: null }), "Unbound");
        }
        const p = places.find((x) => x.value === value);
        if (!p) return;
        void write(edited(a, { profileId: p.profile, profileLabel: p.profileLabel, wasProfileLabel: null, boundDevice: p.device, boundDeviceName: p.deviceName }),
          `Bound to ${p.label}`);
      });
      const earlier = actionButton("Earlier versions", { variant: "ghost" });
      earlier.addEventListener("click", () => void showHistory(a));
      const trash = actionButton("Delete", { variant: "ghost" });
      trash.addEventListener("click", () => void write(edited(a, { trashedAtMs: Date.now() }), "Moved to the trash for 30 days"));
      actions.append(details, bind, earlier, trash);
    }
    box.append(actions);
    if (history?.id === a.id) {
      const list = el("div", "hive-history");
      if (!history.versions.length) list.append(el("p", "cc-hint", "No earlier versions."));
      for (const v of history.versions) {
        const line = el("div", "cc-row");
        line.append(el("span", "cc-sub", `${relativeTime(v.updatedAtMs)} · ${badge(v)[0]}${v.trashedAtMs ? " · in the trash" : ""}`));
        const use = actionButton("Use this version", { variant: "secondary" });
        use.addEventListener("click", () => { history = null; void write(edited(v, {}, Math.max(Date.now(), a.updatedAtMs + 1)), "Went back to that version"); });
        line.append(use);
        list.append(line);
      }
      box.append(list);
    }
    return box;
  }

  function render(): void {
    const parts: HTMLElement[] = [el("h2", "card-title", "Hive accounts")];
    if (!vk()) {
      parts.push(el("p", "cc-hint", "Unlock the vault to see the accounts every phone shares."));
      return setChildren(element, ...parts);
    }
    if (!state) {
      parts.push(loadingState("Opening the hive…"));
      return setChildren(element, ...parts);
    }
    if (!state.exists) {
      parts.push(el("p", "cc-hint", "Start the hive (above) to share accounts between phones."));
      return setChildren(element, ...parts);
    }
    parts.push(el("p", "cc-hint", "Every account the phones keep, opened here with the hive key. A change here reaches every phone when it next syncs; "
      + "deleting never touches the real account."));
    const add = actionButton("Add from the vault", { variant: "secondary" });
    add.addEventListener("click", () => void addFromVault());
    parts.push(tabs.element, add);
    if (unreadable) parts.push(el("p", "cc-hint", `${unreadable} ${unreadable === 1 ? "item" : "items"} couldn't be opened with this vault's hive key.`));
    const shown = accounts.filter((a) => (show === "trash") === Boolean(a.trashedAtMs))
      .sort((x, y) => x.app.localeCompare(y.app) || x.handle.localeCompare(y.handle));
    if (!shown.length) parts.push(el("p", "cc-hint", show === "trash" ? "The trash is empty." : "No accounts in the hive yet. Accounts the phones create arrive here at once."));
    for (const a of shown) parts.push(row(a));
    setChildren(element, ...parts);
  }

  render();
  void refresh();
  return { element, refresh };
}
