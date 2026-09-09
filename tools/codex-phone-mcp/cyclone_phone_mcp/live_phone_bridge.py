"""Dedicated foreground MCP transport; shares One's broker, never native Codex.

Only this module exposes the typed cloud catalog. No subprocess or raw gateway
endpoint is accepted. HTTP is private loopback; One owns optional HTTPS tunneling.
"""
from __future__ import annotations
import base64
import hashlib
import hmac
import json
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from .live_phone import ACTIONS, validate_request
from .live_phone_ipc import root

PORT = 8788
PREFIX = 'cyclone_phone_'
OPERATIONS = {
    'devices': 'devices', 'status': 'status', 'observe': 'observe',
    'screenshot': 'screenshot', 'locate': 'locate', 'search_ui': 'locate',
    'inspect': 'inspect', 'tap': 'tap', 'long_press': 'long-press',
    'type': 'type', 'clear_text': 'clear-text', 'scroll': 'scroll',
    'back': 'back', 'home': 'home', 'open_app': 'open-app',
}


def catalog():
    result = []
    for name, op in OPERATIONS.items():
        props = {}
        required = []
        def field(key, description, needed=True):
            props[key] = {'type': 'string', 'maxLength': 4000 if key == 'text' else 240, 'description': description}
            if needed: required.append(key)
        if op not in {'devices', 'status'}:
            field('device', 'Physical USB/LAN device from cyclone_phone_devices.')
        if op in {'locate'} | ACTIONS:
            field('goal', 'Intended visible result. Observe, locate, act once, inspect verification.')
        if op in ACTIONS:
            field('observation_id', 'Current observation ID; expires after 30 seconds or any action.')
            props['user_authorized'] = {'type': 'boolean', 'default': False, 'description': 'True only with explicit user authorization; Android GATE still applies.'}
        if op in {'inspect', 'tap', 'long-press', 'type', 'clear-text'}:
            field('element', 'Current semantic element ID returned by locate.')
        if op == 'type': field('text', 'Text for the selected editable element.')
        if op == 'scroll':
            field('direction', 'Semantic scrolling direction.')
            props['direction']['enum'] = ['forward', 'backward', 'up', 'down', 'left', 'right']
        if op == 'open-app': field('package', 'Installed Android package, for example com.android.chrome.')
        result.append({'name': PREFIX + name, 'description': f'Live physical foreground phone: {name.replace("_", " ")}. No background sessions. Images accompany fresh observations. Respect Pause/Stop and user confirmations.',
                       'inputSchema': {'type': 'object', 'properties': props, 'required': required, 'additionalProperties': False},
                       'annotations': {'readOnlyHint': op not in ACTIONS, 'destructiveHint': op in ACTIONS, 'idempotentHint': False, 'openWorldHint': True}})
    return result


def cloud_key():
    from cyclone_device_gateway.tooling_seam import _protect, _unprotect
    path = root() / 'cloud-key.dpapi'
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(_protect(secrets.token_urlsafe(32).encode()))
    return _unprotect(path.read_bytes()).decode()


class Bridge:
    def __init__(self, broker):
        self.broker = broker
        self.lock = broker.lock
        self.frames = {}
        self.last_cloud = 0

    def control(self):
        try: return json.loads((root() / 'control.json').read_text())
        except (OSError, ValueError): return {}

    def dispatch(self, method, params):
        if method == 'initialize':
            return {'protocolVersion': '2025-03-26', 'capabilities': {'tools': {}, 'resources': {}}, 'serverInfo': {'name': 'Cyclone Live Phone', 'version': '1.2.0'},
                    'instructions': 'Use cyclone_phone tools directly. Foreground only. Observe and locate before acting. Every action needs a fresh observation_id. Inspect verification and the returned image. Never retry an uncertain action.'}
        if method == 'ping': return {}
        if method == 'tools/list': return {'tools': catalog()}
        if method == 'resources/list': return {'resources': []}
        if method == 'resources/templates/list': return {'resourceTemplates': []}
        if method == 'resources/read': return self.read_frame(params)
        if method != 'tools/call': raise ValueError('Unsupported MCP method')
        name = params.get('name', '')
        schemas = {t['name']: t['inputSchema'] for t in catalog()}
        if name not in schemas: raise ValueError('Unknown phone tool')
        args = params.get('arguments', {})
        schema = schemas[name]
        if not isinstance(args, dict) or set(args) - set(schema['properties']) or set(schema['required']) - set(args):
            raise ValueError('Invalid typed arguments')
        request = validate_request({'operation': OPERATIONS[name[len(PREFIX):]], **args})
        with self.lock:
            self.last_cloud = int(time.time())
            if request['operation'] not in {'status', 'devices', 'inspect'}: self.frames.clear()
            result = self.broker.handle(request)
            return self.response(result)

    def response(self, result):
        # Vision transport is added separately; paths never cross this boundary.
        def clean(value):
            if isinstance(value, dict): return {k: clean(v) for k,v in value.items() if k not in {'screenshot_path', 'path', 'reference'}}
            if isinstance(value, list): return [clean(v) for v in value]
            return value
        return {'content': [{'type': 'text', 'text': json.dumps(clean(result))}], 'isError': result.get('ok') is False}

    def read_frame(self, params):
        raise ValueError('No current image resource')


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    def __init__(self, address, bridge, token):
        self.bridge, self.token = bridge, token
        self.slots = threading.BoundedSemaphore(4)
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass  # Never log headers, queries, UI or typed input.
    def setup(self):
        super().setup()
        self.connection.settimeout(95)
    def reply(self, status, body=None):
        data = b'' if body is None else json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def allowed(self):
        return self.path == '/mcp' and not self.headers.get('Origin') and hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + self.server.token)
    def do_GET(self): self.reply(405 if self.allowed() else 401)
    def do_DELETE(self): self.reply(405 if self.allowed() else 401)
    def do_POST(self):
        if not self.allowed(): return self.reply(401)
        if not self.server.slots.acquire(blocking=False): return self.reply(429)
        ident = None
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 16384 or self.headers.get('Transfer-Encoding'):
                return self.reply(413)
            request = json.loads(self.rfile.read(size))
            if not isinstance(request, dict) or request.get('jsonrpc') != '2.0': raise ValueError()
            ident = request.get('id')
            if ident is not None and (isinstance(ident, (dict,list,bool)) or len(str(ident)) > 128): raise ValueError()
            method = request.get('method')
            if method == 'notifications/initialized' and ident is None: return self.reply(202)
            if ident is None: raise ValueError('Request ID required')
            params = request.get('params', {})
            if not isinstance(params, dict): raise ValueError()
            result = self.server.bridge.dispatch(method, params)
            self.reply(200, {'jsonrpc': '2.0', 'id': ident, 'result': result})
        except (ValueError, TypeError, KeyError):
            self.reply(200, {'jsonrpc': '2.0', 'id': ident, 'error': {'code': -32602, 'message': 'Invalid or unavailable Live Phone request; observe again'}})
        except Exception:
            self.reply(200, {'jsonrpc': '2.0', 'id': ident, 'error': {'code': -32603, 'message': 'Phone unavailable. Do not replay an uncertain action; observe again.'}})
        finally: self.server.slots.release()


def start_bridge(broker):
    server = Server(('127.0.0.1', PORT), Bridge(broker), cloud_key())
    threading.Thread(target=server.serve_forever, name='cyclone-live-phone-mcp', daemon=True).start()
