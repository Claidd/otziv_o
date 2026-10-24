"""Installed image reuse must never substitute a different CI image or restart healthy gateways."""
import copy
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import sys
from unittest.mock import patch

from ci_release import (capacity_plan, import_release, reused_references,
                        transport_references, validate_reused_references)
from deployment_capacity import validate_plan, verify_reused_images
from release_ci import GateError
from remote_release_transport import PORT, staging_required_bytes, validate_remote_plan
from selective_rollout import plan as rollout_plan
import test_ci_release as fixtures


class InstalledImageReuseTests(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.ReleaseArtifactsTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.release = fixture.release
        self.row = next(row for row in self.release['images'] if row['component'] == 'whatsapp')
        self.ref = transport_references(self.release, 52345)['whatsapp']
        self.inventory = {'schema': 'otziv-server-preflight-v1', 'ready': True, 'images': [
            {'service': service, 'healthy': True, 'configId': self.row['configId'], 'reference': self.ref}
            for service in ('whatsapp_lika', 'whatsapp_vika')]}

    def test_identical_gateway_pair_skips_download_and_upload_and_preserves_reference(self):
        reused = reused_references(self.release, self.inventory)
        self.assertEqual({'whatsapp': self.ref}, reused)
        refs = transport_references(self.release, 54001)
        output = io.StringIO()
        with contextlib.redirect_stdout(output), patch('ci_release.fetch_image', return_value=Path('verified.oci.tar')) as fetch, \
             patch('ci_release.upload_ci_image', side_effect=lambda record, archive, row: refs[row['component']]) as upload:
            result = import_release(object(), self.release, {}, Path('unused'), reused=reused)
        self.assertEqual('', output.getvalue(), 'Direct transport stdout must contain only its JSON reply')
        self.assertNotIn('whatsapp', [call.args[2]['component'] for call in fetch.call_args_list])
        self.assertNotIn('whatsapp', [call.args[2]['component'] for call in upload.call_args_list])
        self.assertEqual({'backend', 'web', 'observer'},
                         {call.args[2]['component'] for call in fetch.call_args_list})
        self.assertEqual(self.ref, result['releaseImages']['whatsapp'])
        self.assertEqual([self.ref], result['reusedImages'])
        validate_plan(result, self.release['revision'])

    def test_local_cli_persists_mixed_proof_and_rejects_tampered_plan(self):
        import ci_release
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root/'release.json'
            evidence = root/'preflight.json'
            registry = root/'registry.json'
            output = root/'capacity.json'
            verified = root/'verified.json'
            manifest.write_text(json.dumps(self.release))
            evidence.write_text(json.dumps(self.inventory))
            registry.write_text(json.dumps({'port': 54001, 'state': 'staging'}))
            refs = transport_references(self.release, 54001)
            args = ['ci_release.py', 'import', '--input', str(manifest), '--output', str(output),
                    '--registry', str(registry), '--preflight', str(evidence)]
            with patch.object(sys, 'argv', args), patch('ci_release.Client'), \
                 patch('ci_release.fetch_image', return_value=Path('verified.oci.tar')), \
                 patch('ci_release.upload_ci_image', side_effect=lambda record, archive, row: refs[row['component']]):
                ci_release.main()
            record = json.loads(registry.read_text())
            self.assertEqual({'whatsapp': self.ref}, record['reusedImages'])
            record['state'] = 'readonly'
            registry.write_text(json.dumps(record))
            args = ['ci_release.py', 'validate-plan', '--input', str(output), '--output', str(verified),
                    '--manifest', str(manifest), '--registry', str(registry), '--revision', self.release['revision']]
            with patch.object(sys, 'argv', args), patch('release_registry.owned'):
                ci_release.main()
                self.assertEqual(json.loads(output.read_text()), json.loads(verified.read_text()))
                invalid = json.loads(output.read_text())
                invalid['reusedImages'] = []
                output.write_text(json.dumps(invalid))
                with self.assertRaises(GateError):
                    ci_release.main()

    def test_missing_duplicate_unhealthy_different_config_or_manifest_require_transfer(self):
        for defect in ('missing', 'duplicate', 'unhealthy', 'config', 'digest', 'mixed_ref', 'mutable', 'foreign'):
            evidence = copy.deepcopy(self.inventory)
            rows = evidence['images']
            if defect == 'missing': rows.pop()
            if defect == 'duplicate': rows.append(copy.deepcopy(rows[0]))
            if defect == 'unhealthy': rows[0]['healthy'] = False
            if defect == 'config': rows[0]['configId'] = 'sha256:' + 'f' * 64
            if defect == 'digest':
                for row in rows: row['reference'] = self.ref.split('@')[0] + '@sha256:' + 'f' * 64
            if defect == 'mixed_ref': rows[0]['reference'] = self.ref.replace(':52345/', ':52346/')
            if defect == 'mutable':
                for row in rows: row['reference'] = 'otziv-whatsapp:latest'
            if defect == 'foreign':
                for row in rows: row['reference'] = self.ref.replace('127.0.0.1', 'other.example')
            with self.subTest(defect=defect):
                self.assertEqual({}, reused_references(self.release, evidence))

    def test_explicit_reuse_mapping_rejects_injection_wrong_component_and_wrong_digest(self):
        for ref in (';echo nope', self.ref + '\n', self.ref.replace(':52345/', ':99999/'),
                    self.ref.replace('/ci-whatsapp@', '/ci-backend@'),
                    self.ref.split('@')[0] + '@sha256:' + 'f' * 64):
            with self.subTest(reference=ref), self.assertRaises(GateError):
                validate_reused_references(self.release, {'whatsapp': ref})
        with self.assertRaises(GateError):
            validate_reused_references(self.release, {'mysql': self.ref})

    def test_direct_staging_excludes_only_verified_reused_archive_and_keeps_reserve(self):
        reserve = 5 * 1024**3
        full = staging_required_bytes(self.release, reserve)
        reused = staging_required_bytes(self.release, reserve, reused={'whatsapp': self.ref})
        self.assertEqual(self.row['archiveBytes'] * 3, full - reused)
        self.assertGreaterEqual(reused, reserve + 1024**3)

    def test_direct_mixed_plan_requires_identical_remote_proof(self):
        reused = {'whatsapp': self.ref}
        expected = capacity_plan(self.release, transport_references(self.release, PORT, reused), reused=reused)
        record = {'owner': 'a'*32, 'reusedImages': reused}
        status = {'registry': {'owner': record['owner'], 'port': PORT, 'state': 'readonly'}, 'capacity': expected}
        with patch('remote_release_transport.invoke', return_value=status):
            self.assertEqual(expected, validate_remote_plan(record, self.release, expected))
            tampered = copy.deepcopy(expected)
            tampered['releaseImages']['whatsapp'] = transport_references(self.release, PORT)['whatsapp']
            with self.assertRaises(GateError):
                validate_remote_plan(record, self.release, tampered)
            with self.assertRaises(GateError):
                validate_remote_plan({'owner': record['owner']}, self.release, expected)

    def test_reuse_is_revalidated_on_vps_by_reference_config_platform_and_layers(self):
        reused = {'whatsapp': self.ref}
        value = capacity_plan(self.release, transport_references(self.release, PORT, reused), reused=reused)
        installed = {'Id': self.row['configId'], 'Os': 'linux', 'Architecture': 'amd64',
                     'RepoDigests': [self.ref], 'RootFS': {'Layers': [l['diffId'] for l in self.row['layers']]}}
        with patch('deployment_capacity.run', return_value=json.dumps([installed])) as run:
            verify_reused_images(value)
            run.assert_called_once_with(['docker', 'image', 'inspect', self.ref])
        for defect in ('id', 'platform', 'layers', 'reference', 'missing'):
            image = copy.deepcopy(installed)
            if defect == 'id': image['Id'] = 'sha256:' + 'f'*64
            if defect == 'platform': image['Architecture'] = 'arm64'
            if defect == 'layers': image['RootFS']['Layers'] = []
            if defect == 'reference': image['RepoDigests'] = []
            with self.subTest(defect=defect), \
                 patch('deployment_capacity.run', return_value=json.dumps([] if defect == 'missing' else [image])), \
                 self.assertRaises(ValueError):
                verify_reused_images(value)

    def test_helper_only_changes_retain_both_gateways_but_runtime_changes_do_not(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            desired = {name: {'image': self.ref, 'security_opt': ['seccomp=policy.json']}
                       for name in ('whatsapp_lika', 'whatsapp_vika')}
            actual = [{'State': {'Status': 'running', 'Health': {'Status': 'healthy'}},
                       'Config': {'Image': self.ref, 'Labels': {'com.docker.compose.service': name,
                                   'com.docker.compose.config-hash': 'same'}}}
                      for name in desired]
            def command(args):
                if args[-2:] == ['--format', 'json']: return json.dumps({'services': desired})
                if args[-2:] == ['--hash', '*']: return '\n'.join(name + ' same' for name in desired)
                if args[0:2] == ['docker', 'inspect']: return json.dumps(actual)
                return 'container1 container2'
            with patch('selective_rollout.command', side_effect=command):
                result = rollout_plan(root, '.env', ['infrastructure/scripts/prod/ci_release.py'])
                self.assertFalse(result['full'])
                self.assertEqual({'retain'}, set(result['services'].values()))
                self.assertEqual({'recreate'}, set(rollout_plan(root, '.env', ['policy.json'])['services'].values()))
                self.assertEqual({'recreate'}, set(rollout_plan(root, '.env', [], full=True)['services'].values()))
                actual[1]['Config']['Labels']['com.docker.compose.config-hash'] = 'old-settings'
                result = rollout_plan(root, '.env', [])
                self.assertEqual('retain', result['services']['whatsapp_lika'])
                self.assertEqual('recreate', result['services']['whatsapp_vika'])


if __name__ == '__main__':
    unittest.main()
