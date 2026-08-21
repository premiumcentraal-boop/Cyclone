from pathlib import Path

repo = Path(SPECPATH).resolve().parents[2]
a = Analysis(
    [str(repo / "scripts" / "pc-companion" / "entrypoints" / "pc_runtime.py")],
    pathex=[str(repo / "apps" / "device-gateway")],
    binaries=[],
    datas=[],
    hiddenimports=[],
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
    console=True,
)
