from __future__ import annotations

import argparse
import json

import uvicorn

from .config import Settings
from .doctor import BridgeDoctor, format_human
from .server import create_app


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cyclone-device-gateway")
    subcommands = parser.add_subparsers(dest="command")
    doctor = subcommands.add_parser("doctor", help="Diagnose the Cyclone USB bridge without printing tokens")
    doctor.add_argument("--json", action="store_true", dest="json_output", help="Emit machine-readable JSON")
    subcommands.add_parser("serve", help="Run the loopback PC Device Gateway")
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "doctor":
        report = BridgeDoctor().run()
        if args.json_output:
            print(json.dumps(report, ensure_ascii=False, separators=(",", ":")))
        else:
            print(format_human(report))
        return 0 if report["overall"] == "READY" else 2

    # Preserve the historical no-argument launch behavior as well as explicit `serve`.
    settings = Settings.from_env()
    uvicorn.run(create_app(settings), host=settings.host, port=settings.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
