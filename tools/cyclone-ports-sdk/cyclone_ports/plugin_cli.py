"""``cyclone-plugin``: pack and check a Cyclone plugin package, and keep the signed index (plan 50).

    cyclone-plugin pack <folder> [--out dist]      build <name>-<version>-<platform>.cyclone.zip, print its SHA-256
    cyclone-plugin check <file.cyclone.zip>        every rule Cyclone applies before installing
    cyclone-plugin index keygen                    a new Ed25519 signing key (needs cryptography)
    cyclone-plugin index sign <index.json> --key-env CYCLONE_INDEX_KEY
    cyclone-plugin index verify <index.json> --public <base64>
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import zipfile
from pathlib import Path

from . import index as idx
from .package import PackageError, pack, read_package


def _sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="cyclone-plugin", description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_pack = sub.add_parser("pack")
    p_pack.add_argument("folder")
    p_pack.add_argument("--out", default="dist")
    p_check = sub.add_parser("check")
    p_check.add_argument("file")
    p_index = sub.add_parser("index")
    isub = p_index.add_subparsers(dest="icmd", required=True)
    isub.add_parser("keygen")
    p_sign = isub.add_parser("sign")
    p_sign.add_argument("file")
    p_sign.add_argument("--key-env", default="CYCLONE_INDEX_KEY", help="environment variable holding the PEM key")
    p_verify = isub.add_parser("verify")
    p_verify.add_argument("file")
    p_verify.add_argument("--public", required=True)
    args = parser.parse_args(argv)
    try:
        if args.cmd == "pack":
            target = pack(Path(args.folder), Path(args.out))
            print(json.dumps({"file": str(target), "sha256": _sha(target), "bytes": target.stat().st_size}))
        elif args.cmd == "check":
            path = Path(args.file)
            with zipfile.ZipFile(path) as zf:
                found = read_package(zf)
            print(json.dumps({"ok": True, "name": found["package"]["name"], "version": found["package"]["version"],
                              "kind": found["package"]["kind"], "sha256": _sha(path)}))
        elif args.icmd == "keygen":
            key = idx.keygen()
            print(json.dumps({"keyId": key["keyId"], "public": key["public"]}))
            sys.stderr.write("Private key (store it as a CI secret, never in a repo):\n" + key["private"])
        elif args.icmd == "sign":
            pem = os.environ.get(args.key_env, "")
            if not pem:
                parser.error(f"set {args.key_env} to the PEM signing key")
            path = Path(args.file)
            Path(str(path) + ".sig").write_bytes(idx.sign(path.read_bytes(), pem))
            print(f"signed {path.name}")
        else:
            path = Path(args.file)
            raw_key = base64.b64decode(args.public)
            doc = idx.verify(path.read_bytes(), Path(str(path) + ".sig").read_bytes(), {idx.key_id(raw_key): args.public})
            print(json.dumps({"ok": True, "serial": doc["serial"], "plugins": len(doc["plugins"])}))
    except PackageError as exc:
        print(json.dumps({"ok": False, "problems": exc.problems}, indent=2))
        return 1
    except (idx.IndexTrustError, OSError, zipfile.BadZipFile, ValueError) as exc:
        print(json.dumps({"ok": False, "problems": [str(exc)]}))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
