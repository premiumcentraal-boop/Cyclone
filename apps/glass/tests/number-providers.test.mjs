import test from "node:test";
import assert from "node:assert/strict";
import { installMiniDom } from "./helpers/mini-dom.mjs";
import { fakeGateway as gateway, flush } from "./helpers/fakeGateway.mjs";
import { GatewayClient } from "../.test-dist/services/gateway.js";
import { createNumberProvidersView } from "../.test-dist/pages/numberProvidersView.js";
installMiniDom();
const fakeGateway = (routes) => gateway(Object.fromEntries(Object.entries(routes).map(([path, value]) => [path, () => value])));
const button = (r, t) => r.querySelectorAll("button").find((b) => b.textContent === t);
const overview = { keyStorage: "WINDOWS_DPAPI_CURRENT_USER", keyStoreError: false, orders: [], providers: [
  { id: "vmos", title: "VMOS Cloud", connected: false, status: "not_connected", agentEnabled: false, apps: [], routines: [], currency: "USD" },
  { id: "smsbot", title: "SMSBot.cc", connected: false, status: "not_connected", agentEnabled: false, apps: [], routines: [], currency: "EUR" },
] };
const context = (gw) => ({ client: new GatewayClient({ token: "test", fetch: gw.fetch }) });

test("both connections have private key inputs and never enable agent use implicitly", async () => {
  const gw = fakeGateway({ "GET /v1/number-providers": overview });
  const view = createNumberProvidersView(context(gw), () => {}); await flush();
  assert.ok(view.element.textContent.includes("VMOS Cloud")); assert.ok(view.element.textContent.includes("SMSBot.cc"));
  assert.equal(view.element.querySelectorAll("input").filter((n) => n.type === "password").length, 3);
  assert.ok(view.element.querySelectorAll("input").filter((n) => n.type === "checkbox").every((n) => !n.checked));
  view.destroy();
});

test("connect clears credentials, persists developer scopes and does not request a number", async () => {
  const gw = fakeGateway({ "GET /v1/number-providers": overview, "POST /v1/number-providers/vmos/config": overview });
  const view = createNumberProvidersView(context(gw), () => {}); await flush();
  const fields = view.element.querySelectorAll("input");
  fields.find((n) => n.getAttribute("aria-label") === "VMOS Cloud Access key").value = "private-access";
  fields.find((n) => n.getAttribute("aria-label") === "VMOS Cloud Secret key").value = "private-secret";
  fields.find((n) => n.getAttribute("aria-label") === "VMOS Cloud allowed apps").value = "com.example.shop";
  button(view.element, "Connect VMOS Cloud").click(); await flush();
  const call = gw.calls.find((c) => c.path.endsWith("/config"));
  assert.equal(call.body.accessKey, "private-access"); assert.deepEqual(call.body.apps, ["com.example.shop"]);
  assert.ok(fields.filter((n) => n.type === "password").every((n) => n.value === ""));
  assert.equal(gw.calls.filter((c) => c.method === "POST").length, 1);
  view.destroy();
});

test("quote shows term, services and exact amount; purchase stays in owner approval", async () => {
  const quoted = { id: "nr_test", provider: "vmos", requestId: "owner_test", status: "quoted", expiresAt: Date.now() + 180000,
    quote: { country: "GB", days: 30, services: ["SMS reception"], amountCents: 998, currency: "USD", quantity: 1, autoRenew: false, terms: "No refund on release." } };
  const gw = fakeGateway({ "GET /v1/number-providers": overview,
    "GET /v1/number-providers/vmos/catalogue": { countries: [{ code: "GB", name: "United Kingdom", available: true, plans: [{ id: 2, days: 30, amountCents: 998, currency: "USD" }] }], templates: [] },
    "POST /v1/number-providers/vmos/quotes": quoted,
    "POST /v1/number-orders/nr_test/request-approval": { ...quoted, status: "waiting_owner", approvalId: "apv_1" },
  });
  const view = createNumberProvidersView(context(gw), () => {}); await flush();
  button(view.element, "Load countries and plans").click(); await flush();
  button(view.element, "Get exact price").click(); await flush();
  assert.ok(view.element.textContent.includes("9.98 USD")); assert.ok(view.element.textContent.includes("SMS reception"));
  assert.ok(view.element.textContent.includes("Auto-renew off"));
  button(view.element, "Request owner approval").click(); await flush();
  assert.ok(gw.calls.some((c) => c.path.endsWith("/request-approval")));
  assert.ok(gw.calls.every((c) => !c.path.includes("/answer") && !c.path.includes("/purchase")));
  view.destroy();
});
