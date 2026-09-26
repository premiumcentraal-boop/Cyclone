from __future__ import annotations

import json
import unittest

from cyclone_device_gateway.desktop_runtime.command_splitter import (
    DeviceRef,
    OpenRouterCommandSplitter,
    SplitterError,
    split_command,
)


def devices():
    return [
        DeviceRef(device_id="dev_aaa", nickname="Work Phone", name="Pixel 7"),
        DeviceRef(device_id="dev_bbb", nickname="Tablet", name="Galaxy Tab"),
    ]


class FakeSplitterModel:
    def __init__(self, response: dict):
        self._response = response
        self.last_call = None

    def split(self, command, devices):
        self.last_call = (command, devices)
        return self._response


class SplitCommandTests(unittest.TestCase):
    def test_splits_one_sentence_into_two_device_goals(self):
        model = FakeSplitterModel({
            "assignments": [
                {"deviceId": "dev_aaa", "goal": "unlock and check messages"},
                {"deviceId": "dev_bbb", "goal": "open camera and take a photo"},
            ],
            "clarification": None,
        })
        result = split_command(model, "unlock and check messages on Work Phone, open camera and take a photo on Tablet", devices())
        self.assertIsNone(result.clarification)
        self.assertEqual(len(result.assignments), 2)
        by_device = {a.device_id: a.goal for a in result.assignments}
        self.assertEqual(by_device["dev_aaa"], "unlock and check messages")
        self.assertEqual(by_device["dev_bbb"], "open camera and take a photo")

    def test_unrecognized_device_id_refuses_the_whole_split(self):
        model = FakeSplitterModel({
            "assignments": [{"deviceId": "dev_not_real", "goal": "do something"}],
            "clarification": None,
        })
        result = split_command(model, "do something on the fridge", devices())
        self.assertEqual(result.assignments, [])
        self.assertIsNotNone(result.clarification)

    def test_model_asks_for_clarification_directly(self):
        model = FakeSplitterModel({
            "assignments": [],
            "clarification": "Which device did you mean by 'the other one'?",
        })
        result = split_command(model, "do something on the other one", devices())
        self.assertEqual(result.assignments, [])
        self.assertIn("other one", result.clarification)

    def test_duplicate_device_assignment_rejected(self):
        model = FakeSplitterModel({
            "assignments": [
                {"deviceId": "dev_aaa", "goal": "do X"},
                {"deviceId": "dev_aaa", "goal": "do Y"},
            ],
        })
        with self.assertRaises(SplitterError):
            split_command(model, "do two things on work phone", devices())

    def test_empty_goal_rejected(self):
        model = FakeSplitterModel({"assignments": [{"deviceId": "dev_aaa", "goal": ""}]})
        with self.assertRaises(SplitterError):
            split_command(model, "do something vague", devices())

    def test_no_assignments_and_no_clarification_is_malformed(self):
        model = FakeSplitterModel({"assignments": []})
        with self.assertRaises(SplitterError):
            split_command(model, "do a thing", devices())

    def test_empty_command_rejected_before_calling_model(self):
        model = FakeSplitterModel({"assignments": []})
        with self.assertRaises(SplitterError):
            split_command(model, "   ", devices())
        self.assertIsNone(model.last_call)

    def test_model_receives_the_exact_device_catalog(self):
        model = FakeSplitterModel({"assignments": [{"deviceId": "dev_aaa", "goal": "x"}]})
        split_command(model, "do x on work phone", devices())
        command, passed_devices = model.last_call
        self.assertEqual(command, "do x on work phone")
        self.assertEqual([d.device_id for d in passed_devices], ["dev_aaa", "dev_bbb"])


class OpenRouterCommandSplitterTests(unittest.TestCase):
    def test_sends_device_catalog_and_parses_response(self):
        captured = {}

        def fake_post(url, headers, body):
            captured["body"] = body
            return {"choices": [{"message": {"content": json.dumps({
                "assignments": [
                    {"deviceId": "dev_aaa", "goal": "check mail"},
                    {"deviceId": "dev_bbb", "goal": "check calendar"},
                ],
                "clarification": None,
            })}}]}

        splitter = OpenRouterCommandSplitter("sk-test", "some/model", ["providerA"], post=fake_post)
        result = splitter.split("check mail on work phone and calendar on tablet", devices())

        self.assertEqual(len(result.assignments), 2)
        sent_user_message = json.loads(captured["body"]["messages"][1]["content"])
        self.assertEqual(
            {d["deviceId"] for d in sent_user_message["devices"]},
            {"dev_aaa", "dev_bbb"},
        )

    def test_no_devices_rejected_before_any_request(self):
        splitter = OpenRouterCommandSplitter("sk-test", "some/model", ["providerA"], post=lambda *a: {})
        with self.assertRaises(SplitterError):
            splitter.split("do something", [])


if __name__ == "__main__":
    unittest.main()
