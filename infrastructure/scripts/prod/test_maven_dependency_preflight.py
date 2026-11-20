import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import Mock

from maven_dependency_preflight import preflight


class MavenDependencyPreflightTest(unittest.TestCase):
    def execute(self, responses):
        run = Mock(side_effect=[subprocess.CompletedProcess([], code, text) for code, text in responses])
        sleep = Mock()
        result = preflight(Path("backend/pom.xml"), run=run, sleep=sleep, output=io.StringIO())
        return result, run, sleep

    def test_success_still_requires_validation_with_no_test_or_audit_skip_flags(self):
        result, run, sleep = self.execute([(0, "BUILD SUCCESS")])
        self.assertEqual(result, 0)
        command = run.call_args.args[0]
        self.assertEqual(command[-1], "validate")
        self.assertIn("-U", command)
        self.assertFalse(any("skip" in value.lower() or "ignore" in value.lower() for value in command))
        self.assertEqual(run.call_count, 1)
        sleep.assert_not_called()

    def test_transient_parent_failure_is_rechecked_then_successful(self):
        result, run, sleep = self.execute([(1, "Non-resolvable parent POM: HTTP 429"), (0, "BUILD SUCCESS")])
        self.assertEqual(result, 0)
        self.assertEqual(run.call_count, 2)
        sleep.assert_called_once_with(15)
        self.assertEqual(run.call_args_list[0], run.call_args_list[1])

    def test_permanent_import_failure_remains_failure_after_three_attempts(self):
        result, run, sleep = self.execute([(1, "Non-resolvable import POM: Could not find artifact")] * 3)
        self.assertEqual(result, 1)
        self.assertEqual(run.call_count, 3)
        self.assertEqual([c.args[0] for c in sleep.call_args_list], [15, 30])

    def test_validation_or_test_failure_is_not_retried(self):
        for message in ("DependencyConvergence failed", "There are test failures", "Malformed POM"):
            with self.subTest(message=message):
                result, run, sleep = self.execute([(2, message)])
                self.assertEqual(result, 2)
                self.assertEqual(run.call_count, 1)
                sleep.assert_not_called()

    def test_process_timeout_does_not_become_success(self):
        run = Mock(side_effect=subprocess.TimeoutExpired("mvn", 300))
        with self.assertRaises(subprocess.TimeoutExpired):
            preflight(Path("backend/pom.xml"), run=run, sleep=Mock(), output=io.StringIO())


if __name__ == "__main__":
    unittest.main()
