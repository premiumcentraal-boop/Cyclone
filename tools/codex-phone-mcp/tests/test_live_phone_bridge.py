import base64
import json
import os
import tempfile
import threading
import unittest
import urllib.request
import urllib.error
from pathlib import Path
from unittest.mock import Mock, patch
from cyclone_phone_mcp.live_phone_bridge import Bridge, Server, catalog
from cyclone_phone_mcp.live_phone_ipc import root

class DirectBridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        env = patch.dict(os.environ, {'LOCALAPPDATA': self.tmp.name}); env.start(); self.addCleanup(env.stop)
        root().mkdir(parents=True)
        self.control(True)
        self.broker = Mock(); self.broker.lock = threading.RLock()
        self.broker.engine.observations = {'pixel': ('o1', 0, True)}
        self.broker.handle.return_value = {'ok': True, 'device': 'pixel'}
        self.bridge = Bridge(self.broker)
    def control(self, enabled, generation='one'):
        (root() / 'control.json').write_text(json.dumps({'enabled': enabled, 'stopped': not enabled, 'generation': generation}))
    def test_exact_typed_cloud_catalog_and_no_native_route(self):
        names = {t['name'] for t in catalog()}
        self.assertEqual(15, len(names))
        self.assertIn('cyclone_phone_search_ui', names)
        self.assertNotIn('phone_act', names)
        for tool in catalog():
            self.assertFalse(tool['inputSchema']['additionalProperties'])
            self.assertFalse({'shell', 'token', 'url', 'display_id', 'session_id', 'workspaceId'} & set(tool['inputSchema']['properties']))
    def test_typed_actions_reuse_broker_and_require_current_authority(self):
        for op in ('tap', 'long_press', 'type', 'clear_text', 'scroll', 'back', 'home', 'open_app'):
            schema = next(t['inputSchema'] for t in catalog() if t['name'] == 'cyclone_phone_' + op)
            args = {k: 'test' for k in schema['required']}
            args.update(device='pixel', observation_id='o1')
            self.bridge.dispatch('tools/call', {'name': 'cyclone_phone_' + op, 'arguments': args})
            self.assertEqual('o1', self.broker.handle.call_args.args[0]['observation_id'])
        with self.assertRaises(ValueError):
            self.bridge.dispatch('tools/call', {'name': 'cyclone_phone_home', 'arguments': {'device':'pixel'}})
        self.assertNotIn('CycloneLivePhone', Path('scripts/pc-companion/entrypoints/agent_mcp.py').read_text())
    def test_inline_vision_and_expiring_authenticated_resource(self):
        from PIL import Image
        path = root() / 'latest.png'; Image.new('RGB', (8, 12), 'blue').save(path)
        self.broker.handle.return_value = {'ok': True, 'observation_id': 'o1', 'device':'pixel', 'ui': {}, 'vision': {'ready': True, 'path': str(path), 'mime':'image/png', 'width':8, 'height':12}, 'screenshot_path':str(path)}
        r = self.bridge.dispatch('tools/call', {'name':'cyclone_phone_observe','arguments':{'device':'pixel'}})
        self.assertEqual(path.read_bytes(), base64.b64decode(r['content'][1]['data']))
        self.assertNotIn(str(path), r['content'][0]['text'])
        uri = r['structuredContent']['screenshot']['reference']
        self.assertEqual(path.read_bytes(), base64.b64decode(self.bridge.read_frame({'uri':uri})['contents'][0]['blob']))
        self.control(False, 'pause')
        with self.assertRaises(ValueError): self.bridge.read_frame({'uri':uri})
        with self.assertRaises(ValueError): self.bridge.read_frame({'uri':'file:///etc/passwd'})
    def test_http_authentication_initialize_and_no_arbitrary_execution(self):
        server = Server(('127.0.0.1',0), self.bridge, 'test-credential')
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        url = 'http://127.0.0.1:%s/mcp' % server.server_port
        body = json.dumps({'jsonrpc':'2.0','id':1,'method':'tools/list'}).encode()
        with self.assertRaises(urllib.error.HTTPError): urllib.request.urlopen(urllib.request.Request(url,body), timeout=3)
        headers = {'Authorization':'Bearer test-credential','Content-Type':'application/json'}
        result = json.load(urllib.request.urlopen(urllib.request.Request(url,body,headers), timeout=3))
        self.assertEqual(15,len(result['result']['tools']))
        init = json.dumps({'jsonrpc':'2.0','id':2,'method':'initialize','params':{}}).encode()
        r = json.load(urllib.request.urlopen(urllib.request.Request(url,init,headers), timeout=3))
        self.assertEqual('Cyclone Live Phone',r['result']['serverInfo']['name'])
        with self.assertRaises(ValueError): self.bridge.dispatch('tools/call',{'name':'shell','arguments':{}})
        self.broker.handle.assert_not_called()
