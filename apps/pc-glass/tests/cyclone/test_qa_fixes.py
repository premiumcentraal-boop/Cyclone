# Copyright 2026 Cyclone contributors
# Licensed under the Apache License, Version 2.0.
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
import pytest
from artemis.cyclone.launch_goal import launch_goal_package
from artemis.drivers.cyclone.gateway_driver import CycloneGatewayDriver
from artemis.services import llm

@pytest.mark.parametrize('goal,package', [
    ('Open the Gmail app on the phone.', 'com.google.android.gm'),
    ('Open the Chrome browser on the phone.', 'com.android.chrome'),
    ('Open the Settings app and stop with it visible.', 'com.android.settings'),
    ('Open calculator and stop with the app visible.', 'com.google.android.calculator'),
    ('Open Gmail then go home.', None),
    ('Open Settings and search for Battery.', None),
    ('Open Gmail twice.', None),
    ('Open Gmail and send an email.', None),
    ('Do not open Gmail.', None),
])
def test_launch_goal_only(goal, package):
    assert launch_goal_package(goal) == package


def test_capsule_routes_to_openrouter_without_google():
    from artemis.memory.chunking import StepCapsuleLens
    with patch.object(llm, '_env_secret', side_effect=lambda *names: 'fake' if 'OPEN_ROUTER_API_KEY' in names else None), patch.object(llm,'get_google_llm') as google, patch.object(llm,'get_openrouter_llm', return_value='router') as router:
        lens = StepCapsuleLens(model_name='gemini-2.5-flash')
        assert lens._get_llm() == 'router'
        google.assert_not_called()
        assert router.call_args.args[0] == 'google/gemini-2.5-flash'


def test_openrouter_slug_is_preserved():
    with patch.object(llm,'get_openrouter_llm', return_value='router') as router:
        assert llm.get_utils_llm('deepseek/deepseek-v4.1-flash') == 'router'
        assert router.call_args.args[0] == 'deepseek/deepseek-v4.1-flash'


def test_models_prefix_is_normalized():
    assert llm.remap_google_model_for_openrouter('models/gemini-2.5-flash') == 'google/gemini-2.5-flash'


def test_foreground_verification_refreshes_cached_package():
    driver = CycloneGatewayDriver(session_id='default-foreground', device_id='dev_test', token='test')
    driver._current_package = 'old'
    driver._request = Mock(return_value={'observation':{'observationId':'fresh','package':'com.android.settings'}})
    assert asyncio.run(driver.get_current_package()) == 'com.android.settings'
    driver._request = Mock(return_value={})
    assert asyncio.run(driver.get_current_package()) is None


@pytest.mark.asyncio
@pytest.mark.parametrize('body,expected_all', [({}, True), ({'session_id':'mine'}, False), ({'device_id':'pixel'},False), ({'all':True},True)])
async def test_api_stop_scope(body, expected_all):
    from apps.admin_console.routers.tasks import stop_task, task_queue_service
    request = SimpleNamespace(json=AsyncMock(return_value=body))
    with patch.object(task_queue_service, 'stop_tasks', return_value=True) as stop:
        await stop_task(request)
        assert stop.call_args.kwargs['clear_all'] is expected_all
        assert stop.call_args.kwargs['session_id'] == body.get('session_id')


def test_worker_keeps_mode_a_and_router_env(monkeypatch):
    from apps.admin_console.services.task_queue_service import TaskQueueService
    from artemis.runtime.adb_endpoint import AdbEndpoint, AdbTarget
    for key,value in {'CYCLONE_CONNECTED':'1','CYCLONE_SESSION_ID':'default-foreground','CYCLONE_DEVICE_ID':'dev_test','CYCLONE_DEVICE_GATEWAY_TOKEN':'test-token','CYCLONE_DEVICE_GATEWAY_URL':'http://localhost:8765','OPEN_ROUTER_API_KEY':'test-router'}.items():
        monkeypatch.setenv(key,value)
    monkeypatch.delenv('GOOGLE_API_KEY',raising=False)
    monkeypatch.delenv('GEMINI_API_KEY',raising=False)
    target = TaskQueueService._task_target({'device_serial':'pixel'})
    _,env = TaskQueueService._build_worker_invocation({},'test','glass-session','Open Settings','flash',target)
    assert env['CYCLONE_SESSION_ID'] == 'default-foreground'
    assert env['ARTEMIS_SESSION_ID'] == 'glass-session'
    assert env['CYCLONE_DEVICE_GATEWAY_TOKEN'] == 'test-token'
    assert env['OPEN_ROUTER_API_KEY'] == 'test-router'
    assert 'GEMINI_API_KEY' not in env

@pytest.mark.asyncio
async def test_launch_completion_prevents_following_home_action():
    from artemis.agents.flash.runner import FlashRunner, _TurnRecord
    runner = FlashRunner.__new__(FlashRunner)
    runner.goal = 'Open Gmail.'
    runner.executor = SimpleNamespace(action_tool_names={'manage_app','press_key'})
    driver = CycloneGatewayDriver(session_id='default-foreground',device_id='dev_test',token='fake')
    driver.get_current_package = AsyncMock(return_value='com.google.android.gm')
    runner.controller = SimpleNamespace(driver=driver)
    runner._execute_and_record_action = AsyncMock(return_value=(None, [], 1))
    runner._finalize_task_report = AsyncMock(return_value={'status':'success'})
    result = await runner._process_tool_calls(
        [{'name':'manage_app','args':{'action':'launch','app_name':'Gmail'}},
         {'name':'press_key','args':{'key':'home'}}],
        SimpleNamespace(indexed_elements=[]), [], '', {}, None, [], 0, _TurnRecord())
    assert result[0]['status'] == 'success'
    assert runner._execute_and_record_action.await_count == 1
    driver.get_current_package.assert_awaited_once()

@pytest.mark.asyncio
async def test_multistep_launch_does_not_exit_early():
    from artemis.agents.flash.runner import FlashRunner, _TurnRecord
    runner = FlashRunner.__new__(FlashRunner)
    runner.goal = 'Open Gmail then go home.'
    runner.executor = SimpleNamespace(action_tool_names={'manage_app','press_key'})
    runner.controller = SimpleNamespace(driver=Mock())
    runner._execute_and_record_action = AsyncMock(return_value=(None, [], 1))
    runner._finalize_task_report = AsyncMock()
    result = await runner._process_tool_calls(
        [{'name':'manage_app','args':{'action':'launch','app_name':'Gmail'}},
         {'name':'press_key','args':{'key':'home'}}],
        SimpleNamespace(indexed_elements=[]), [], '', {}, None, [], 0, _TurnRecord())
    assert result[0] is None
    assert runner._execute_and_record_action.await_count == 2
    runner._finalize_task_report.assert_not_awaited()

@pytest.mark.asyncio
async def test_missing_key_does_not_retry_step_memory():
    from artemis.memory.step_memory import StepMemoryService
    lens=SimpleNamespace(name='step_capsule',render=AsyncMock(side_effect=ValueError('API key required')))
    svc=StepMemoryService(Mock(),lens=lens,retry_limit=3)
    svc._on_status=Mock()
    svc.submit('job',{'step_number':1})
    await svc.flush()
    assert svc.has_failed('job')
    assert lens.render.await_count == 1
    assert sum(call.args == ('job','failed') for call in svc._on_status.call_args_list) == 1

@pytest.mark.asyncio
async def test_manage_app_success_exits_launch_goal_without_package_match():
    from artemis.agents.flash.runner import FlashRunner, _TurnRecord
    runner = FlashRunner.__new__(FlashRunner)
    runner.goal = "Open Gmail."
    runner.executor = SimpleNamespace(action_tool_names={"manage_app", "press_key"})
    driver = CycloneGatewayDriver(session_id="default-foreground", device_id="dev_test", token="fake")
    driver.get_current_package = AsyncMock(return_value="com.android.launcher3")
    runner.controller = SimpleNamespace(driver=driver)

    async def fake_exec(name, args, tc_id, state, messages, raw_text, step_token_usage,
                        pre_screenshot_bytes, xml_list, action_sequence, turn, injected=None,
                        native_text=None, index_elements=None):
        turn.actions.append((name, "success", "Launched app via Cyclone gateway."))
        return (None, [], 1)

    runner._execute_and_record_action = AsyncMock(side_effect=fake_exec)
    runner._finalize_task_report = AsyncMock(return_value={"status": "success"})
    result = await runner._process_tool_calls(
        [{"name": "manage_app", "args": {"action": "launch", "app_name": "Gmail"}},
         {"name": "press_key", "args": {"key": "home"}}],
        SimpleNamespace(indexed_elements=[]), [], "", {}, None, [], 0, _TurnRecord())
    assert result[0]["status"] == "success"
    assert runner._execute_and_record_action.await_count == 1

