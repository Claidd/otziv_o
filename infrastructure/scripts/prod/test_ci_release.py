import copy
import io
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

from ci_artifacts import extract_files, storage_url, validate_artifact
from ci_image_bundle import SERVICES, export_image
from ci_release import capacity_plan, collect, fetch_image, fetch_manifest, load_backend, validate_release
from production_images import inventory, record
from release_ci import GateError
from release_preflight import probe
from test_ci_image_bundle import fixture

HEAD = 'a' * 40
SHA = 'sha256:' + 'b' * 64


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        saved, _ = fixture(self.directory)
        self.images = []
        for index, component in enumerate(SERVICES, 1):
            image = export_image(saved, component, HEAD, self.directory / component, 123, 1)
            image['artifact'] = {'id': index, 'name': f'ci-image-{component}-attempt-1', 'digest': SHA}
            self.images.append(image)
        self.release = {'schema': 'otziv-ci-release-v1', 'revision': HEAD, 'runId': 123, 'runAttempt': 1,
            'images': self.images, 'bundleBytes': 1024,
            'runtimeCapacity': {'schema': 'otziv-deploy-capacity-v2', 'revision': HEAD,
                'images': [{'reference': 'example.test/keycloak@' + SHA, 'configId': SHA,
                            'layers': self.images[0]['layers']}],
                'releaseImages': {'keycloak': 'example.test/keycloak@' + SHA}}}

    def metadata(self, image):
        return dict(image['artifact'], expired=False, size_in_bytes=1024,
                    workflow_run={'id': 123, 'head_sha': HEAD})

    def test_complete_release_retains_runtime_and_expected_enabled_images(self):
        validate_release(self.release, HEAD, 123, 1)
        plan = capacity_plan(self.release)
        self.assertEqual({'app', 'nginx', 'whatsapp', 'docker-observer', 'keycloak'}, set(plan['releaseImages']))
        self.assertEqual(5, len(plan['images']))
        self.assertIn('external-review-worker', capacity_plan(self.release, worker=True)['releaseImages'])

    def test_release_rejects_wrong_run_source_missing_component_bad_sizes_and_role(self):
        for change in ('source', 'run', 'missing', 'duplicate', 'size', 'role', 'artifact', 'attempt'):
            value = copy.deepcopy(self.release)
            if change == 'source': value['images'][0]['revision'] = 'c' * 40
            if change == 'run': value['images'][0]['runId'] += 1
            if change == 'missing': value['images'].pop()
            if change == 'duplicate': value['images'][1] = value['images'][0]
            if change == 'size': value['images'][0]['layers'][0]['unpackedBytes'] = -1
            if change == 'role': value['images'][0]['service'] = 'mysql'
            if change == 'artifact': value['images'][0]['artifact']['name'] = 'other-artifact'
            if change == 'attempt': value['images'][0]['runAttempt'] = 2
            with self.subTest(change=change), self.assertRaises((GateError, ValueError)):
                validate_release(value, HEAD, 123, 1)

    def test_artifact_wrong_source_expiry_digest_and_name_fail_before_download(self):
        image = self.images[0]
        for change in ('run', 'head', 'expiry', 'digest', 'name'):
            metadata = self.metadata(image)
            if change == 'run': metadata['workflow_run']['id'] += 1
            if change == 'head': metadata['workflow_run']['head_sha'] = 'c' * 40
            if change == 'expiry': metadata['expired'] = True
            if change == 'digest': metadata['digest'] = 'sha256:' + 'd' * 64
            if change == 'name': metadata['name'] = 'substituted'
            client = Mock()
            client.get.return_value = metadata
            with self.subTest(change=change), self.assertRaises(GateError):
                fetch_image(client, self.release, image, self.directory / 'download')
            client.download.assert_not_called()

    def test_download_binds_inner_receipt_to_manifest_and_verifies_oci(self):
        image = self.images[0]
        client = Mock()
        client.get.return_value = self.metadata(image)
        def download(metadata, destination, maximum):
            with zipfile.ZipFile(destination, 'w') as archive:
                archive.write(self.directory / image['component'] / image['archive'], image['archive'])
                archive.write(self.directory / image['component'] / (image['component'] + '.json'), image['component'] + '.json')
            return destination
        client.download.side_effect = download
        path = fetch_image(client, self.release, image, self.directory / 'received')
        self.assertEqual(image['archiveBytes'], path.stat().st_size)
        self.assertFalse((path.parent / 'download.zip').exists())

    def test_collector_reuses_successful_receipts_from_previous_partial_attempt(self):
        repo = self.directory / 'repo'
        repo.mkdir()
        (repo / 'docker-compose.yaml').write_text('services:\n  keycloak:\n    image: ${OTZIV_KEYCLOAK_IMAGE:-example.test/keycloak@' + SHA + '}\n')
        script = repo / 'infrastructure/scripts/prod/deploy-prod.ps1'
        script.parent.mkdir(parents=True)
        script.write_text('$deployBundlePaths = @(\n    "docker-compose.yaml"\n)\n')
        inputs = self.directory / 'receipts'
        inputs.mkdir()
        for row in self.images:
            (inputs / (row['component'] + '.json')).write_text(json.dumps(row))
        (inputs / 'upstream.json').write_text(json.dumps({'revision': HEAD, 'runId': 123, 'runAttempt': 1,
                                                       'image': self.release['runtimeCapacity']['images'][0]}))
        updated = copy.deepcopy(self.images[0])
        updated['runAttempt'] = 2
        updated['artifact'] = {'id': 99, 'name': 'ci-image-backend-attempt-2', 'digest': SHA}
        (inputs / 'backend-second.json').write_text(json.dumps(updated))
        with patch('subprocess.check_output', return_value=b'docker-compose.yaml\0'):
            value = collect(repo, inputs, HEAD, 123, 2)
        self.assertEqual(2, value['runAttempt'])
        self.assertEqual(2, next(row for row in value['images'] if row['component'] == 'backend')['runAttempt'])
        self.assertEqual(1, next(row for row in value['images'] if row['component'] == 'web')['runAttempt'])

    def test_production_inventory_is_recorded_only_for_matching_running_configs(self):
        observed = [{'service': 'app', 'configId': self.images[0]['configId'], 'reference': 'private-transport'}]
        self.assertEqual('backend', inventory(self.release, observed)[0]['component'])
        observed[0]['configId'] = SHA
        client = Mock()
        with self.assertRaises(GateError):
            record(client, self.release, observed)
        client.get.assert_not_called()
        with self.assertRaises(GateError):
            inventory(self.release, [{'service': 'keycloak', 'configId': SHA, 'reference': 'example.test/keycloak:mutable'}])

    def test_recovery_loads_verified_backend_from_prior_successful_attempt_without_building(self):
        row = {k: v for k, v in self.images[0].items() if k != 'artifact'}
        installed = {'Os': 'linux', 'Architecture': 'amd64', 'RootFS': {'Layers': [x['diffId'] for x in row['layers']]}}
        with patch('ci_release.subprocess.run') as run, patch('ci_release.subprocess.check_output', return_value=json.dumps([installed]).encode()):
            self.assertEqual(row, load_backend(self.directory / 'backend', HEAD, 123, 2))
        self.assertEqual(['load', 'tag'], [call.args[0][1] for call in run.call_args_list])
        for revision, run_id in [('f' * 40, 123), (HEAD, 999)]:
            with patch('ci_release.subprocess.run') as run, self.assertRaises(GateError):
                load_backend(self.directory / 'backend', revision, run_id, 2)
            run.assert_not_called()

    def test_recovery_rejects_changed_archive_before_docker(self):
        (self.directory / 'backend' / 'backend.oci.tar').write_bytes(b'changed')
        with patch('ci_release.subprocess.run') as run, self.assertRaises(ValueError):
            load_backend(self.directory / 'backend', HEAD, 123, 1)
        run.assert_not_called()


class ArchiveBoundaryTest(unittest.TestCase):
    def test_signed_storage_redirects_cannot_receive_github_credentials(self):
        self.assertEqual('https://store.blob.core.windows.net/file?sig=value',
                         storage_url('https://store.blob.core.windows.net/file?sig=value'))
        for target in ('http://store.blob.core.windows.net/file', 'https://blob.core.windows.net.attacker.test/file',
                       'https://user:secret@store.blob.core.windows.net/file', 'https://store.blob.core.windows.net:444/file',
                       'https://github.com/file', '//store.blob.core.windows.net/file'):
            with self.subTest(target=target), self.assertRaises(GateError):
                storage_url(target)

    def test_zip_traversal_duplicate_symlink_size_and_unknown_files_are_rejected(self):
        for kind in ('traversal', 'duplicate', 'symlink', 'oversized', 'extra'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / 'input.zip'
                with zipfile.ZipFile(archive, 'w') as source:
                    name = '../receipt.json' if kind == 'traversal' else 'receipt.json'
                    member = zipfile.ZipInfo(name)
                    if kind == 'symlink': member.external_attr = (stat.S_IFLNK | 0o777) << 16
                    source.writestr(member, b'x' * (11 if kind == 'oversized' else 2))
                    if kind == 'duplicate': source.writestr(name, b'{}')
                    if kind == 'extra': source.writestr('unexpected', b'{}')
                with self.assertRaises(GateError):
                    extract_files(archive, Path(temporary) / 'out', {'receipt.json': 10})

    def test_early_preflight_rejects_low_capacity_and_command_injection(self):
        plan = {'revision': HEAD}
        response = subprocess.CompletedProcess([], 0, json.dumps({'ready': True, 'capacity': {'result': 'FAIL', 'filesystems': []}}).encode(), b'')
        with patch('release_preflight.subprocess.run', return_value=response) as run:
            with self.assertRaisesRegex(GateError, 'Insufficient VPS'):
                probe('server.example', 'hunt', 22, Path('key'), Path('known'), '/docker', plan, 10)
            args = run.call_args.args[0]
            self.assertIn('StrictHostKeyChecking=yes', args)
            self.assertTrue(args[-1].startswith('sudo -n python3 -B -c '))
        with patch('release_preflight.subprocess.run') as run, self.assertRaises(GateError):
            probe('server; touch /tmp/unsafe', 'hunt', 22, Path('key'), Path('known'), '/docker')
        run.assert_not_called()

    def test_normal_deploy_orders_early_preflight_before_large_artifacts_and_retains_safety_guards(self):
        root = Path(__file__).parents[3]
        deploy = (root / 'deploy.ps1').read_text(encoding='utf-8')
        self.assertLess(deploy.index('release_preflight.py'), deploy.index('$registryHelper create'))
        self.assertLess(deploy.index('release_preflight.py'), deploy.index('$artifactHelper import'))
        self.assertIn('SkipBuildPush = $true', deploy)
        self.assertIn('RequireMainCi = $true', deploy)
        inner = (root / 'infrastructure/scripts/prod/deploy-prod.ps1').read_text(encoding='utf-8')
        for required in ('database_image_guard.py', 'create-pre-deploy-db-backup.sh', 'deployment_capacity.py', 'OTZIV_DEPLOY_COMPLETE='):
            self.assertIn(required, inner)


if __name__ == '__main__':
    unittest.main()
