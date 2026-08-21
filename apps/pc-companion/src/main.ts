import "./styles.css";
import { CyclonePcCompanionApp } from "./app.js";
import { createDesktopService } from "./services/serviceFactory.js";

async function bootstrap(): Promise<void> {
  const root = document.getElementById("app");
  if (!root) throw new Error("Cyclone root element is missing");
  try {
    const service = await createDesktopService();
    const app = new CyclonePcCompanionApp(root, service);
    await app.start();
    window.addEventListener("beforeunload", () => app.destroy(), { once: true });
  } catch {
    root.innerHTML = `<main style="display:grid;place-items:center;min-height:100vh;background:#08090d;color:#f5f3ff;font-family:system-ui"><section style="max-width:520px;padding:32px;text-align:center"><h1>Cyclone couldn't start the local Gateway</h1><p style="color:#a7a4b5;line-height:1.6">Close and reopen Cyclone PC Companion. If the problem continues, open Settings after restarting and run diagnostics.</p></section></main>`;
  }
}

void bootstrap();
