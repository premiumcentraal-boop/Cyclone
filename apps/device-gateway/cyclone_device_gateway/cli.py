from __future__ import annotations

import argparse
import getpass
import json

import uvicorn

from .adb.client import ADBClient
from .adb.onboarding import ADBTransportOnboarding, TransportOnboardingError
from .config import Settings, resolve_adb_path
from .desktop_runtime.api import create_desktop_app
from .desktop_runtime.transport_api import create_transport_router
from .doctor import BridgeDoctor, format_human
from .skill_http import attach_to_app


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="cyclone-device-gateway")
    subcommands = parser.add_subparsers(dest="command")
    doctor = subcommands.add_parser("doctor", help="Diagnose the Cyclone USB bridge without printing tokens")
    doctor.add_argument("--json", action="store_true", dest="json_output", help="Emit machine-readable JSON")

    transport = subcommands.add_parser("transport", help="Onboard USB, Android Wireless Debugging, or VMOS ADB")
    transport_subcommands = transport.add_subparsers(dest="transport_command")

    usb = transport_subcommands.add_parser("usb", help="Show USB/ADB authorization state")
    usb.add_argument("--json", action="store_true", dest="json_output")

    wifi = transport_subcommands.add_parser("wifi", help="Pair and connect Android Wireless debugging")
    wifi.add_argument("--pair", required=True, dest="pair_endpoint", help="Pairing address shown by Android")
    wifi.add_argument("--connect", required=True, dest="connect_endpoint", help="Device IP address and port shown on Wireless debugging")
    wifi.add_argument("--json", action="store_true", dest="json_output")

    vmos = transport_subcommands.add_parser("vmos", help="Connect a VMOS/remote ADB endpoint")
    vmos.add_argument("--endpoint", required=True, help="VMOS ADB endpoint in host:port form")
    vmos.add_argument("--json", action="store_true", dest="json_output")

    subcommands.add_parser("serve", help="Run the loopback PC Device Gateway and Desktop V1 fleet runtime")
    return parser


def _print_transport(result: dict[str, object], *, json_output: bool) -> None:
    if json_output:
        print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
        return
    mode = str(result.get("mode") or "transport").upper()
    print(f"{mode}: {'READY' if result.get('ok') is True else 'ACTION REQUIRED'}")
    next_step = result.get("next")
    if isinstance(next_step, str) and next_step:
        print(next_step)
    devices = result.get("devices")
    if isinstance(devices, list):
        for item in devices:
            if not isinstance(item, dict):
                continue
            print(f"- {item.get('serial', 'unknown')} · {item.get('state', 'unknown')} · {item.get('model') or 'Android'}")
    device = result.get("device")
    if isinstance(device, dict):
        print(f"- {device.get('serial', 'unknown')} · {device.get('state', 'unknown')} · {device.get('model') or 'Android'}")


def _run_transport(args: argparse.Namespace) -> int:
    onboarding = ADBTransportOnboarding(ADBClient(resolve_adb_path()))
    try:
        if args.transport_command == "usb":
            result = onboarding.usb_status()
        elif args.transport_command == "wifi":
            # Never accept the pairing code as a command-line argument: argv is routinely retained
            # by shells/process inspectors. Prompt without echo, then keep it only in local memory.
            pairing_code = getpass.getpass("Android Wireless debugging 6-digit pairing code: ")
            result = onboarding.pair_wireless(args.pair_endpoint, pairing_code, args.connect_endpoint)
        elif args.transport_command == "vmos":
            result = onboarding.connect(args.endpoint, mode="vmos")
        else:
            raise TransportOnboardingError("TRANSPORT_MODE_REQUIRED", "Choose transport usb, wifi, or vmos.")
    except TransportOnboardingError as exc:
        payload = {"ok": False, "code": exc.code, "message": exc.safe_message}
        if getattr(args, "json_output", False):
            print(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
        else:
            print(f"ACTION REQUIRED: {exc.safe_message}")
        return 2
    _print_transport(result, json_output=getattr(args, "json_output", False))
    return 0 if result.get("ok") is True else 2


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

    if args.command == "transport":
        return _run_transport(args)

    settings = Settings.from_env()
    app = create_desktop_app(settings)
    app.include_router(create_transport_router(app.state.desktop_runtime, settings.token))
    attach_to_app(app)
    uvicorn.run(app, host=settings.host, port=settings.port)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
