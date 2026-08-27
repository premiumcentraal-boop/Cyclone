from pathlib import Path

repo = Path(SPECPATH).resolve().parents[2]
entrypoints = repo / "scripts" / "pc-companion" / "entrypoints"
scrcpy = repo / "apps" / "device-gateway" / "third_party" / "scrcpy"
a = Analysis(
    [str(entrypoints / "pc_runtime.py")],
    pathex=[str(repo / "apps" / "device-gateway"), str(entrypoints)],
    binaries=[],
    datas=[
        (str(scrcpy / "scrcpy-server-v4.0"), "third_party/scrcpy"),
        (str(scrcpy / "scrcpy-v4.0.json"), "third_party/scrcpy"),
        (str(scrcpy / "LICENSE"), "third_party/scrcpy"),
        (str(scrcpy / "NOTICE.md"), "third_party/scrcpy"),
    ],
    hiddenimports=["secure_gateway_token"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, a.binaries, a.datas,
    [],
    name="CyclonePCRuntime",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
)
