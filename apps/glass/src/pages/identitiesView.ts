/**
 * Command Center → Accounts → Identities (plan 59, build 1): every account Cyclone keeps, with a clear badge for the
 * Cyclone profile it is bound to, and the 30-day trash.
 *
 * - The badge says which profile the account belongs to, or "Not bound (was Food)" after that profile was deleted. Its
 *   identity and password stay saved either way.
 * - **Bind to profile** picks a profile from that phone's list. **Switch phone** puts the phone on that profile.
 * - **Delete** moves it to the trash for 30 days, with Restore and Delete forever. Deleting never touches the real
 *   account in the app.
 *
 * Values (names, birthday, email, password) are never shown here: they open in the Vault, behind its check.
 */
import type { GlassContext } from "../app.js";
import { actionButton, card, chip, emptyState, errorState, loadingState, segmented } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { appName, identitiesApi, profileBadge, type Identity } from "../services/identities.js";
import { profilesApi, type PhoneProfile } from "../services/profiles.js";

type Show = "all" | "unbound" | "trash";

export interface IdentitiesView {
  element: HTMLElement;
  refresh(): Promise<void>;
}

function option(value: string, label: string): HTMLOptionElement {
  const node = el("option", undefined, label);
  node.value = value;
  return node;
}

export function createIdentitiesView(ctx: GlassContext, say: (text: string, tone?: "ok" | "error") => void): IdentitiesView {
  const element = card("cc-card id-card");
  const listBox = el("div", "id-list");
  let show: Show = "all";
  let items: Identity[] = [];
  let trashDays = 30;
  let error: { code?: string; message: string } | null = null;
  let loaded = false;
  const profiles = new Map<string, PhoneProfile[]>();
  const tabs = segmented<Show>([{ id: "all", label: "All" }, { id: "unbound", label: "Not bound" }, { id: "trash", label: "Trash" }], show, (id) => {
    show = id;
    void refresh();
  });
  element.append(
    el("h2", "card-title", "Identities"),
    el("p", "cc-hint", "Every account Cyclone keeps, and the profile it belongs to. Its details and password stay saved even when that profile is deleted. "
      + "Deleting here only removes Cyclone's copy; it never closes the real account."),
    tabs.element, listBox);

  const deviceName = (id: string | null): string => (id ? ctx.devices.find((d) => d.id === id)?.name ?? id : "No phone");

  async function phoneProfiles(device: string): Promise<PhoneProfile[]> {
    if (!profiles.has(device)) {
      try {
        profiles.set(device, (await profilesApi.list(ctx.client, device)).profiles.filter((p) => !p.inTrash));
      } catch {
        profiles.set(device, []);
      }
    }
    return profiles.get(device) ?? [];
  }

  async function refresh(): Promise<void> {
    try {
      const r = await identitiesApi.list(ctx.client, show === "trash");
      items = show === "unbound" ? r.identities.filter((i) => !i.profile) : r.identities;
      trashDays = r.trashDays;
      error = null;
    } catch (err) {
      error = { code: (err as { code?: string }).code, message: (err as Error).message || "Identities couldn't be read." };
    }
    loaded = true;
    render();
  }

  async function act(label: string, action: () => Promise<unknown>): Promise<void> {
    try {
      await action();
      say(`${label}.`, "ok");
    } catch (err) {
      say((err as Error).message || `${label} failed.`, "error");
    }
    await refresh();
  }

  function render(): void {
    if (!loaded) return setChildren(listBox, loadingState("Reading identities…"));
    if (error) return setChildren(listBox, errorState("Identities couldn't be read", error, () => void refresh()));
    if (!items.length) {
      return setChildren(listBox, emptyState({
        icon: "user",
        title: show === "trash" ? "The trash is empty" : show === "unbound" ? "Every identity is bound to a profile" : "No identities yet",
        body: show === "trash" ? `Deleted identities stay here for ${trashDays} days.` : "Accounts Cyclone creates, or that you add, show here with their profile.",
      }));
    }
    const table = el("table", "cc-table id-table");
    const head = el("tr");
    for (const h of ["Account", "Profile", "Phone", "Saved", ""]) head.append(el("th", undefined, h));
    table.append(head);
    for (const i of items) table.append(row(i));
    setChildren(listBox, table);
  }

  function row(i: Identity): HTMLElement {
    const tr = el("tr");
    tr.dataset.id = i.accountId;
    const who = el("td");
    who.append(el("strong", undefined, `${appName(i.service)} · ${i.handle}`), el("div", "cc-sub", i.service));
    const badge = el("td");
    badge.append(chip(profileBadge(i), i.profile ? "accent" : "warning"));
    const saved = el("td", undefined, [i.hasPassword ? "Password" : "", i.vaultItems ? "Identity card in the vault" : ""].filter(Boolean).join(" · ") || "Account only");
    const actions = el("td", "cc-row");
    if (i.trashedAt) {
      const days = Math.max(0, Math.ceil(((i.purgeAt ?? 0) - Date.now()) / 86_400_000));
      actions.append(el("span", "cc-sub", `Deleted for good in ${days} day${days === 1 ? "" : "s"}`));
      const restore = actionButton("Restore", { variant: "secondary" });
      restore.addEventListener("click", () => void act("Restored", () => identitiesApi.restore(ctx.client, i.accountId)));
      const purge = actionButton("Delete forever", { variant: "danger" });
      purge.addEventListener("click", () => {
        if (purge.dataset.confirm !== "1") {
          purge.dataset.confirm = "1";
          purge.replaceChildren(el("span", "btn-label", "Delete Cyclone's copy forever? (The real account stays.)"));
          return;
        }
        void act("Deleted for good", () => identitiesApi.purge(ctx.client, i.accountId));
      });
      actions.append(restore, purge);
    } else {
      const bind = el("select", "cc-input id-bind");
      bind.setAttribute("aria-label", "Bind to profile");
      bind.append(option("", i.profile ? "Change profile…" : "Bind to profile…"));
      if (i.deviceId) {
        void phoneProfiles(i.deviceId).then((list) => {
          for (const p of list) if (p.id !== i.profile?.id) bind.append(option(p.id, p.label));
          if (i.profile) bind.append(option("__unbind", "Not bound"));
        });
      }
      bind.addEventListener("change", () => {
        const value = bind.value;
        if (!value || !i.deviceId) return;
        if (value === "__unbind") return void act("Unbound", () => identitiesApi.unbind(ctx.client, i.accountId));
        const label = profiles.get(i.deviceId)?.find((p) => p.id === value)?.label ?? null;
        void act(`Bound to ${label ?? "the profile"}`, () => identitiesApi.bind(ctx.client, i.accountId, i.deviceId!, value, label));
      });
      actions.append(bind);
      if (i.profile && i.deviceId) {
        const open = actionButton("Switch phone", { variant: "ghost" });
        open.title = `Put ${deviceName(i.deviceId)} on the ${profileBadge(i)} profile`;
        open.addEventListener("click", () => void act(`Switching to ${profileBadge(i)}`, () => profilesApi.switchTo(ctx.client, i.deviceId!, i.profile!.id)));
        actions.append(open);
      }
      const trash = actionButton("Delete", { variant: "ghost" });
      trash.addEventListener("click", () => void act(`Moved to the trash for ${trashDays} days`, () => identitiesApi.trash(ctx.client, i.accountId)));
      actions.append(trash);
    }
    tr.append(who, badge, el("td", undefined, deviceName(i.deviceId)), saved, actions);
    return tr;
  }

  render();
  void refresh();
  return { element, refresh };
}
