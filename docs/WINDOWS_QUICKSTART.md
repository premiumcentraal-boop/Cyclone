# Cyclone on Windows

Cyclone can be launched from a fresh ZIP download or clone on Windows 10/11.
The launcher keeps application data in Docker named volumes and puts the default
workspace and vault under `.runtime/`, which is ignored by Git.

## First launch

1. Install Docker Desktop with Linux containers enabled and make sure it is running.
2. Install Node.js 22 or newer.
3. Extract the repository, then double-click `Launch-Cyclone.bat`.

The first launch creates a local `.env`, installs the desktop dependencies, starts
Postgres, Redis, Hermes, Cyclone Core, and n8n, waits for Core health, and opens the
Cyclone browser client at <http://127.0.0.1:1420>.

The native Tauri window is used automatically when Rust/Cargo is installed. Without
Rust, the browser client is the supported fallback. To force the browser client:

```powershell
.\scripts\launch-windows.ps1 -WebOnly
```

To stop services without deleting persistent data, double-click `Stop-Cyclone.bat`
or run:

```powershell
.\scripts\stop-windows.ps1
```

## Configure live agents and Telegram

The launcher never writes provider or Telegram credentials into the repository.
After the first launch, edit the local `.env` and set whichever values you use:

```dotenv
DEEPSEEK_API_KEY=...
OPENROUTER_API_KEY=...
TELEGRAM_BOT_TOKEN=...
TELEGRAM_ALLOWED_USERS=your-telegram-user-id
TELEGRAM_HOME_CHANNEL=your-telegram-chat-id
```

Then run `Launch-Cyclone.bat` again. `.env` is ignored and must never be committed.

## Native Windows installer

The checked-in Tauri project can produce an NSIS installer once Rust stable and the
Windows WebView2/C++ build prerequisites are installed:

```powershell
cd apps\desktop
npm.cmd ci
npm.cmd run tauri:build
```

The installer is written below `apps\desktop\src-tauri\target\release\bundle\nsis\`.
