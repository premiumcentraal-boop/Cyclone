"""Delete remote branches whose commits are kept elsewhere, so no work is lost.

A branch is deleted only when its tip is reachable from something that stays:
the dev branch, main, a release tag, or a pull request's head (GitHub keeps every
pull request's commits under refs/pull/<n>/head, closed or merged). Branches whose
commits exist nowhere else are listed and kept.

Run from a full clone with push rights (the owner's PC):

    python scripts/ops/prune_branches.py            # list what would go and what stays
    python scripts/ops/prune_branches.py --delete   # delete them, 50 per push
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

KEEP = {"main", "claude/cyclone-v5-handoff-review-9qrs40", "testbench/results"}
ANCHORS = ("refs/heads/main", "refs/heads/claude/cyclone-v5-handoff-review-9qrs40", "refs/tags", "refs/pull")


def git(*args: str, cwd: Path | None = None) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--remote", default="origin")
    parser.add_argument("--delete", action="store_true", help="delete; without it, only list")
    parser.add_argument("--keep", action="append", default=[], help="another branch to keep (repeatable)")
    args = parser.parse_args()
    keep = KEEP | set(args.keep)
    url = git("remote", "get-url", args.remote).strip()
    with tempfile.TemporaryDirectory() as tmp:
        mirror = Path(tmp) / "mirror.git"
        print(f"Mirroring {url} to check which commits are kept elsewhere…", file=sys.stderr)
        git("clone", "--quiet", "--mirror", url, str(mirror))
        heads = {}
        for line in git("for-each-ref", "--format=%(objectname) %(refname:strip=2)", "refs/heads", cwd=mirror).splitlines():
            sha, name = line.split(" ", 1)
            heads[name] = sha
        prune, unique = [], []
        for name, sha in sorted(heads.items()):
            if name in keep:
                continue
            contained = git("for-each-ref", "--contains", sha, "--format=%(refname)", *ANCHORS, cwd=mirror).split()
            (prune if contained else unique).append(name)
    print(f"Delete ({len(prune)}): commits kept by the dev branch, main, a tag or a pull request")
    for name in prune:
        print(f"  {name}")
    print(f"Keep ({len(unique)}): commits that exist nowhere else")
    for name in unique:
        print(f"  {name}")
    if not args.delete:
        print("\nNothing deleted. Run again with --delete to delete the first list.")
        return 0
    for start in range(0, len(prune), 50):
        batch = prune[start : start + 50]
        subprocess.run(["git", "push", args.remote, "--delete", *batch], check=True)
    print(f"Deleted {len(prune)} branches.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
