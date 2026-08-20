import unittest

from cyclone_phone_mcp.acceptance import AcceptanceHarness, MockGateway


class AcceptanceTests(unittest.TestCase):
    def test_mock_acceptance_runs_twice_and_learns_signal(self):
        report = AcceptanceHarness(MockGateway()).run(execute=True)
        self.assertTrue(report.passed)
        self.assertEqual(len(report.runs), 2)
        self.assertGreaterEqual(report.runs[1]["knownRouteHints"], report.runs[0]["knownRouteHints"])
        self.assertGreaterEqual(report.runs[1]["brainHints"], report.runs[0]["brainHints"])


if __name__ == "__main__": unittest.main()
