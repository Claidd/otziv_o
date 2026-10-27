"""Only exact historical/current reviewed MySQL transitions can be selected."""
import json
from pathlib import Path
import tempfile
import unittest

from coordinated_mysql_cutover import TRANSITIONS, selected_transition


class TransitionSelectionTest(unittest.TestCase):
    def test_each_reviewed_target_selects_only_its_separate_guarded_procedure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'infrastructure/runtime-security/reviewed-image-activations.json'
            path.parent.mkdir(parents=True)
            for reference, expected in TRANSITIONS.items():
                path.write_text(json.dumps({'images': [{'component': 'mysql', 'reference': reference}]}))
                self.assertEqual(selected_transition(directory), expected)

    def test_missing_unknown_and_duplicate_target_fail_before_import_or_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'infrastructure/runtime-security/reviewed-image-activations.json'
            path.parent.mkdir(parents=True)
            target = {'component': 'mysql', 'reference': next(iter(TRANSITIONS))}
            for images in ([], [target, target], [{'component': 'mysql', 'reference': 'mysql:latest'}]):
                path.write_text(json.dumps({'images': images}))
                with self.assertRaises(ValueError):
                    selected_transition(directory)


if __name__ == '__main__':
    unittest.main()
