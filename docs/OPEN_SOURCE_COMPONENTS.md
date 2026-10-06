# Open Source Components

Cyclone may interoperate with or incorporate open-source projects. Before code is copied or distributed, verify the current upstream license and document the exact usage boundary here.

## Mobilerun Portal

- Project: `droidrun/mobilerun-portal`
- Upstream reference inspected: `1b6431dfb90cb797d3cd4147dc4cceefb7dfc047`
- License at that reference: **GNU AGPL-3.0-or-later**
- Copyright notice in upstream license: `Mobilerun Portal Copyright (C) 2025 Niels Schmidt`
- Cyclone usage in `feature/mobile-mobilerun-backend`: **external compatibility backend only**
- Source copied into Cyclone: **No**
- Integration method: documented authenticated Mobilerun Portal HTTP API
- Cyclone adapter: `apps/cyclone-core/app/mobile_portal.py`
- Gateway: `apps/cyclone-core/app/mobile_gateway.py`

### Licensing boundary

Mobilerun Portal remains independently installed/run on the Android device. Cyclone's compatibility adapter was written against Portal's documented network API and exposes Cyclone's own stable `phone.*` protocol to higher layers.

Do not copy Portal source into Cyclone or distribute a modified Portal binary as part of Cyclone without first making an explicit AGPL compliance/distribution decision and preserving all required notices/source availability obligations.

See `docs/MOBILERUN_PORTAL_BACKEND.md` for architecture and configuration.

## Hermes Agent

- Project: `NousResearch/hermes-agent`
- License: **MIT** (checked 2026-10-06 on the project page)
- Cyclone usage (plan 52/53): **structure only** — the Manager's layout (self-registering tool registry, tiered
  system prompt, store/loop split; later capped memory files, playbooks in `SKILL.md` form, session search,
  heartbeat) follows Hermes' design.
- Source copied into Cyclone: **No** (as of plan 53 R1). If a later run copies code (for example the context
  compressor), record the upstream commit here and keep the MIT notice in the copied file's header.

