/** Hash routes. Hash routing keeps the gateway's static mount trivial (one index.html under /glass/). */
export type AppTab = "map" | "coverage" | "skills" | "screens" | "scenarios" | "versions" | "runs" | "issues";
const APP_TABS: AppTab[] = ["map", "coverage", "skills", "screens", "scenarios", "versions", "runs", "issues"];

export type Route =
  | { name: "home" }
  | { name: "apps" }
  | { name: "app"; placeId: string; tab: AppTab; route?: string[]; runId?: string; skill?: string }
  | { name: "runs" }
  | { name: "run"; runId: string }
  | { name: "phone" }
  | { name: "devices" }
  | { name: "knowledge" }
  | { name: "lab"; experimentId?: string }
  | { name: "market" }
  | { name: "settings" };

export const DEFAULT_ROUTE: Route = { name: "home" };

/** Mapper rooms (`screen:purpose:digest`) and learned screens of the Taught map (`page:<id>`). */
const ROOM_ID = /^(?:screen:[a-z_]{1,40}:[0-9a-f]{8,64}|page:[A-Za-z0-9_-]{1,160})$/;
const SKILL_ID = /^you\.[a-f0-9]{12}$/;

export function parseRoute(hash: string): Route {
  const raw = hash.replace(/^#/, "");
  const path = raw.split(/[?&]/, 1)[0] ?? "";
  const query = new URLSearchParams(raw.includes("?") ? raw.slice(raw.indexOf("?") + 1) : "");
  const parts = path.split("/").filter(Boolean);
  if (parts[0] === "apps" && parts.length >= 2) {
    const placeId = safeDecode(parts[1] ?? "");
    if (!placeId) return { name: "apps" };
    const route = (query.get("route") ?? "").split(",").filter((id) => ROOM_ID.test(id)).slice(0, 60);
    const runId = query.get("run") ?? "";
    const skill = query.get("skill") ?? "";
    const tab = APP_TABS.includes(parts[2] as AppTab) ? (parts[2] as AppTab) : "map";
    return {
      name: "app",
      placeId,
      tab,
      ...(route.length ? { route } : {}),
      ...(/^[A-Za-z0-9_-]{4,120}$/.test(runId) ? { runId } : {}),
      ...(SKILL_ID.test(skill) ? { skill } : {}),
    };
  }
  if (parts[0] === "runs" && parts.length >= 2) {
    const runId = safeDecode(parts[1] ?? "");
    return /^[A-Za-z0-9_-]{4,120}$/.test(runId) ? { name: "run", runId } : { name: "runs" };
  }
  if (parts[0] === "lab") {
    const id = safeDecode(parts[1] ?? "");
    return /^exp-[0-9]{8}-[0-9]{6}-[a-z0-9]{4}$/.test(id) ? { name: "lab", experimentId: id } : { name: "lab" };
  }
  if (parts[0] === "market") return { name: "market" };
  if (parts[0] === "apps") return { name: "apps" };
  if (parts[0] === "runs") return { name: "runs" };
  if (parts[0] === "phone") return { name: "phone" };
  if (parts[0] === "settings") return { name: "settings" };
  if (parts[0] === "devices") return { name: "devices" };
  if (parts[0] === "knowledge") return { name: "knowledge" };
  return DEFAULT_ROUTE;
}

export function routeHref(route: Route): string {
  switch (route.name) {
    case "home":
      return "#/home";
    case "apps":
      return "#/apps";
    case "app": {
      const base = `#/apps/${encodeURIComponent(route.placeId)}/${route.tab}`;
      const query = new URLSearchParams();
      if (route.route?.length) query.set("route", route.route.join(","));
      if (route.runId) query.set("run", route.runId);
      if (route.skill) query.set("skill", route.skill);
      const text = query.toString();
      return text ? `${base}?${text}` : base;
    }
    case "runs":
      return "#/runs";
    case "run":
      return `#/runs/${encodeURIComponent(route.runId)}`;
    case "phone":
      return "#/phone";
    case "devices":
      return "#/devices";
    case "knowledge":
      return "#/knowledge";
    case "lab":
      return route.experimentId ? `#/lab/${encodeURIComponent(route.experimentId)}` : "#/lab";
    case "market":
      return "#/market";
    case "settings":
      return "#/settings";
  }
}

/** Sidebar section that owns a route. */
export function sectionOf(route: Route): "home" | "apps" | "runs" | "phone" | "devices" | "knowledge" | "lab" | "market" | "settings" {
  if (route.name === "app") return "apps";
  if (route.name === "run") return "runs";
  return route.name;
}

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return "";
  }
}
