import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from ci_image_reuse import candidate, fingerprint, prepare
from release_ci import REPOSITORY


class ImageReuseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git('init', '-q')
        for name in ['backend/Dockerfile', 'backend/src/main/App.java', 'frontend/Dockerfile',
                     'frontend/src/app.js', 'shared/client-common/index.js',
                     'infrastructure/nginx/prod.conf', 'infrastructure/scripts/prod/deploy.py', '.dockerignore']:
            self.put(name, 'initial')
        self.first = self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), '-c', 'user.name=Test',
                                       '-c', 'user.email=test@example.invalid', *args], stderr=subprocess.DEVNULL).decode().strip()

    def put(self, name, value):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'fixture')
        return self.git('rev-parse', 'HEAD')

    def test_backend_change_preserves_web_inputs_but_changes_backend(self):
        self.put('backend/src/main/App.java', 'modified')
        second = self.commit()
        self.assertNotEqual(fingerprint(self.repo, 'backend', self.first), fingerprint(self.repo, 'backend', second))
        self.assertEqual(fingerprint(self.repo, 'web', self.first), fingerprint(self.repo, 'web', second))

    def test_shared_client_and_nginx_changes_invalidate_web(self):
        for name in ['shared/client-common/index.js', 'infrastructure/nginx/prod.conf']:
            previous = self.git('rev-parse', 'HEAD')
            self.put(name, 'modified')
            current = self.commit()
            self.assertNotEqual(fingerprint(self.repo, 'web', previous), fingerprint(self.repo, 'web', current))

    def test_policy_change_invalidates_all_components(self):
        self.put('infrastructure/scripts/prod/deploy.py', 'modified')
        current = self.commit()
        for component in ['backend', 'web', 'observer', 'whatsapp', 'worker', 'publisher']:
            self.assertNotEqual(fingerprint(self.repo, component, self.first), fingerprint(self.repo, component, current))

    def test_candidate_rejects_failed_pr_fork_and_nonancestor_sources(self):
        self.put('README.md', 'not an image input')
        current = self.commit()
        good = {'head_sha': self.first, 'head_branch': 'main', 'event': 'push', 'status': 'completed',
                'conclusion': 'success', 'path': '.github/workflows/quality-gates.yml', 'id': 123,
                'run_attempt': 1, 'repository': {'full_name': REPOSITORY}, 'head_repository': {'full_name': REPOSITORY}}
        for field, bad in [('event', 'pull_request'), ('conclusion', 'failure'),
                           ('head_branch', 'feature'), ('head_repository', {'full_name': 'other/repo'}),
                           ('path', '.github/workflows/untrusted.yml'), ('head_sha', 'f'*40)]:
            client = Mock()
            client.get.return_value = {'workflow_runs': [{**good, field: bad}]}
            with patch('ci_release.fetch_manifest') as fetch:
                self.assertIsNone(candidate(client, self.repo, 'backend', current, fingerprint(self.repo, 'backend', current)))
                fetch.assert_not_called()

    def test_changed_input_never_downloads_previous_manifest(self):
        self.put('backend/src/main/App.java', 'changed')
        current = self.commit()
        client = Mock()
        client.get.return_value = {'workflow_runs': [{'head_sha': self.first, 'head_branch': 'main', 'event': 'push',
            'status': 'completed', 'conclusion': 'success', 'path': '.github/workflows/quality-gates.yml',
            'repository': {'full_name': REPOSITORY}, 'head_repository': {'full_name': REPOSITORY}}]}
        with patch('ci_release.fetch_manifest') as fetch:
            self.assertIsNone(candidate(client, self.repo, 'backend', current, fingerprint(self.repo, 'backend', current)))
            fetch.assert_not_called()


class InstalledIdentityTest(unittest.TestCase):
    def test_classic_and_containerd_identity_bind_to_verified_oci(self):
        from ci_image_reuse import validate_installed
        from release_ci import GateError
        row={'configId':'sha256:'+'a'*64,'manifestDigest':'sha256:'+'b'*64,'layers':[{'diffId':'sha256:'+'c'*64}]}
        value={'Id':row['configId'],'Os':'linux','Architecture':'amd64','RootFS':{'Layers':[row['layers'][0]['diffId']]}}
        validate_installed(value,row)
        value.update(Id=row['manifestDigest'],Descriptor={'digest':row['manifestDigest']})
        validate_installed(value,row)
        for changed in [{'Id':'sha256:'+'d'*64},{'Descriptor':{'digest':'sha256:'+'d'*64}},{'Architecture':'arm64'},{'RootFS':{'Layers':[]}}]:
            with self.subTest(changed=changed),self.assertRaises(GateError):validate_installed({**value,**changed},row)


if __name__ == '__main__':
    unittest.main()
