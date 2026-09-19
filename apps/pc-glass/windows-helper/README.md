# Cyclone Glass - Windows helper

**Product name:** Cyclone Glass

Easy Start / Stop / **Update** / Open for the web platform. **No command-prompt windows.**

## Daily use

1. Double-click **`Launch Cyclone Glass Helper.vbs`**
2. Or run `Install-DesktopShortcut.ps1` once -> Desktop **Cyclone Glass**

| Button | Action |
|--------|--------|
| **Start** | Hidden `uv run python -m artemis ui --port 8000 --no-open` with Mode A env |
| **Stop** | Hidden `artemis stop` |
| **Update** | Fetch GitHub channel tip -> `git reset --hard` (replace tracked files) -> keep `.env` / `traces` / DBs -> optional UI rebuild -> restart |
| **Open UI** | http://127.0.0.1:8000 |

Update channel: `update-channel.json` (default branch `feature/pc-glass-artemis`).

Logs / update backups: `%LOCALAPPDATA%\CycloneGlass\`

## Rapid-fire updates

Every Update:

1. Stops Glass  
2. Backs up data under `%LOCALAPPDATA%\CycloneGlass\update-backup\…`  
3. `git fetch` + `git reset --hard origin/<branch>` - **old tracked files gone, new files in**  
4. `git clean` under `apps/pc-glass` with excludes so **data and `.venv` stay**  
5. Re-applies Mode A defaults into `.env` **without wiping API keys**  
6. Rebuilds showcase UI when checked  
7. Starts Glass again  

## Console `.cmd` files

`Start Cyclone PC Glass.cmd` opens this helper when present. Prefer the VBS / Desktop shortcut.
