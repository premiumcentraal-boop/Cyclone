from __future__ import annotations

from dataclasses import dataclass
import subprocess
from typing import Sequence


@dataclass(frozen=True)
class ADBDevice:
    serial: str
    state: str
    model: str | None = None
    product: str | None = None
    device: str | None = None
    transport_id: str | None = None


class ADBError(RuntimeError):
    pass


class ADBClient:
    def __init__(self, adb_path: str = "adb", serial: str | None = None):
        self.adb_path = adb_path
        self.serial = serial

    def _base(self, use_serial: bool = True) -> list[str]:
        cmd = [self.adb_path]
        if use_serial and self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def run(self, args: Sequence[str], *, binary: bool = False, timeout: float = 15, use_serial: bool = True):
        p = subprocess.run(self._base(use_serial) + list(args), capture_output=True, timeout=timeout)
        if p.returncode:
            err = p.stderr.decode("utf-8", "replace").strip()
            raise ADBError(err or f"adb exited {p.returncode}")
        return p.stdout if binary else p.stdout.decode("utf-8", "replace")

    def start_process(self, args: Sequence[str], *, stdout=None):
        return subprocess.Popen(self._base() + list(args), stdout=stdout or subprocess.PIPE, stderr=subprocess.PIPE)

    def devices(self) -> list[ADBDevice]:
        text = self.run(["devices", "-l"], use_serial=False)
        out: list[ADBDevice] = []
        for line in text.splitlines()[1:]:
            line = line.strip()
            if not line:
                continue
            bits = line.split()
            serial, state = bits[0], bits[1]
            props = dict(part.split(":", 1) for part in bits[2:] if ":" in part)
            out.append(ADBDevice(serial, state, props.get("model"), props.get("product"), props.get("device"), props.get("transport_id")))
        return out

    def select_device(self, requested_serial: str | None = None) -> ADBDevice:
        devices = [d for d in self.devices() if d.state == "device"]
        target = requested_serial or self.serial
        if target:
            for d in devices:
                if d.serial == target:
                    self.serial = target
                    return d
            raise ADBError(f"Configured device {target!r} is not connected and authorized")
        if len(devices) == 1:
            self.serial = devices[0].serial
            return devices[0]
        if not devices:
            raise ADBError("No authorized ADB device connected")
        raise ADBError("Multiple ADB devices connected; set CYCLONE_DEVICE_SERIAL")

    def shell(self, *args: str, timeout: float = 15) -> str:
        return self.run(["shell", *args], timeout=timeout)

    def exec_out(self, *args: str, timeout: float = 15) -> bytes:
        return self.run(["exec-out", *args], binary=True, timeout=timeout)

    def forward_bridge(self, local_port: int = 8766) -> None:
        self.run(["forward", f"tcp:{local_port}", "localabstract:cyclone_gateway"])
