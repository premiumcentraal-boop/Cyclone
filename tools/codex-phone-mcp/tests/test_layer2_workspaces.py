import unittest
from cyclone_phone_mcp.tools import PhoneTools, _validate_mcp_action_params
from cyclone_phone_mcp.mcp_server import TOOLS

class FakeGateway:
    def __init__(self): self.calls = []
    def observe(self, **kwargs): self.calls.append(('observe', kwargs)); return {}
    def action(self, tool, params, goal, **kwargs):
        self.calls.append((tool, params, kwargs))
        return {'execution': {'ok': False, 'error': {'code': 'POLICY_DENIED'}}}

class Layer2Tests(unittest.TestCase):
    def test_mcp_switch_keeps_scope_and_android_failure(self):
        gateway = FakeGateway()
        result = PhoneTools(gateway=gateway).phone_workspace({'operation': 'switch', 'params': {'id': 'a'}, 'session_id': 'default-foreground', 'display_id': 0})
        self.assertFalse(result['execution']['ok'])
        self.assertEqual(gateway.calls[0][0], 'observe')
        self.assertEqual(gateway.calls[1][0], 'workspace.switch')
        self.assertEqual(gateway.calls[1][1]['id'], 'a')
        self.assertEqual(gateway.calls[1][2]['session_id'], 'default-foreground')
    def test_no_shell_or_background_scope(self):
        tools = PhoneTools(gateway=FakeGateway())
        with self.assertRaises(ValueError):
            tools.phone_workspace({'operation': 'switch', 'params': {'shell': 'bad'}, 'session_id': 'default-foreground'})
        with self.assertRaises(ValueError):
            tools.phone_workspace({'operation': 'switch', 'params': {'id': 'a'}, 'session_id': 'background-a', 'display_id': 2})
    def test_workspace_identity_preserves_semantic_validation(self):
        _validate_mcp_action_params('phone.click', {'elementId': 'e1', 'workspaceId': 'a', 'workspaceGeneration': 4})
        with self.assertRaises(ValueError):
            _validate_mcp_action_params('phone.click', {'workspaceId': 'a', 'workspaceGeneration': 4})
        with self.assertRaises(ValueError):
            _validate_mcp_action_params('phone.click', {'elementId': 'e1', 'workspaceId': 'a', 'workspaceGeneration': True})
    def test_tool_is_discoverable(self):
        self.assertTrue(any(tool['name'] == 'phone_workspace' for tool in TOOLS))
