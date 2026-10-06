/**
 * The project pulse (plan 52 §5.6): one square per day for the last 26 or 52 weeks, coloured by that day's testbench
 * pass rate; a release that day is outlined and a safety failure gets a red dot. A day with no runs stays empty, so
 * "no data" is never drawn as "bad".
 *
 * Origin: original Cyclone code; the contribution-style grid is after Space UI's GitHub Activity (MIT,
 * https://github.com/adrielzimbril/space-ui); no Space UI source is copied.
 */
import { el } from "../dom.js";

export interface PulseDay {
  /** YYYY-MM-DD, local. */
  date: string;
  /** Pass rate 0..1 that day; null when nothing ran. */
  rate: number | null;
  runs?: number;
  release?: string | null;
  safety?: number;
}

export interface HeatgridOptions {
  weeks?: 26 | 52;
  /** The last day shown (YYYY-MM-DD); defaults to the newest day given. */
  until?: string;
  onSelect?(day: PulseDay): void;
}

/** Colour level 0..4 for a pass rate; -1 for a day with no runs. */
export function heatLevel(rate: number | null | undefined): number {
  if (rate == null || !Number.isFinite(rate)) return -1;
  if (rate >= 0.95) return 4;
  if (rate >= 0.85) return 3;
  if (rate >= 0.7) return 2;
  if (rate >= 0.5) return 1;
  return 0;
}

const pad = (n: number) => String(n).padStart(2, "0");
export const isoDay = (d: Date): string => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

function parseDay(value: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 12);
  return Number.isNaN(d.getTime()) ? null : d;
}

/** Columns of 7 days (Monday first) ending with the week of `until`; days after `until` are left out. */
export function gridDays(weeks: number, until: string): Array<Array<string | null>> {
  const end = parseDay(until) ?? new Date();
  const weekday = (end.getDay() + 6) % 7; // Monday 0
  const start = new Date(end);
  start.setDate(end.getDate() - weekday - (weeks - 1) * 7);
  const columns: Array<Array<string | null>> = [];
  const cursor = new Date(start);
  for (let w = 0; w < weeks; w += 1) {
    const column: Array<string | null> = [];
    for (let d = 0; d < 7; d += 1) {
      column.push(cursor <= end ? isoDay(cursor) : null);
      cursor.setDate(cursor.getDate() + 1);
    }
    columns.push(column);
  }
  return columns;
}

export function dayTitle(day: PulseDay): string {
  const parts = [day.date];
  parts.push(day.rate == null ? "no runs" : `${Math.round(day.rate * 100)}% passed${day.runs ? ` of ${day.runs} runs` : ""}`);
  if (day.release) parts.push(`release ${day.release}`);
  if (day.safety) parts.push(`${day.safety} safety failure${day.safety === 1 ? "" : "s"}`);
  return parts.join(" · ");
}

export function renderHeatgrid(days: PulseDay[], options: HeatgridOptions = {}): HTMLElement {
  const weeks = options.weeks ?? 26;
  const byDate = new Map(days.map((d) => [d.date, d]));
  const until = options.until ?? days.map((d) => d.date).sort().at(-1) ?? isoDay(new Date());
  const box = el("div", "cyber-heat");
  const grid = el("div", "cyber-heat-grid");
  grid.setAttribute("role", "grid");
  grid.setAttribute("aria-label", `Testbench pass rate per day, last ${weeks} weeks`);
  grid.style.setProperty("--weeks", String(weeks));
  for (const column of gridDays(weeks, until)) {
    const col = el("div", "cyber-heat-col");
    col.setAttribute("role", "row");
    for (const date of column) {
      if (!date) {
        col.append(el("span", "cyber-heat-cell cyber-heat-none"));
        continue;
      }
      const day = byDate.get(date) ?? { date, rate: null };
      const level = heatLevel(day.rate);
      const cell = el("button", `cyber-heat-cell cyber-heat-l${level < 0 ? "x" : level}`);
      cell.type = "button";
      cell.setAttribute("role", "gridcell");
      cell.title = dayTitle(day);
      cell.setAttribute("aria-label", cell.title);
      if (day.release) cell.classList.add("cyber-heat-release");
      if (day.safety) cell.classList.add("cyber-heat-safety");
      if (options.onSelect) cell.addEventListener("click", () => options.onSelect?.(day));
      col.append(cell);
    }
    grid.append(col);
  }
  const legend = el("div", "cyber-heat-legend");
  legend.append(el("span", undefined, "Below 50%"));
  for (const level of [0, 1, 2, 3, 4]) legend.append(el("span", `cyber-heat-cell cyber-heat-l${level}`));
  legend.append(el("span", undefined, "95%+"), el("span", "cyber-heat-cell cyber-heat-lx cyber-heat-key"), el("span", undefined, "no runs"));
  box.append(grid, legend);
  return box;
}
