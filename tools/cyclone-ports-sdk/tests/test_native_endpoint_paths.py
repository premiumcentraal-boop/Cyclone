import pytest
from cyclone_ports import validate_manifest


@pytest.mark.parametrize("endpoint,ok", [
    ("http://127.0.0.1:8765/v1/number-plugins/vmos", True),
    ("http://[::1]:8765/plugins/smsbot", True),
    ("http://localhost.evil.example/plugin", False),
    ("http://127.0.0.1@evil.example/plugin", False),
    ("http://127.0.0.1:99999/plugin", False),
    ("http://127.0.0.1/plugin?key=secret", False),
    ("http://127.0.0.1/plugin#secret", False),
])
def test_native_paths_preserve_the_loopback_and_credential_boundary(endpoint, ok):
    manifest = {"contract": "cyclone.ports/1", "name": "native-provider", "version": "1.0.0", "endpoint": endpoint,
                "serves": [{"port": "code.in", "way": "in"}], "needs": {"personal": False}}
    assert (not validate_manifest(manifest)) is ok
