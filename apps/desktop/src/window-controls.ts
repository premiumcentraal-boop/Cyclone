type TauriWindow = {
  close(): Promise<void>;
  minimize(): Promise<void>;
  isMaximized(): Promise<boolean>;
  maximize(): Promise<void>;
  unmaximize(): Promise<void>;
};

async function resolveWindow(): Promise<TauriWindow | undefined> {
  try {
    const module = await import("@tauri-apps/api/window");
    return module.getCurrentWindow() as unknown as TauriWindow;
  } catch {
    return undefined;
  }
}

export async function handleWindowAction(action: "close" | "minimize" | "maximize"): Promise<void> {
  const tauriWindow = await resolveWindow();
  if (!tauriWindow) {
    if (action === "maximize") document.documentElement.classList.toggle("window-expanded");
    return;
  }
  if (action === "close") await tauriWindow.close();
  if (action === "minimize") await tauriWindow.minimize();
  if (action === "maximize") {
    if (await tauriWindow.isMaximized()) await tauriWindow.unmaximize();
    else await tauriWindow.maximize();
  }
}
