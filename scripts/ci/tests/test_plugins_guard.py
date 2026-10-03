"""Plan 50 (alpha.103): plugins from GitHub.

- Bytes come only from GitHub, through one client, with redirects checked against GitHub's own hosts.
- A plugin's key and secret settings reach it only through the stdin handshake: never argv, its environment or a route.
- No plugin process is started through a shell.
- Every /v1/plugins route needs the login token.
- Core holds no plugin: the ID Generator starter and MRZ Studio discovery are gone from the gateway and Glass.
- The frozen Ports contract is unchanged; the package format and the index are separate contracts.
"""
import ast
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
GATEWAY = ROOT / "apps/device-gateway/cyclone_device_gateway"
PLUGINS = GATEWAY / "plugins"
GLASS = ROOT / "apps/glass/src"
SDK = ROOT / "tools/cyclone-ports-sdk/cyclone_ports"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class PluginsGuard(unittest.TestCase):
    def test_only_the_github_client_opens_internet_connections(self):
        for path in PLUGINS.glob("*.py"):
            text = read(path)
            if path.name == "github.py":
                continue
            self.assertNotRegex(text, r"\burllib\.request\b|\burlopen\(|\bimport requests\b|\bhttp\.client\b|\bsocket\.create_connection",
                                f"{path.name} must reach the internet only through plugins/github.py")
        github = read(PLUGINS / "github.py")
        hosts = ast.literal_eval(re.search(r"ALLOWED_HOSTS = frozenset\((\{.*?\})\)", github, re.S).group(1))
        self.assertEqual(hosts, {"api.github.com", "github.com", "raw.githubusercontent.com",
                                 "objects.githubusercontent.com", "release-assets.githubusercontent.com"})
        self.assertIn("class _NoRedirect", github, "urllib must not follow redirects on its own")
        self.assertIn('parts.scheme != "https" or parts.hostname not in ALLOWED_HOSTS', github)

    def test_no_shell_and_secrets_only_through_the_handshake(self):
        for path in PLUGINS.glob("*.py"):
            self.assertNotRegex(read(path), r"shell\s*=\s*True|os\.system\(|os\.popen\(", path.name)
        host = read(PLUGINS / "host.py")
        self.assertIn("self._popen([str(spec.entry)], **kwargs)", host, "the program is started with no arguments")
        env = re.search(r"def minimal_env\(.*?\n(?=\n\nclass|\n\ndef)", host, re.S).group(0)
        self.assertNotRegex(env, r"key|settings|os\.environ\.copy|dict\(os\.environ", "the environment is built, not inherited")
        self.assertIn("handshake_line(port, spec.key", host)
        self.assertIn("redact(", host, "plugin output is redacted before it is logged")

    def test_every_route_needs_the_token_and_none_returns_a_secret(self):
        api = read(PLUGINS / "api.py")
        self.assertEqual(api.count("@router."), api.count("dependencies=[Depends(auth)]"))
        self.assertNotIn("secret_settings", api)
        service = read(PLUGINS / "service.py")
        settings = re.search(r"    def settings\(self, name: str\).*?\n(?=    def )", service, re.S).group(0)
        self.assertIn('"secretsSet"', settings)
        self.assertIn("k not in secret_names", settings, "secret fields are left out of the values a read returns")
        self.assertRegex(settings, r'"values": values, "secretsSet": sorted\(k for k in secret_names if saved\.get\(k\)\)')

    def test_installs_are_atomic_by_construction(self):
        service = read(PLUGINS / "service.py")
        self.assertIn("with self.store.transaction() as db:", service)
        self.assertIn("os.replace(unpacked, target)", service, "a version folder is placed with one rename")
        self.assertIn("def reconcile(self)", service)
        store = read(PLUGINS / "store.py")
        self.assertIn('BEGIN IMMEDIATE', store)
        self.assertIn("PRAGMA synchronous=FULL", store)

    def test_the_index_trusts_only_compiled_in_keys(self):
        index = read(PLUGINS / "index.py")
        self.assertRegex(index, r"TRUSTED_KEYS: dict\[str, str\] = \{")
        self.assertIn("https://raw.githubusercontent.com/", index)
        self.assertNotIn("os.environ", index, "no environment variable can add a trusted key")
        sdk_index = read(SDK / "index.py")
        self.assertIn("min_serial", sdk_index, "an older index never replaces a newer one")
        self.assertIn("has expired", sdk_index)

    def test_core_holds_no_plugin(self):
        sources = [p for p in GATEWAY.rglob("*.py")] + [p for p in GLASS.rglob("*.ts")]
        for path in sources:
            self.assertNotRegex(read(path), r"(?i)id_generator|id-generator|idGenerator|\bmrz", str(path.relative_to(ROOT)))
        self.assertFalse((GATEWAY / "command/mrz.py").exists())
        self.assertFalse((GATEWAY / "ports/id_generator.py").exists())

    def test_the_ports_contract_is_unchanged(self):
        catalog = read(SDK / "catalog.py")
        self.assertIn('CONTRACT = "cyclone.ports/1"', catalog)
        names = set(re.findall(r'Port\("([a-z.]+)",', catalog))
        self.assertEqual(names, {"run.event", "screen.shot", "account.fields", "page.text", "file.out", "log.line",
                                 "file.in", "value.in", "code.in", "link.in", "secret.out", "secret.in"})
        package = read(SDK / "package.py")
        self.assertIn('PACKAGE = "cyclone.package/1"', package)
        for limit in ('"zip_bytes": 200 * 1024 * 1024', '"unpacked_bytes": 500 * 1024 * 1024', '"entries": 5_000',
                      '"path_chars": 240', '"ratio": 100'):
            self.assertIn(limit, package)


if __name__ == "__main__":
    unittest.main()


class PluginBuildGuard(unittest.TestCase):
    def test_the_build_never_leaves_a_plugin_holding_the_callers_pipe(self):
        """alpha.103's first publish hung: a one-file plugin's child kept the build's stdout open. The conformance run
        sends output nowhere and stops the whole process tree; the release smoke's web calls time out."""
        build = read(SDK / "build.py")
        self.assertIn("stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL", build)
        self.assertIn('"taskkill", "/F", "/T"', build)
        self.assertIn("os.killpg(proc.pid", build)
        smoke = read(ROOT / "scripts/pc/build-pc-package.ps1")
        self.assertNotRegex(smoke, r"Invoke-RestMethod (?!-TimeoutSec)")
