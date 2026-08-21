from pathlib import Path

repo = Path(SPECPATH).resolve().parents[2]
a = Analysis(
    [str(repo / "scripts" / "pc-companion" / "entrypoints" / "agent_mcp.py")],
    pathex=[str(repo / "tools" / "cyclone-agent-mcp")],
    binaries=[],
    datas=[],
    hiddenimports=["mcp"],
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
    name="CycloneAgentMCP",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
)
