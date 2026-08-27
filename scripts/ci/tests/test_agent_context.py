from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
MODULE_PATH = ROOT / "scripts" / "agent" / "cyclone-context.py"
SPEC = importlib.util.spec_from_file_location("cyclone_context", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CycloneContextTests(unittest.TestCase):
    def test_python_beta_and_semver_beta_compare_equal(self) -> None:
        self.assertEqual(
            MODULE.normalize_product_version("3.3.0b2"),
            MODULE.normalize_product_version("3.3.0-beta.2"),
        )

    def test_context_starts_with_only_the_scope_first_documents(self) -> None:
        context = MODULE.build_context()
        self.assertEqual(
            context["canonical_docs"],
            [
                "AGENTS.md",
                "docs/agent-system/FAST_WORK_AND_TOKEN_PLAYBOOK.md",
            ],
        )
        self.assertIn("docs/agent-system/README.md", context["reference_docs"])

    def test_porcelain_paths_preserve_dot_directories(self) -> None:
        self.assertEqual(
            MODULE.porcelain_paths(" M .github/workflows/check.yml\n?? docs/new.md"),
            [".github/workflows/check.yml", "docs/new.md"],
        )


if __name__ == "__main__":
    unittest.main()
