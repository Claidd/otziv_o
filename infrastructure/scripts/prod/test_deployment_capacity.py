import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('capacity', HERE / 'deployment_capacity.py')
capacity = importlib.util.module_from_spec(spec); spec.loader.exec_module(capacity)
REV = 'a' * 40
REF = 'example.org/app@sha256:' + 'b' * 64
CONFIG = 'sha256:' + 'c' * 64
DIFF = 'sha256:' + 'f' * 64


def layer():
    return {'diffId': DIFF, 'compressedBytes': capacity.GIB // 4, 'unpackedBytes': capacity.GIB}


def plan():
    return {'schema': 'otziv-deploy-capacity-v2', 'revision': REV,
            'images': [{'reference': REF, 'configId': CONFIG, 'layers': [layer()]}], 'releaseImages': {'app': REF}}


class CapacityTests(unittest.TestCase):
    def evaluate(self, value=None, present=False, free=8 * capacity.GIB, distinct=False, before=True):
        return capacity.budget(value or plan(), REV, lambda ref: present,
            lambda path: (path if distinct else 'same-disk', free), '/docker', '/var/lib/docker',
            64 * 1024 ** 2, 128 * 1024 ** 2, before)

    def test_same_disk_aggregates_backup_images_and_bundle_without_double_reserving(self):
        result = self.evaluate(); row = result['filesystems'][0]
        self.assertEqual(row['requiredBytes'], capacity.GIB + 2 * capacity.GIB + capacity.GIB // 4 + 128 * 1024 ** 2 + 320 * 1024 ** 2)
        self.assertEqual(result['result'], 'PASS'); self.assertFalse(result['automaticDeletion'])

    def test_insufficient_space_and_exact_boundary(self):
        required = self.evaluate()['filesystems'][0]['requiredBytes']
        self.assertEqual(self.evaluate(free=required)['result'], 'PASS')
        self.assertEqual(self.evaluate(free=required-1)['result'], 'FAIL')

    def test_existing_exact_config_has_no_download_budget_and_no_credit_for_deleting_current_images(self):
        result = self.evaluate(present=True)
        self.assertEqual(result['missingImages'], [])
        self.assertEqual(result['filesystems'][0]['components']['imageDownloadAndUnpack'], 0)
        self.assertTrue(result['currentImagesPreserved'])

    def test_distinct_filesystems_each_keep_reserve(self):
        result = self.evaluate(distinct=True)
        self.assertEqual(len(result['filesystems']), 2)
        self.assertTrue(all(row['reserveBytes'] == capacity.GIB for row in result['filesystems']))

    def test_second_preflight_does_not_charge_already_created_backup(self):
        row = self.evaluate(before=False)['filesystems'][0]
        self.assertNotIn('encryptedDatabaseBackup', row['components'])

    def test_aliases_of_same_config_do_not_double_charge(self):
        p = plan(); p['images'].append({**p['images'][0], 'reference': 'example.org/alias@sha256:' + 'd'*64})
        self.assertEqual(self.evaluate(p)['filesystems'][0]['components']['imageDownloadAndUnpack'], 2 * capacity.GIB + capacity.GIB // 4)

    def test_only_complete_existing_prefix_reuses_a_layer_and_changed_parent_does_not(self):
        p = plan(); p['images'][0]['layers'] = [layer(), {**layer(), 'diffId': 'sha256:' + 'd' * 64}]
        def run(chains):
            return capacity.budget(p, REV, lambda ref: False, lambda path: ('disk', 8 * capacity.GIB),
                '/docker', '/store', 0, 0, False, chains)['filesystems'][0]['components']['imageDownloadAndUnpack']
        cost = 2 * capacity.GIB + capacity.GIB // 4
        self.assertEqual(run(set()), 2 * cost)
        self.assertEqual(run({DIFF}), cost)
        self.assertEqual(run(set(capacity.chain_ids([DIFF, 'sha256:' + 'd' * 64]))), 0)
        self.assertEqual(run(set(capacity.chain_ids(['sha256:' + 'e' * 64, 'sha256:' + 'd' * 64]))), 2 * cost)

    def test_unknown_revision_mutable_ref_invalid_size_and_unlisted_release_reject(self):
        for mode in ['revision','tag','negative','boolean','duplicate','missing','config']:
            p = plan()
            if mode == 'revision': p['revision'] = 'd'*40
            if mode == 'tag': p['images'][0]['reference'] = 'example.org/app:latest'
            if mode == 'negative': p['images'][0]['layers'][0]['unpackedBytes'] = -1
            if mode == 'boolean': p['images'][0]['layers'][0]['compressedBytes'] = True
            if mode == 'duplicate': p['images'].append(copy.deepcopy(p['images'][0]))
            if mode == 'missing': p['releaseImages']['app'] = 'not-in-plan'
            if mode == 'config': p['images'][0]['configId'] = 'latest'
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.evaluate(p)

    def test_tag_is_resolved_to_published_digest_and_real_platform_config(self):
        def docker(args):
            if args[1] == 'pull': return ''
            if args[1:3] == ['image','inspect']: return json.dumps([{'Size': 12345, 'RepoDigests': [REF]}])
            if args[-1] == REF: return json.dumps({'manifests': [
                {'digest':'sha256:'+'d'*64,'platform':{'os':'linux','architecture':'amd64'}},
                {'digest':'sha256:'+'e'*64,'platform':{'os':'unknown','architecture':'unknown'}}]})
            return json.dumps({'config': {'digest':CONFIG}, 'layers': [{'size': 100}]})
        with patch.object(capacity, 'run', side_effect=docker), patch.object(capacity, 'measure_archive', return_value=[layer()]):
            self.assertEqual(capacity.freeze_image('example.org/app:release'), {'reference':REF,'configId':CONFIG,'layers':[layer()]})

    def test_actual_compose_default_inventory_is_frozen_without_running_containers(self):
        root = HERE.parents[2]
        with patch.object(capacity,'freeze_image',side_effect=lambda ref: {'reference':ref,'configId':CONFIG,'layers':[layer()]}):
            value = capacity.prepare(root/'docker-compose.yaml', REV, {'app':REF})
        capacity.validate_plan(value, REV)
        self.assertGreater(len(value['images']), 8)
        self.assertIn('keycloak', value['releaseImages'])

    def test_preflight_is_before_autostart_pause_and_rollout_extraction_and_remote_build_is_removed(self):
        source = (HERE/'deploy-prod.ps1').read_text(encoding='utf-8')
        preflight = source.index('--before-backup\n')
        self.assertLess(preflight, source.index('\npause_self_heal\n'))
        second = source.index('python3 "`$capacity_check_dir/infrastructure/scripts/prod/deployment_capacity.py" check')
        self.assertLess(second, source.index('tar --warning=no-timestamp -xzf "`$bundle_path" -C "`$remote_path"'))
        self.assertNotIn('compose build whatsapp_lika', source)
        self.assertNotIn('compose build docker-observer', (HERE/'rollout-docker-observer.sh').read_text())
        self.assertIn('"infrastructure\\scripts\\prod\\image_layer_capacity.py"', source)
        self.assertIn('$dockerObserverImage = $capacityPlan.releaseImages.', source)
        self.assertIn('"infrastructure\\scripts\\prod\\deployment_capacity.py"', source)
        self.assertIn('$appImage = $capacityPlan.releaseImages.app', source)
        self.assertIn('$webImage = $capacityPlan.releaseImages.nginx', source)
        self.assertIn('"infrastructure\\runtime-security\\chromium-seccomp.json"', source)
        self.assertNotIn('"infrastructure\\runtime-security",', source)


if __name__ == '__main__': unittest.main()
