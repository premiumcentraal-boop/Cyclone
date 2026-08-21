# Cyclone PC Companion sidecar packaging

Desktop V1 prepares two standalone Windows Python sidecars with PyInstaller:

- `CyclonePCRuntime.exe` — entry point for the existing PC Device Gateway runtime.
- `CycloneAgentMCP.exe` — generic MCP/connector entry point.

The build lock pins Python 3.14.7, PyInstaller 6.22.0, and MCP SDK 2.0.0. Final target machines do not require Python, pip, a virtual environment, or a repository checkout.

The Agent 3 workflow builds sidecars only. Agent 2's Tauri application is intentionally not modified here. The release workflow exposes an opt-in Tauri gate for the final integration pass; it defaults off and never publishes a production GitHub Release.

## Third-party binaries

`third-party-binaries.lock.json` is authoritative for external binary payloads. Every entry must contain exact name/version, official source, license, SHA256, and deterministic download/build method. The list is empty in Agent 3 because no external binary is bundled by this branch.

If final consolidation bundles scrcpy, add a pinned scrcpy release asset with its exact upstream URL/version/license/SHA256 before download. Never use a `latest` URL or unverified binary.

Release metadata generation creates `SHA256SUMS.txt`, `source-sha.txt`, `release-provenance.json`, and `THIRD_PARTY_NOTICES` beside the eventual installer/sidecars.
