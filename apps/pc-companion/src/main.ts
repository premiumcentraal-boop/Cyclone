import "./styles.css";
import { CyclonePcCompanionApp } from "./app.js";
import { createDesktopService } from "./services/serviceFactory.js";

const root = document.getElementById("app");
if (!root) throw new Error("Cyclone root element is missing");

const app = new CyclonePcCompanionApp(root, createDesktopService());
void app.start();

window.addEventListener("beforeunload", () => app.destroy(), { once: true });
