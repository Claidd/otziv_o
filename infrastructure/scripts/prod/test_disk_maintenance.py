import argparse
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location('disk_maintenance', Path(__file__).with_name('disk_maintenance.py'))
maintenance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(maintenance)


class DiskMaintenanceTests(unittest.TestCase):
    def test_threshold_cooldown_and_emergency_escalation(self):
        state = {'lastAttemptAt': 10000, 'lastUsedPercent': 85}
        self.assertFalse(maintenance.due({'usedPercent': 79}, {}, 20000, 80, 3600))
        self.assertTrue(maintenance.due({'usedPercent': 80}, {}, 20000, 80, 3600))
        self.assertFalse(maintenance.due({'usedPercent': 89}, state, 10100, 80, 3600))
        self.assertTrue(maintenance.due({'usedPercent': 90}, state, 10100, 80, 3600))
        state['lastUsedPercent'] = 90
        self.assertTrue(maintenance.due({'usedPercent': 95}, state, 10100, 80, 3600))
        state['lastUsedPercent'] = 95
        self.assertFalse(maintenance.due({'usedPercent': 95}, state, 10100, 80, 3600))
        self.assertTrue(maintenance.due({'usedPercent': 95}, state, 13600, 80, 3600))
        self.assertTrue(maintenance.due({'usedPercent': 95}, state, 9000, 80, 3600))

    def test_mysql_keeps_existing_retention_by_default(self):
        with patch.object(maintenance, 'mysql', side_effect=['259200\t1', '0\n0\n0', '']) as sql:
            maintenance.purge_mysql('my-mysql', 0)
        self.assertIn('INTERVAL 259200 SECOND', sql.call_args.args[1])

    def test_mysql_shorter_retention_requires_explicit_option(self):
        with patch.object(maintenance, 'mysql', side_effect=['259200\t1', '0\n0\n0', '']) as sql:
            maintenance.purge_mysql('my-mysql', 24)
        self.assertIn('INTERVAL 86400 SECOND', sql.call_args.args[1])

    def test_disabled_expiration_replication_and_unknown_topology_prevent_purge(self):
        for responses in (['0\t1'], ['259200\t0'], ['259200\t1', '1\n0\n0'],
                          ['259200\t1', '0\n1\n0'], ['259200\t1', '0\n0\n1'],
                          ['259200\t1', 'unknown']):
            with self.subTest(responses=responses), patch.object(maintenance, 'mysql', side_effect=responses) as sql:
                maintenance.purge_mysql('my-mysql', 24)
                self.assertFalse(any('PURGE' in call.args[1] for call in sql.call_args_list))

    def test_mysql_failure_never_falls_back_to_deleting_files(self):
        with patch.object(maintenance, 'mysql', side_effect=RuntimeError('offline')):
            with self.assertRaises(RuntimeError):
                maintenance.purge_mysql('my-mysql', 24)

    def test_only_old_images_without_tags_digests_or_containers_are_removed(self):
        images = [
            {'Id': 'old-cache', 'Created': '2020-01-01T00:00:00Z'},
            {'Id': 'tagged', 'Created': '2020-01-01T00:00:00Z', 'RepoTags': ['rollback:old']},
            {'Id': 'digest', 'Created': '2020-01-01T00:00:00Z', 'RepoDigests': ['rollback@sha256:abc']},
            {'Id': 'stopped', 'Created': '2020-01-01T00:00:00Z'},
            {'Id': 'new-cache', 'Created': '2099-01-01T00:00:00Z'},
        ]
        with patch.object(maintenance, 'command', side_effect=[
            '\n'.join(item['Id'] for item in images), json.dumps(images), 'container1',
            json.dumps([{'Image': 'stopped'}]), '',
        ]) as run:
            maintenance.remove_unreferenced_images()
        self.assertEqual(run.call_args.args[0], ['docker', 'image', 'rm', 'old-cache'])
        self.assertEqual(run.call_count, 5)

    def test_no_more_cleanup_after_target_reached(self):
        args = argparse.Namespace(root=Path('/docker'), target_percent=75,
                                  mysql_container='my-mysql', mysql_pressure_hours=0)
        with patch.object(maintenance, 'command') as run, patch.object(maintenance, 'usage', return_value={'usedPercent': 75}):
            after, errors = maintenance.clean(args, {'usedPercent': 85})
        self.assertEqual(run.call_args.args[0], ['apt-get', 'clean'])
        self.assertEqual(run.call_count, 1)
        self.assertEqual(after['usedPercent'], 75)
        self.assertEqual(errors, [])

    def test_docker_engine_and_buildx_cache_reserve_compatibility(self):
        for flag in ('--keep-storage', '--reserved-space'):
            with self.subTest(flag=flag), patch.object(maintenance, 'command', side_effect=[flag, '']) as run:
                maintenance.prune_build_cache()
                self.assertEqual(run.call_args.args[0], ['docker', 'builder', 'prune', '--force',
                                                       '--filter', 'until=168h', flag, '1GB'])
        with patch.object(maintenance, 'command', return_value='unknown') as run:
            with self.assertRaises(RuntimeError):
                maintenance.prune_build_cache()
            self.assertEqual(run.call_count, 1)

    def test_failed_action_does_not_prevent_other_cleanup(self):
        args = argparse.Namespace(root=Path('/docker'), target_percent=75,
                                  mysql_container='my-mysql', mysql_pressure_hours=0)
        with patch.object(maintenance, 'command', side_effect=[RuntimeError('apt busy'), '']) as run, \
                patch.object(maintenance, 'usage', side_effect=[{'usedPercent': 85}, {'usedPercent': 74}]):
            after, errors = maintenance.clean(args, {'usedPercent': 85})
        self.assertEqual(run.call_count, 2)
        self.assertEqual(errors, ['package download cache'])
        self.assertEqual(after['usedPercent'], 74)

    def test_active_deployment_lock_is_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            lock = root / '.deploy.lock.d'
            lock.mkdir()
            (lock / 'owner').write_text('deployment')
            with maintenance.deploy_lock(root) as acquired:
                self.assertFalse(acquired)
            self.assertEqual((lock / 'owner').read_text(), 'deployment')

    def test_own_lock_is_released_even_on_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with self.assertRaises(RuntimeError):
                with maintenance.deploy_lock(root) as acquired:
                    self.assertTrue(acquired)
                    self.assertTrue((root / '.deploy.lock.d/owner').is_file())
                    raise RuntimeError('cleanup failed')
            self.assertFalse((root / '.deploy.lock.d').exists())

    def test_dry_run_never_invokes_external_commands_or_writes_state(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(maintenance, 'command') as run:
            state = Path(folder) / 'state'
            self.assertEqual(maintenance.main(['--root', folder, '--state-dir', str(state)]), 0)
            run.assert_not_called()
            self.assertFalse(state.exists())

    def test_state_roundtrip_and_invalid_state_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            state = Path(folder) / 'state.json'
            self.assertEqual(maintenance.read_state(state), {})
            expected = {'lastAttemptAt': 10000, 'lastUsedPercent': 95}
            maintenance.write_state(state, expected)
            self.assertEqual(maintenance.read_state(state), expected)
            for invalid in ('[]', '{"lastAttemptAt":"yesterday"}', '{"lastAttemptAt":NaN,"lastUsedPercent":95}'):
                state.write_text(invalid)
                with self.assertRaises(RuntimeError):
                    maintenance.read_state(state)

    def test_invalid_thresholds_and_too_short_retention_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            for flags in (['--trigger-percent', '101'], ['--target-percent', '90'],
                          ['--mysql-pressure-hours', '23'], ['--cooldown-seconds', '0']):
                with self.subTest(flags=flags), self.assertRaises(SystemExit):
                    maintenance.parse_args(['--root', folder, *flags])


if __name__ == '__main__':
    unittest.main()
