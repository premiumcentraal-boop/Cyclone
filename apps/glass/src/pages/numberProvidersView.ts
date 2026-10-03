/** Owner connects accounts and reviews prices. Glass never holds a provider inbox or answers its own approval. */
import type { GlassContext } from "../app.js";
import { actionButton, card, chip } from "../ui/components.js";
import { el, link, setChildren } from "../ui/dom.js";
import { numberProvidersApi as api, rentalPrice, type Catalogue, type NumberOrder, type ProviderConnection, type ProviderKind } from "../services/numberProviders.js";
import type { NumberAccount } from "../services/numbers.js";

const field = (label: string, control: HTMLElement): HTMLElement => {
  const wrap = el("label", "nm-field"); wrap.append(el("span", "nm-field-label", label), control); return wrap;
};
const input = (label: string, secret = false): HTMLInputElement => {
  const n = el("input", "cc-input"); n.setAttribute("aria-label", label); n.type = secret ? "password" : "text";
  if (secret) n.autocomplete = "new-password";
  return n;
};
const select = (label: string): HTMLSelectElement => {
  const n = el("select", "cc-input"); n.setAttribute("aria-label", label); return n;
};
function options(n: HTMLSelectElement, rows: Array<[string, string]>): void {
  setChildren(n, ...rows.map(([value, label]) => { const o = el("option", undefined, label); o.value = value; return o; }));
  n.value = rows[0]?.[0] ?? "";
}

export function createNumberProvidersView(ctx: GlassContext, say: (text: string, tone?: "ok" | "error") => void) {
  const element = card("cc-card nm-providers");
  const connections = el("div", "nm-grid");
  const rental = card("cc-card");
  const orders = el("div", "nm-provider-orders");
  const hint = el("p", "nm-note", "Connect your own account. Every new number needs your approval of its exact price. Temporary numbers can expire and may be refused by a website; keep a separate recovery method.");
  const reload = actionButton("Check providers", { variant: "ghost" });
  element.append(el("h3", "nm-h", "Number providers"), hint, reload, connections, rental, orders);
  let destroyed = false, ready = false, busy = false;
  let providers: ProviderConnection[] = [], accounts: NumberAccount[] = [];
  let catalogue: Catalogue | null = null;
  let quote: NumberOrder | null = null;
  const kind = select("Number provider"), country = select("Number country"), plan = select("Number plan or template"), period = select("Rental period"), area = select("US area code"), account = select("Assign rental to account");
  options(kind, [["vmos", "VMOS Cloud"], ["smsbot", "SMSBot.cc"]]);
  options(area, [["213", "213"], ["480", "480"], ["702", "702"], ["813", "813"]]);
  const load = actionButton("Load countries and plans");
  const price = actionButton("Get exact price");
  const quoteBox = el("div", "nm-provider-quote");
  price.disabled = true;
  const grid = el("div", "nm-grid");
  grid.append(field("Provider", kind), field("Country", country), field("Plan or service template", plan), field("Period (SMSBot)", period), field("US area code (VMOS)", area), field("Account", account));
  rental.append(el("h3", "nm-h", "Choose a new temporary number"), el("p", "nm-note", "Prices and stock come from your account. SMSBot uses service templates so the purchase has a price guard; all included services appear in the quote."), grid, load, price, quoteBox);

  async function act(fn: () => Promise<void>): Promise<void> {
    if (busy || destroyed) return;
    busy = true; load.disabled = price.disabled = true;
    try { await fn(); } catch (e) { say((e as Error).message || "Provider request failed.", "error"); }
    finally { busy = false; load.disabled = false; price.disabled = !catalogue || !plan.value; }
  }
  function reset(): void { catalogue = null; quote = null; price.disabled = true; options(country, []); options(plan, []); options(period, []); setChildren(quoteBox); }
  kind.addEventListener("change", reset);
  area.addEventListener("change", reset);
  function plans(): void {
    if (!catalogue) return;
    quote = null; setChildren(quoteBox);
    if (kind.value === "vmos") {
      const c = catalogue.countries.find((c) => c.code === country.value);
      options(plan, (c?.plans ?? []).map((p) => [String(p.id), `${p.days} days · ${rentalPrice(p)}`]));
      options(period, []); period.disabled = true;
    } else {
      options(plan, catalogue.templates.filter((t) => t.country === country.value).map((t) => [t.id, t.name]));
      periods(); period.disabled = false;
    }
    price.disabled = !plan.value;
  }
  function periods(): void {
    options(period, (catalogue?.templates.find((t) => t.id === plan.value)?.periods ?? []).map((p) => [p, `${p} days`]));
    quote = null; setChildren(quoteBox);
  }
  country.addEventListener("change", plans);
  plan.addEventListener("change", () => { if (kind.value === "smsbot") periods(); quote = null; setChildren(quoteBox); });
  period.addEventListener("change", () => { quote = null; setChildren(quoteBox); });
  account.addEventListener("change", () => { quote = null; setChildren(quoteBox); });
  load.addEventListener("click", () => void act(async () => {
    catalogue = await api.catalogue(ctx.client, kind.value as ProviderKind, area.value);
    if (destroyed) return;
    options(country, catalogue.countries.filter((c) => c.available).map((c) => [c.code, `${c.name} (${c.code})`]));
    plans();
    if (!country.value || !plan.value) say("No available plans for this selection. Try another country or check the provider dashboard.", "error");
  }));
  price.addEventListener("click", () => void act(async () => {
    const body: Record<string, unknown> = { requestId: "owner_" + crypto.randomUUID(), country: country.value, accountId: account.value || null };
    if (kind.value === "vmos") { body.planId = Number(plan.value); body.areaCode = area.value; }
    else { body.templateId = plan.value; body.period = period.value; }
    quote = await api.quote(ctx.client, kind.value as ProviderKind, body);
    showQuote(); await refresh();
  }));
  function showQuote(): void {
    if (!quote || destroyed) return;
    const current = quote, q = current.quote;
    const request = actionButton("Request owner approval", { variant: "primary" });
    request.disabled = current.status !== "quoted";
    request.addEventListener("click", () => void act(async () => { quote = await api.requestApproval(ctx.client, current.id); showQuote(); await refresh(); say("Review and approve the rental in Needs you. Nothing is bought before you approve.", "ok"); }));
    setChildren(quoteBox, el("h4", "nm-h", `${q.country} · ${q.days} days · ${rentalPrice(q)}`),
      el("p", "nm-note", `One number · ${q.services.join(", ")} · Auto-renew off`), el("p", "nm-note", q.terms),
      el("p", "nm-note", `Quote expires ${new Date(current.expiresAt).toLocaleTimeString()}. Status: ${current.status.replaceAll("_", " ")}.`), request,
      link("Open Needs you to approve", "#/command/approvals", "btn btn-ghost"));
  }
  function connection(p: ProviderConnection): HTMLElement {
    const box = card("cc-card");
    const keys = p.id === "vmos" ? ["accessKey", "secretKey"] : ["apiKey"];
    const names: Record<string, string> = Object.fromEntries([["accessKey", "Access key"], ["secretKey", "Secret key"], ["apiKey", "API key"]]);
    const fields = keys.map((key) => ({ key, node: input(`${p.title} ${names[key]}`, true) }));
    const enabled = input(`${p.title} allow agents`); enabled.type = "checkbox"; enabled.checked = p.agentEnabled;
    const apps = input(`${p.title} allowed apps`); apps.value = p.apps.join(", ");
    const routines = input(`${p.title} allowed routines`); routines.value = p.routines.join(", ");
    const save = actionButton(p.connected ? `Save ${p.title} connection` : `Connect ${p.title}`, { variant: "primary" });
    box.append(el("h4", "nm-h", p.title), chip(p.status.replaceAll("_", " "), p.status === "active" ? "success" : "neutral"),
      el("p", "nm-note", p.connected ? "Keys are stored privately. Leave blank to keep them." : "Enter your own provider keys. Cyclone uses your existing balance; it never tops it up."),
      ...fields.map((f) => field(names[f.key], f.node)), field("Allow agents to request quotes and use approved numbers", enabled),
      field("Allowed Android app packages (comma separated; blank = any)", apps), field("Allowed routine IDs (comma separated; blank = any)", routines), save);
    save.addEventListener("click", () => void act(async () => {
      save.disabled = true;
      const body: Record<string, unknown> = { agentEnabled: enabled.checked, apps: apps.value.split(",").map((s) => s.trim()).filter(Boolean), routines: routines.value.split(",").map((s) => s.trim()).filter(Boolean) };
      for (const f of fields) { if (f.node.value) body[f.key] = f.node.value; f.node.value = ""; }
      try {
        const result = await api.configure(ctx.client, p.id, body); providers = result.providers;
        if (!destroyed) setChildren(connections, ...providers.map(connection));
        say("Connection saved. Purchases still need your approval.", "ok"); await refresh();
      } finally { save.disabled = false; for (const f of fields) f.node.value = ""; }
    }));
    return box;
  }
  function orderRow(o: NumberOrder): HTMLElement {
    const box = card("cc-card");
    box.append(el("h4", "nm-h", `${o.provider === "vmos" ? "VMOS" : "SMSBot"} · ${o.quote.country} · ${rentalPrice(o.quote)}`),
      chip(o.status.replaceAll("_", " "), o.status === "complete" ? "success" : "neutral"),
      el("p", "nm-note", o.number ? `Allocated: ${o.number}. Manage its account and expiry in Numbers below.` : "This request has not yet supplied a confirmed number."));
    if (o.error) box.append(el("p", "nm-note", o.error.replaceAll("_", " ")));
    if (["unknown", "processing", "syncing"].includes(o.status)) box.append(el("p", "nm-note", "Do not request another number for this job: the provider may already have charged you."));
    if (o.status === "waiting_owner") box.append(link("Review in Needs you", "#/command/approvals", "btn btn-ghost"));
    if (o.status === "unknown" && o.provider === "smsbot") {
      const id = input("Actual SMSBot rental ID");
      const reconcile = actionButton("Match an existing rental");
      reconcile.addEventListener("click", () => void act(async () => { await api.reconcile(ctx.client, o.id, id.value.trim()); await refresh(); }));
      box.append(el("p", "nm-note", "Open SMSBot and identify the rental bought for this request. Matching it is read-only; it cannot buy again."),
        link("Open SMSBot dashboard", "https://cabinet.smsbot.cc/", "btn btn-ghost"), field("Actual rental ID", id), reconcile);
    }
    return box;
  }
  async function refresh(): Promise<void> {
    try {
      const result = await api.overview(ctx.client);
      if (destroyed) return;
      providers = result.providers;
      if (!ready) { setChildren(connections, ...providers.map(connection)); ready = true; }
      if (quote) {
        const current = result.orders.find((o) => o.id === quote?.id);
        if (current) { quote = current; showQuote(); }
      }
      hint.textContent = result.keyStoreError ? "The encrypted key store cannot be read. It has been preserved; reconnect under the Windows user who created it." :
        `Your provider keys stay private (${result.keyStorage === "MEMORY_ONLY" ? "memory only on this platform" : "encrypted for this Windows user"}). Every purchase needs exact-price approval. Temporary numbers may be refused by a website; keep a separate recovery method.`;
      setChildren(orders, ...result.orders.map(orderRow));
    } catch (e) { if (!destroyed && !ready) hint.textContent = (e as Error).message || "Number providers unavailable in this runtime."; }
  }
  reload.addEventListener("click", () => void refresh());
  void refresh();
  return { element, refresh,
    setAccounts(rows: NumberAccount[]): void { const selected = account.value; accounts = rows; options(account, [["", "Assign later"], ...accounts.map((a): [string, string] => [a.id, `${a.service} · ${a.handle}`])]); if (accounts.some((a) => a.id === selected)) account.value = selected; },
    destroy(): void { destroyed = true; for (const n of connections.querySelectorAll("input")) if (n.type === "password") n.value = ""; },
  };
}
