/**
 * Cloud phones on the Devices page (plan 44, alpha 90): add a VMOS Cloud, DuoPlus or remote-ADB account, pick the
 * phones this PC keeps connected, and see each one's link in one line. Once connected a cloud phone shows up above
 * with the other phones: connecting, installing Cyclone and phone care work as they do for a USB phone.
 *
 * Alpha.117: a VMOS account can rent phones right here, by the period (a day, a week, a month) or pay-for-time. A
 * rented phone is kept connected, so Cyclone and the skills arrive on it by themselves. Pay-for-time phones power on
 * and off here; rentals renew here; any phone can be backed up when the owner asks, and restored from its own
 * backup. Every payment and every restore asks the owner to confirm first, with the exact price or what is replaced.
 */
import { actionButton, chip, segmented, type Tone } from "../ui/components.js";
import { el, setChildren } from "../ui/dom.js";
import { GatewayError, type GatewayClient } from "../services/gateway.js";
import {
  cloudApi, money, settling, type CloudAccount, type CloudBilling, type CloudOffer, type CloudPhone, type CloudProvider,
  type CloudSku, type CloudStatus,
} from "../services/cloud.js";

export interface CloudViewDeps {
  /** Schedules the next look; returns a cancel function. */
  later(fn: () => void, ms: number): () => void;
  /** A phone just connected or let go: the device list above should look again. */
  onChange?(): void;
  /** The clock, for "paid until" and "on for" lines. */
  now?(): number;
}

const defaultDeps: CloudViewDeps = {
  later: (fn, ms) => {
    const id = setTimeout(fn, ms);
    (id as unknown as { unref?: () => void }).unref?.();
    return () => clearTimeout(id);
  },
};

/** While a link is on its way, look often; otherwise now and then (renewals and drops show on their own). */
export const SETTLING_POLL_MS = 3_000;
export const IDLE_POLL_MS = 30_000;

const PROVIDERS: Array<{ id: CloudProvider; label: string }> = [
  { id: "vmos", label: "VMOS Cloud" },
  { id: "duoplus", label: "DuoPlus" },
  { id: "adb", label: "Remote ADB" },
];

const FIELDS: Record<CloudProvider, Array<{ key: string; label: string; secret: boolean; placeholder: string }>> = {
  vmos: [
    { key: "accessKey", label: "Access key", secret: false, placeholder: "From VMOS Cloud → API" },
    { key: "secretKey", label: "Secret key", secret: true, placeholder: "Kept encrypted on this PC" },
  ],
  duoplus: [{ key: "apiKey", label: "API key", secret: true, placeholder: "From DuoPlus → Automation → API" }],
  adb: [{ key: "address", label: "ADB address", secret: false, placeholder: "203.0.113.7:5555" }],
};

export interface CloudPhonesView {
  element: HTMLElement;
  refresh(): Promise<void>;
  destroy(): void;
}

export function createCloudPhonesView(client: GatewayClient, deps: CloudViewDeps = defaultDeps): CloudPhonesView {
  const element = el("section", "devices-section cloud");
  let status: CloudStatus | null = null;
  let adding = false;
  let provider: CloudProvider = "vmos";
  let formError: string | null = null;
  let busy = false;
  let destroyed = false;
  let cancel: (() => void) | null = null;
  const confirming = new Set<string>();
  const phoneErrors = new Map<string, string>();
  let connected = new Set<string>();
  const now = (): number => deps.now?.() ?? Date.now();

  // Renting: one open panel at a time, on one account.
  interface RentDraft {
    accountId: string;
    kind: CloudBilling;
    android: number;
    offers: CloudOffer[] | null;
    loading: boolean;
    sku: (CloudSku & { plan: string }) | null;
    count: number;
    autoRenew: boolean;
    confirming: boolean;
    error: string | null;
    done: string | null;
  }
  let rent: RentDraft | null = null;
  // Per phone: an open renew or restore panel, and what it is about to do.
  interface PhonePanel {
    kind: "renew" | "restore";
    offers: CloudOffer[] | null;
    sku: CloudSku | null;
    backupId: string | null;
  }
  const panels = new Map<string, PhonePanel>();

  const schedule = (): void => {
    cancel?.();
    if (destroyed) return;
    cancel = deps.later(() => void refresh(), status && settling(status) ? SETTLING_POLL_MS : IDLE_POLL_MS);
  };

  async function refresh(): Promise<void> {
    try {
      status = await cloudApi.status(client);
    } catch (error) {
      // An older runtime without cloud phones: show nothing.
      if (error instanceof GatewayError && error.status === 404) {
        status = null;
        element.hidden = true;
        return;
      }
    }
    const now = new Set(status?.accounts.flatMap((a) => a.phones.filter((p) => p.state === "connected").map((p) => `${a.id}/${p.remoteId}`)) ?? []);
    if ([...now].some((key) => !connected.has(key)) || [...connected].some((key) => !now.has(key))) deps.onChange?.();
    connected = now;
    render();
    schedule();
  }

  const act = async (fn: () => Promise<unknown>, key?: string): Promise<void> => {
    busy = true;
    if (key) phoneErrors.delete(key);
    render();
    try {
      await fn();
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (key) phoneErrors.set(key, message);
      else formError = message;
    }
    busy = false;
    await refresh();
  };

  function render(): void {
    if (destroyed) return;
    element.hidden = status === null;
    if (!status) return;
    const add = actionButton(adding ? "Cancel" : "Add cloud phones", { variant: "ghost", icon: adding ? undefined : "plug" });
    add.addEventListener("click", () => {
      adding = !adding;
      formError = null;
      render();
    });
    const head = el("div", "cloud-head");
    head.append(el("h2", "section-title", "Cloud phones"), add);
    const parts: HTMLElement[] = [head];
    if (adding) parts.push(form());
    else if (!status.accounts.length) {
      parts.push(el("p", "device-problem", "Connect VMOS Cloud or DuoPlus phones to this PC. Cyclone keeps each one connected, renews its access before it runs out and installs Cyclone on it."));
    }
    if (status.memoryOnly && status.accounts.length) {
      parts.push(el("p", "device-problem", "On this system the accounts are kept only until Cyclone restarts."));
    }
    if (!status.ssh && status.accounts.some((a) => a.provider === "vmos")) {
      parts.push(el("p", "cloud-warn", "VMOS phones need Windows' OpenSSH Client: Settings → System → Optional features → OpenSSH Client."));
    }
    const grid = el("div", "devices-grid");
    grid.append(...status.accounts.map(accountCard));
    if (status.accounts.length) parts.push(grid);
    setChildren(element, ...parts);
  }

  function form(): HTMLElement {
    const node = el("article", "card cloud-form");
    const picker = segmented(PROVIDERS, provider, (id) => {
      provider = id;
      formError = null;
      render();
    });
    const inputs = new Map<string, HTMLInputElement>();
    const fields = el("div", "cloud-fields");
    const name = field("Name", false, provider === "adb" ? "Phone name (optional)" : "Account name (optional)");
    for (const spec of FIELDS[provider]) {
      const f = field(spec.label, spec.secret, spec.placeholder);
      inputs.set(spec.key, f.input);
      fields.append(f.node);
    }
    fields.append(name.node);
    const install = el("input");
    install.type = "checkbox";
    install.checked = true;
    const installLabel = el("label", "cloud-check");
    installLabel.append(install, el("span", undefined, "Install Cyclone on each phone"));
    const save = actionButton(busy ? "Adding…" : "Add", { variant: "primary" });
    save.disabled = busy;
    save.addEventListener("click", () => void act(async () => {
      formError = null;
      const values = Object.fromEntries([...inputs].map(([k, input]) => [k, String(input.value ?? "").trim()]));
      if (provider === "adb") {
        // One Remote ADB account holds every address phone.
        const existing = status?.accounts.find((a) => a.provider === "adb")
          ?? await cloudApi.addAccount(client, { provider, label: "Remote ADB", installCyclone: install.checked });
        if (existing) await cloudApi.addAddress(client, existing.id, values.address, String(name.input.value ?? "").trim());
      } else {
        await cloudApi.addAccount(client, { provider, label: String(name.input.value ?? "").trim() || undefined, secrets: values,
                                            installCyclone: install.checked });
      }
      for (const input of inputs.values()) input.value = "";
      adding = false;
    }));
    node.append(picker.element, fields, installLabel);
    if (formError) node.append(el("p", "cloud-warn", formError));
    const actions = el("div", "device-actions");
    actions.append(save);
    node.append(actions, el("p", "device-problem", hint(provider)));
    return node;
  }

  function accountCard(account: CloudAccount): HTMLElement {
    const card = el("article", "card device-card cloud-account");
    card.dataset.accountId = account.id;
    const top = el("div", "device-card-top");
    const names = el("div", "device-names");
    const kept = account.phones.filter((p) => p.keep).length;
    names.append(el("span", "device-name", account.label),
      el("span", "muted", [account.providerLabel, `${account.phones.length} ${account.phones.length === 1 ? "phone" : "phones"}`, kept ? `${kept} kept connected` : ""].filter(Boolean).join(" · ")));
    top.append(names);
    card.append(top);
    if (account.error) card.append(el("p", "cloud-warn", account.error.message));
    if (account.pendingRentals) {
      card.append(el("p", "cloud-phone-message", `${account.pendingRentals} rented ${account.pendingRentals === 1 ? "phone is" : "phones are"} being made by VMOS. ${account.pendingRentals === 1 ? "It joins" : "They join"} here and get Cyclone by themselves.`));
    }
    if (rent?.accountId === account.id) card.append(rentPanel(account));
    const rows = el("ul", "cloud-phones");
    rows.append(...account.phones.map((phone) => phoneRow(account, phone)));
    if (account.phones.length) card.append(rows);
    else if (!account.error && account.provider !== "adb") card.append(el("p", "device-problem", "No phones in this account yet."));

    const actions = el("div", "device-actions");
    if (confirming.has(account.id)) {
      actions.append(el("span", "device-confirm", `Remove ${account.label}? Its phones are let go and its key is deleted from this PC.`));
      const yes = actionButton("Remove", { variant: "danger" });
      yes.addEventListener("click", () => void act(async () => {
        confirming.delete(account.id);
        await cloudApi.removeAccount(client, account.id);
      }));
      const no = actionButton("Keep", { variant: "ghost" });
      no.addEventListener("click", () => {
        confirming.delete(account.id);
        render();
      });
      actions.append(yes, no);
    } else {
      if (account.canRent && rent?.accountId !== account.id) {
        const open = actionButton("Rent phones", { variant: "primary" });
        open.disabled = busy;
        open.addEventListener("click", () => {
          rent = { accountId: account.id, kind: "rental", android: 13, offers: null, loading: false, sku: null, count: 1,
                   autoRenew: false, confirming: false, error: null, done: null };
          void loadOffers();
        });
        actions.append(open);
      }
      if (account.provider !== "adb") {
        const again = actionButton("Refresh", { variant: "ghost", icon: "refresh" });
        again.disabled = busy;
        again.addEventListener("click", () => void act(() => cloudApi.refresh(client, account.id)));
        actions.append(again);
      }
      const remove = actionButton("Remove", { variant: "ghost" });
      remove.addEventListener("click", () => {
        confirming.add(account.id);
        render();
      });
      actions.append(remove);
    }
    card.append(actions);
    return card;
  }

  function phoneRow(account: CloudAccount, phone: CloudPhone): HTMLElement {
    const key = `${account.id}/${phone.remoteId}`;
    const row = el("li", `cloud-phone cloud-${phone.state}`);
    row.dataset.remoteId = phone.remoteId;
    const text = el("div", "cloud-phone-text");
    const meta = [phone.android ? `Android ${phone.android.replace(/^android\s*/i, "")}` : "",
      phone.power === "stopped" && phone.billing !== "timing" ? "Off" : "", billingLine(phone, now())].filter(Boolean).join(" · ");
    text.append(el("span", "cloud-phone-name", phone.name));
    if (meta) text.append(el("span", "muted", meta));
    if (phone.keep) text.append(el("span", "cloud-phone-message", phone.message));
    const toggle = actionButton(phone.keep ? "Let go" : "Keep connected", { variant: phone.keep ? "ghost" : "primary" });
    toggle.disabled = busy;
    toggle.addEventListener("click", () => void act(() => cloudApi.keep(client, account.id, phone.remoteId, !phone.keep), key));
    const top = el("div", "cloud-phone-top");
    top.append(chip(stateLabel(phone), tone(phone)), text, toggle);
    row.append(top);
    if (account.provider === "duoplus" && phone.keep && !phone.address) row.append(addressInput(account, phone, key));
    if (account.canRent) row.append(...ownerActions(account, phone, key));
    const error = phoneErrors.get(key);
    if (error) row.append(el("p", "cloud-warn", error));
    return row;
  }

  async function loadOffers(): Promise<void> {
    if (!rent) return;
    const draft = rent;
    draft.loading = true;
    draft.offers = null;
    draft.sku = null;
    draft.error = null;
    render();
    try {
      draft.offers = await cloudApi.offers(client, draft.accountId, draft.android);
    } catch (error) {
      draft.error = error instanceof Error ? error.message : String(error);
    }
    draft.loading = false;
    render();
  }

  function rentPanel(account: CloudAccount): HTMLElement {
    const draft = rent!;
    const node = el("div", "cloud-rent");
    node.append(el("h3", "cloud-rent-title", "Rent VMOS phones"));
    if (draft.done) {
      node.append(el("p", "cloud-ok", draft.done));
      const close = actionButton("Done", { variant: "ghost" });
      close.addEventListener("click", () => { rent = null; render(); });
      node.append(close);
      return node;
    }
    if (draft.confirming && draft.sku) {
      const sku = draft.sku;
      const total = sku.priceCents * draft.count;
      const what = `${draft.count} × ${sku.plan}, Android ${draft.android}`;
      node.append(el("p", "cloud-confirm", sku.kind === "rental"
        ? `Rent ${what} for ${sku.label}${draft.autoRenew ? ", renewing automatically" : ""}? VMOS charges ${money(total)} to your VMOS balance.`
        : `Rent ${what} as pay-for-time? VMOS charges ${money(total)} to your VMOS balance (${money(sku.priceCents)} per ${sku.label} for each phone while it is powered on).`));
      if (draft.error) node.append(el("p", "cloud-warn", draft.error));
      const pay = actionButton(busy ? "Paying…" : `Confirm and pay ${money(total)}`, { variant: "primary" });
      pay.disabled = busy;
      pay.addEventListener("click", () => void (async () => {
        busy = true;
        draft.error = null;
        render();
        try {
          await cloudApi.rent(client, account.id, { kind: sku.kind, skuId: sku.skuId, android: draft.android, count: draft.count,
                                                    autoRenew: sku.kind === "rental" && draft.autoRenew, expectedPriceCents: total });
          draft.done = `Rented. ${draft.count === 1 ? "The new phone joins" : "The new phones join"} this list, ${draft.count === 1 ? "stays" : "stay"} connected and ${draft.count === 1 ? "gets" : "get"} Cyclone and the skills by ${draft.count === 1 ? "itself" : "themselves"}.`;
        } catch (error) {
          draft.error = error instanceof Error ? error.message : String(error);
          draft.confirming = false;
        }
        busy = false;
        await refresh();
      })());
      const back = actionButton("Back", { variant: "ghost" });
      back.addEventListener("click", () => { draft.confirming = false; render(); });
      const row = el("div", "device-actions");
      row.append(pay, back);
      node.append(row);
      return node;
    }
    const kinds = segmented([{ id: "rental" as CloudBilling, label: "By the period" }, { id: "timing" as CloudBilling, label: "Pay-for-time" }],
      draft.kind, (id) => { draft.kind = id; draft.sku = null; render(); });
    const versions = segmented([13, 14, 15].map((v) => ({ id: String(v), label: `Android ${v}` })), String(draft.android),
      (id) => { draft.android = Number(id); void loadOffers(); });
    node.append(kinds.element, versions.element);
    node.append(el("p", "device-problem", draft.kind === "rental"
      ? "A phone that is yours for a day, a week or a month, always on."
      : "A phone you pay for only while it is powered on. Power it off here when it isn't working; everything on it is kept."));
    if (draft.loading) node.append(el("p", "cloud-phone-message", "Asking VMOS what it offers…"));
    if (draft.error) node.append(el("p", "cloud-warn", draft.error));
    const choices = el("div", "cloud-offers");
    for (const offer of draft.offers ?? []) {
      for (const sku of draft.kind === "rental" ? offer.rentals : offer.timing) {
        const picked = draft.sku?.skuId === sku.skuId;
        const label = draft.kind === "rental" ? `${offer.name} · ${sku.label} · ${money(sku.priceCents)}`
          : `${offer.name} · ${money(sku.priceCents)} per ${sku.label}`;
        const choice = actionButton(label, { variant: picked ? "primary" : "secondary" });
        choice.classList.add("cloud-offer");
        choice.setAttribute("aria-pressed", picked ? "true" : "false");
        choice.addEventListener("click", () => { draft.sku = { ...sku, plan: offer.name }; render(); });
        choices.append(choice);
      }
    }
    if (draft.offers && !choices.childNodes.length) {
      node.append(el("p", "device-problem", `VMOS has nothing ${draft.kind === "rental" ? "to rent" : "pay-for-time"} for Android ${draft.android} right now.`));
    }
    node.append(choices);
    const count = segmented([1, 2, 3, 4, 5].map((n) => ({ id: String(n), label: String(n) })), String(draft.count),
      (id) => { draft.count = Number(id); render(); });
    const countRow = el("div", "cloud-rent-row");
    countRow.append(el("span", "cloud-field-label", "Phones"), count.element);
    node.append(countRow);
    if (draft.kind === "rental") {
      const renew = el("input");
      renew.type = "checkbox";
      renew.checked = draft.autoRenew;
      renew.addEventListener("change", () => { draft.autoRenew = renew.checked; });
      const renewLabel = el("label", "cloud-check");
      renewLabel.append(renew, el("span", undefined, "Renew automatically when the period ends"));
      node.append(renewLabel);
    }
    const next = actionButton(draft.sku ? `Review ${money(draft.sku.priceCents * draft.count)}` : "Pick an option", { variant: "primary" });
    next.disabled = !draft.sku || busy;
    next.addEventListener("click", () => { draft.confirming = true; draft.error = null; render(); });
    const cancelRent = actionButton("Cancel", { variant: "ghost" });
    cancelRent.addEventListener("click", () => { rent = null; render(); });
    const row = el("div", "device-actions");
    row.append(next, cancelRent);
    node.append(row);
    return node;
  }

  function ownerActions(account: CloudAccount, phone: CloudPhone, key: string): HTMLElement[] {
    const out: HTMLElement[] = [];
    const bar = el("div", "cloud-phone-actions");
    if (phone.billing === "timing") {
      const power = actionButton(phone.poweredOff ? "Power on" : "Power off", { variant: phone.poweredOff ? "primary" : "ghost" });
      power.disabled = busy;
      power.addEventListener("click", () => void act(() => cloudApi.power(client, account.id, phone.remoteId, phone.poweredOff), key));
      bar.append(power);
    }
    if (phone.billing === "rental") {
      const renew = actionButton("Renew", { variant: "ghost" });
      renew.disabled = busy;
      renew.addEventListener("click", () => void openRenew(account, phone, key));
      const auto = actionButton(phone.autoRenew ? "Auto-renew: on" : "Auto-renew: off", { variant: "ghost" });
      auto.disabled = busy;
      auto.addEventListener("click", () => void act(() => cloudApi.autoRenew(client, account.id, phone.remoteId, !phone.autoRenew), key));
      bar.append(renew, auto);
    }
    if (phone.billing === null) {
      const mark = actionButton("Pay-for-time phone?", { variant: "ghost" });
      mark.title = "Mark this phone as pay-for-time, so it can be powered on and off here";
      mark.disabled = busy;
      mark.addEventListener("click", () => void act(() => cloudApi.setBilling(client, account.id, phone.remoteId, "timing"), key));
      bar.append(mark);
    }
    const backup = actionButton(phone.backup ? (phone.backup.stage === "sizing" ? "Measuring backup…" : "Backing up…") : "Back up",
      { variant: "ghost" });
    backup.disabled = busy || phone.backup !== null || account.phones.some((p) => p.backup !== null);
    backup.addEventListener("click", () => void act(() => cloudApi.backup(client, account.id, phone.remoteId), key));
    bar.append(backup);
    if (phone.backups.length) {
      const restore = actionButton("Restore…", { variant: "ghost" });
      restore.disabled = busy;
      restore.addEventListener("click", () => {
        panels.set(key, { kind: "restore", offers: null, sku: null, backupId: null });
        render();
      });
      bar.append(restore);
    }
    out.push(bar);
    if (phone.lastBackup && !phone.backup) {
      out.push(el("p", phone.lastBackup.ok ? "cloud-ok" : "cloud-warn",
        phone.lastBackup.ok ? `Backed up${phone.lastBackup.atMs ? ` ${when(phone.lastBackup.atMs)}` : ""}.` : `The backup didn't finish: ${phone.lastBackup.message ?? "VMOS gave no reason."}`));
    }
    const panel = panels.get(key);
    if (panel?.kind === "renew") out.push(renewPanel(account, phone, key, panel));
    if (panel?.kind === "restore") out.push(restorePanel(account, phone, key, panel));
    return out;
  }

  async function openRenew(account: CloudAccount, phone: CloudPhone, key: string): Promise<void> {
    const panel: PhonePanel = { kind: "renew", offers: null, sku: null, backupId: null };
    panels.set(key, panel);
    phoneErrors.delete(key);
    render();
    try {
      const android = Number((phone.android ?? "13").replace(/\D+/g, "")) || 13;
      panel.offers = await cloudApi.offers(client, account.id, [13, 14, 15].includes(android) ? android : 13);
    } catch (error) {
      phoneErrors.set(key, error instanceof Error ? error.message : String(error));
      panels.delete(key);
    }
    render();
  }

  function renewPanel(account: CloudAccount, phone: CloudPhone, key: string, panel: PhonePanel): HTMLElement {
    const node = el("div", "cloud-rent");
    const close = (): void => { panels.delete(key); render(); };
    if (!panel.offers) {
      node.append(el("p", "cloud-phone-message", "Asking VMOS for its periods…"));
      return node;
    }
    if (panel.sku) {
      const sku = panel.sku;
      node.append(el("p", "cloud-confirm", `Renew ${phone.name} for ${sku.label}? VMOS charges ${money(sku.priceCents)} to your VMOS balance.`));
      const pay = actionButton(`Confirm and pay ${money(sku.priceCents)}`, { variant: "primary" });
      pay.disabled = busy;
      pay.addEventListener("click", () => void act(async () => {
        panels.delete(key);
        await cloudApi.renew(client, account.id, phone.remoteId, sku.skuId, sku.priceCents);
      }, key));
      const back = actionButton("Back", { variant: "ghost" });
      back.addEventListener("click", () => { panel.sku = null; render(); });
      const row = el("div", "device-actions");
      row.append(pay, back);
      node.append(row);
      return node;
    }
    const choices = el("div", "cloud-offers");
    for (const offer of panel.offers) {
      for (const sku of offer.rentals) {
        const choice = actionButton(`${offer.name} · ${sku.label} · ${money(sku.priceCents)}`, { variant: "secondary" });
        choice.classList.add("cloud-offer");
        choice.addEventListener("click", () => { panel.sku = sku; render(); });
        choices.append(choice);
      }
    }
    node.append(el("p", "cloud-field-label", "Renew for"), choices);
    const cancelRenew = actionButton("Cancel", { variant: "ghost" });
    cancelRenew.addEventListener("click", close);
    node.append(cancelRenew);
    return node;
  }

  function restorePanel(account: CloudAccount, phone: CloudPhone, key: string, panel: PhonePanel): HTMLElement {
    const node = el("div", "cloud-rent");
    const close = (): void => { panels.delete(key); render(); };
    const picked = phone.backups.find((b) => b.backupId === panel.backupId);
    if (picked) {
      node.append(el("p", "cloud-confirm", `Restore "${picked.name ?? picked.backupId}"${picked.atMs ? ` from ${when(picked.atMs)}` : ""} onto ${phone.name}? It replaces everything on the phone now.`));
      const yes = actionButton("Restore", { variant: "danger" });
      yes.disabled = busy;
      yes.addEventListener("click", () => void act(async () => {
        panels.delete(key);
        await cloudApi.restore(client, account.id, phone.remoteId, picked.backupId);
      }, key));
      const back = actionButton("Back", { variant: "ghost" });
      back.addEventListener("click", () => { panel.backupId = null; render(); });
      const row = el("div", "device-actions");
      row.append(yes, back);
      node.append(row);
      return node;
    }
    const list = el("div", "cloud-offers");
    for (const backup of phone.backups) {
      const label = [backup.name ?? backup.backupId, backup.atMs ? when(backup.atMs) : "", backup.sizeBytes ? size(backup.sizeBytes) : ""].filter(Boolean).join(" · ");
      const choice = actionButton(label, { variant: "secondary" });
      choice.classList.add("cloud-offer");
      choice.addEventListener("click", () => { panel.backupId = backup.backupId; render(); });
      list.append(choice);
    }
    node.append(el("p", "cloud-field-label", "Restore from"), list);
    const cancelRestore = actionButton("Cancel", { variant: "ghost" });
    cancelRestore.addEventListener("click", close);
    node.append(cancelRestore);
    return node;
  }

  function addressInput(account: CloudAccount, phone: CloudPhone, key: string): HTMLElement {
    const wrap = el("div", "cloud-address");
    const input = el("input", "cloud-input");
    input.placeholder = "ADB address from DuoPlus, like 203.0.113.7:5555";
    input.setAttribute("aria-label", `ADB address for ${phone.name}`);
    const save = actionButton("Save", { variant: "secondary" });
    save.addEventListener("click", () => void act(() => cloudApi.setAddress(client, account.id, phone.remoteId, String(input.value ?? "")), key));
    wrap.append(input, save);
    return wrap;
  }

  void refresh();
  return {
    element,
    refresh,
    destroy() {
      destroyed = true;
      cancel?.();
    },
  };
}

function field(label: string, secret: boolean, placeholder: string): { node: HTMLElement; input: HTMLInputElement } {
  const node = el("label", "cloud-field");
  const input = el("input", "cloud-input");
  input.type = secret ? "password" : "text";
  input.autocomplete = "off";
  input.spellcheck = false;
  input.placeholder = placeholder;
  node.append(el("span", "cloud-field-label", label), input);
  return { node, input };
}

function hint(provider: CloudProvider): string {
  if (provider === "vmos") return "Cyclone asks VMOS for 7-day ADB access to the phones you keep and renews it before it runs out. The key is encrypted for your Windows user and never shown again.";
  if (provider === "duoplus") return "In DuoPlus, turn on ADB for the phones and add this PC's internet address to the ADB whitelist. Then paste each phone's ADB address here once.";
  return "Any phone reachable at an ADB address: Cyclone keeps adb connected to it and reconnects when it drops.";
}

/** "Rented · paid until 15 Oct", "Pay-for-time · on for 42 min", "Pay-for-time · powered off". */
export function billingLine(phone: CloudPhone, nowMs: number): string {
  if (phone.billing === "timing") {
    if (phone.poweredOff) return "Pay-for-time · powered off";
    return phone.poweredOnAtMs ? `Pay-for-time · on for ${duration(nowMs - phone.poweredOnAtMs)}` : "Pay-for-time";
  }
  if (phone.billing === "rental" || phone.paidUntilMs) {
    if (!phone.paidUntilMs) return "Rented";
    const left = phone.paidUntilMs - nowMs;
    if (left <= 0) return "Rental ended";
    const date = new Date(phone.paidUntilMs).toLocaleDateString(undefined, { day: "numeric", month: "short" });
    return left < 2 * 86_400_000 ? `Rental ends in ${duration(left)}${phone.autoRenew ? " (renews)" : ""}` : `Paid until ${date}`;
  }
  return "";
}

function duration(ms: number): string {
  const minutes = Math.max(0, Math.round(ms / 60_000));
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.round(minutes / 60);
  return hours < 48 ? `${hours} h` : `${Math.round(hours / 24)} days`;
}

function when(ms: number): string {
  return new Date(ms).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

function size(bytes: number): string {
  return bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(1)} GB` : `${Math.max(1, Math.round(bytes / 1024 ** 2))} MB`;
}

export function stateLabel(phone: CloudPhone): string {
  if (!phone.keep) return "Not kept";
  switch (phone.state) {
    case "connected": return "Connected";
    case "needs_you": return "Needs you";
    case "off": return "Off";
    case "waiting": return "Retrying";
    default: return "Connecting";
  }
}

function tone(phone: CloudPhone): Tone {
  if (!phone.keep) return "neutral";
  if (phone.state === "connected") return "success";
  if (phone.state === "needs_you") return "danger";
  if (phone.state === "waiting" || phone.state === "off") return "warning";
  return "accent";
}
