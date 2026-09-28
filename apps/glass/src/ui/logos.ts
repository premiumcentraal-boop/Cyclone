/**
 * The two marks in the top-left switcher: Cyclone (the phone app's three teal arcs) and the Command Center (a page
 * with a plan on it, in Glass's indigo). Built with DOM APIs, like every icon in Glass.
 */
const SVG_NS = "http://www.w3.org/2000/svg";
let serial = 0;

function svgEl<K extends string>(tag: K, attrs: Record<string, string>): SVGElement {
  const node = document.createElementNS(SVG_NS, tag) as SVGElement;
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  return node;
}

function gradient(id: string, stops: Array<[string, string]>, x1 = "0", y1 = "0", x2 = "1", y2 = "1"): SVGElement {
  const g = svgEl("linearGradient", { id, x1, y1, x2, y2 });
  for (const [offset, color] of stops) g.append(svgEl("stop", { offset, "stop-color": color }));
  return g;
}

/** Cyclone: three arcs turning around a centre (the same paths as the phone app's mark). */
export function cycloneLogo(className = "logo logo-cyclone"): SVGSVGElement {
  const id = `cy-arc-${(serial += 1)}`;
  const svg = svgEl("svg", { viewBox: "0 0 128 128", class: className, "aria-hidden": "true" }) as SVGSVGElement;
  const defs = svgEl("defs", {});
  defs.append(gradient(id, [["0", "#CCF6EF"], ["0.45", "#41D7CB"], ["1", "#1B6E70"]]));
  svg.append(svgEl("rect", { x: "0", y: "0", width: "128", height: "128", rx: "30", class: "logo-tile" }), defs);
  for (const d of ["M101 45A43 43 0 0 0 31 27", "M25 79a43 43 0 0 0 70 18", "M45 100a43 43 0 0 0 18-78"]) {
    svg.append(svgEl("path", { d, fill: "none", stroke: `url(#${id})`, "stroke-width": "16", "stroke-linecap": "round" }));
  }
  return svg;
}

/** Command Center: a page with a heading line, two text lines and a ticked plan card, on an indigo tile. */
export function commandLogo(className = "logo logo-command"): SVGSVGElement {
  const id = `cc-tile-${(serial += 1)}`;
  const svg = svgEl("svg", { viewBox: "0 0 128 128", class: className, "aria-hidden": "true" }) as SVGSVGElement;
  const defs = svgEl("defs", {});
  defs.append(gradient(id, [["0", "#7A7AF2"], ["1", "#3F3FB8"]]));
  svg.append(defs, svgEl("rect", { x: "0", y: "0", width: "128", height: "128", rx: "30", fill: `url(#${id})` }));
  svg.append(
    svgEl("rect", { x: "30", y: "26", width: "68", height: "78", rx: "10", fill: "#FFFFFF", opacity: "0.96" }),
    svgEl("rect", { x: "41", y: "38", width: "34", height: "8", rx: "4", fill: "#3F3FB8" }),
    svgEl("rect", { x: "41", y: "53", width: "46", height: "5", rx: "2.5", fill: "#B9B9EE" }),
    svgEl("rect", { x: "41", y: "63", width: "38", height: "5", rx: "2.5", fill: "#B9B9EE" }),
    svgEl("rect", { x: "41", y: "76", width: "46", height: "17", rx: "5", fill: "#EDEDFC" }),
    svgEl("path", { d: "M47 84.5l4 4 8-8", fill: "none", stroke: "#1F9D5C", "stroke-width": "4", "stroke-linecap": "round", "stroke-linejoin": "round" }),
    svgEl("rect", { x: "64", y: "82.5", width: "17", height: "4", rx: "2", fill: "#8C8CE0" }),
  );
  return svg;
}
