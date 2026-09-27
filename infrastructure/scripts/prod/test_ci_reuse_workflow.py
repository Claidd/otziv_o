"""Guard the distinction between cached tests and fresh production/security gates."""
from pathlib import Path
import re
import unittest

WORKFLOW = Path(__file__).resolve().parents[3] / '.github/workflows/quality-gates.yml'


def job(name):
    text = WORKFLOW.read_text(encoding='utf-8')
    found = re.search(r'^  ' + name + r':\n.*?(?=^  [a-z][a-z0-9-]*:\n|\Z)', text, re.M | re.S)
    return re.split(r'(?=^      - )', found[0], flags=re.M)


class WorkflowTest(unittest.TestCase):
    def test_required_checks_are_jobs_and_audits_are_never_reused(self):
        names = ['frontend', 'mobile', 'browser-smoke', 'android-compile',
                 'whatsapp', 'external-review-worker', 'client-parity']
        for name in names:
            with self.subTest(job=name):
                parts = job(name)
                self.assertNotIn('reused', parts[0])
                resolver = next(i for i, p in enumerate(parts) if 'id: reuse' in p)
                self.assertIn('--output "$RUNNER_TEMP/check-outcome.json"', parts[resolver])
                self.assertTrue(any('name: ci-check-' in p for p in parts))
                for index, part in enumerate(parts):
                    condition = re.search(r'^        if: (.*)$', part, re.M)
                    if 'npm audit ' in part:
                        self.assertNotIn('reuse', condition[1] if condition else '')
                    if condition and "steps.reuse.outputs.reused != 'true'" in condition[1]:
                        self.assertGreater(index, resolver)
                record = next(p for p in parts if '- name: Record successful check evidence' in p)
                self.assertIn("github.event_name == 'pull_request'", record)
                self.assertIn('--outcome ', record)

    def test_reused_backend_still_packages_and_scans_current_runtime(self):
        parts = job('backend-tests')
        self.assertIn('needs: [changes, backend-reuse]', parts[0])
        package = next(p for p in parts if '-DskipTests verify' in p)
        self.assertIn("needs.backend-reuse.outputs.reused == 'true'", package)
        scan = next(p for p in parts if 'scan.mjs java ' in p)
        self.assertNotIn('reused', scan)
        java = next(p for p in parts if 'uses: actions/setup-java@' in p)
        self.assertIn('needs.backend-reuse.outputs.java_version', java)
        preparation = ''.join(job('backend-reuse'))
        # JEP 322 strings such as 26.0.2.1+1 are not setup-java input SemVer.
        # Use the action's own normalized concrete version, while attesting the
        # original release file and JDK bytes independently.
        self.assertIn('java_version: ${{ steps.java.outputs.version }}', preparation)
        self.assertIn('id: java', preparation)
        self.assertIn('show-download-progress: true', preparation)
        aggregate = job('backend')
        self.assertTrue(any('PREPARATION' in p and 'RESULT' in p for p in aggregate))
        self.assertTrue(any('--runtime-dir ' in p and '--summary ' in p for p in aggregate))

    def test_manifest_uses_final_current_attempt_outcomes_not_eligibility(self):
        text = ''.join(job('release-manifest'))
        self.assertIn('pattern: ci-check-*-attempt-${{ github.run_attempt }}', text)
        self.assertIn('ci_test_reuse.py collect', text)
        self.assertIn('--test-reuse "$RUNNER_TEMP/test-reuse/final-test-reuse.json"', text)

    def test_infrastructure_security_and_repository_gates_remain_fresh(self):
        for name in ['repository-contracts', 'issuer-security', 'integration-images',
                     'upstream-images', 'monitoring-candidates']:
            with self.subTest(job=name):
                for part in job(name):
                    if name != 'integration-images' or any(x in part for x in ('scan.mjs ', '- name: Exercise ', '- name: Check ')):
                        self.assertNotIn('steps.reuse.outputs', part)
                self.assertNotIn('needs.backend-reuse.outputs', ''.join(job(name)))

    def test_first_activation_requires_explicit_repository_setting(self):
        self.assertIn("vars.OTZIV_CI_TEST_REUSE_ACTIVE == 'true' && 'active' || 'shadow'", ''.join(job('changes')))


if __name__ == '__main__':
    unittest.main()
