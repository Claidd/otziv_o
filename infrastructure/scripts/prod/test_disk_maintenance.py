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

PREPARED = '127.0.0.1:51738/otziv-prepared/ci-'
CURRENT = 'sha256:' + 'a' * 64
PREVIOUS = 'sha256:' + 'b' * 64
LIVE_WEB = 'sha256:' + 'c' * 64


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

    @staticmethod
    def deployment(folder, live=CURRENT, rollback=(PREVIOUS,)):
        root = Path(folder)
        (root / 'docker-compose.yaml').write_text(f'services:\n  app:\n    image: ${{APP_IMAGE:-{PREPARED}backend@{live}}}\n')
        (root / '.env').write_text(f'WEB_IMAGE={PREPARED}web@{LIVE_WEB}\n')
        for index, digest in enumerate(rollback):
            point = root / '.deploy-backups' / f'release-{index}' / 'rollout-1'
            point.mkdir(parents=True)
            (point / 'docker-compose.yaml').write_text(f'image: ${{APP_IMAGE:-{PREPARED}backend@{digest}}}\n')
        return root

    def run_cleanup(self, root, images, containers=()):
        responses = ['\n'.join(item['Id'] for item in images), json.dumps(images),
                     '\n'.join('c' + str(index) for index, _ in enumerate(containers)),
                     *([json.dumps([{'Image': image} for image in containers])] if containers else []),
                     *[''] * 20]
        with patch.object(maintenance, 'command', side_effect=responses) as run:
            maintenance.remove_unreferenced_images(root)
        return [call.args[0][3] for call in run.call_args_list if call.args[0][:3] == ['docker', 'image', 'rm']]

    def test_only_old_images_without_tags_digests_or_containers_are_removed(self):
        images = [
            {'Id': 'old-cache', 'Created': '2020-01-01T00:00:00.123456789Z'},
            {'Id': 'tagged', 'Created': '2020-01-01T00:00:00Z', 'RepoTags': ['rollback:old']},
            {'Id': 'digest', 'Created': '2020-01-01T00:00:00Z', 'RepoDigests': ['rollback@sha256:abc']},
            {'Id': 'stopped', 'Created': '2020-01-01T00:00:00Z'},
            {'Id': 'new-cache', 'Created': '2099-01-01T00:00:00.5+00:00'},
        ]
        with tempfile.TemporaryDirectory() as folder:
            removed = self.run_cleanup(self.deployment(folder), images, containers=['stopped'])
        self.assertEqual(removed, ['old-cache'])

    def test_superseded_release_images_are_removed_but_live_and_rollback_images_kept(self):
        old_backend = 'sha256:' + 'd' * 64
        old_security = 'sha256:' + 'e' * 64
        images = [
            {'Id': 'running', 'Created': '2020-01-01T00:00:00Z',
             'RepoDigests': [f'{PREPARED}backend@sha256:' + '9' * 64]},
            {'Id': 'live', 'Created': '2020-01-01T00:00:00Z', 'RepoDigests': [f'{PREPARED}backend@{CURRENT}']},
            {'Id': 'live-env', 'Created': '2020-01-01T00:00:00Z',
             'RepoDigests': [f'127.0.0.1:62289/otziv-prepared/ci-web@{LIVE_WEB}']},
            {'Id': 'rollback', 'Created': '2020-01-01T00:00:00Z',
             'RepoDigests': [f'127.0.0.1:62289/otziv-prepared/ci-backend@{PREVIOUS}']},
            {'Id': 'old-backend', 'Created': '2020-01-01T00:00:00Z',
             'RepoDigests': [f'{PREPARED}backend@{old_backend}', f'127.0.0.1:49154/otziv-prepared/ci-backend@{old_backend}']},
            {'Id': 'old-security', 'Created': '2099-01-01T00:00:00Z',
             'RepoDigests': [f'ghcr.io/claidd/otziv-security@{old_security}']},
            {'Id': 'transport-registry', 'Created': '2020-01-01T00:00:00Z',
             'RepoDigests': ['registry@sha256:' + '1' * 64]},
            {'Id': 'tagged-release', 'Created': '2020-01-01T00:00:00Z', 'RepoTags': ['otziv-backend-ci:latest'],
             'RepoDigests': [f'{PREPARED}backend@sha256:' + '2' * 64]},
            {'Id': 'mixed', 'Created': '2020-01-01T00:00:00Z',
             'RepoDigests': [f'{PREPARED}backend@sha256:' + '3' * 64, 'mysql@sha256:' + '3' * 64]},
        ]
        with tempfile.TemporaryDirectory() as folder:
            removed = self.run_cleanup(self.deployment(folder), images, containers=['running'])
        self.assertEqual(removed, [f'{PREPARED}backend@{old_backend}',
                                   f'127.0.0.1:49154/otziv-prepared/ci-backend@{old_backend}',
                                   f'ghcr.io/claidd/otziv-security@{old_security}'])

    def test_large_dump_files_are_not_scanned_for_rollback_references(self):
        with tempfile.TemporaryDirectory() as folder:
            root = self.deployment(folder)
            dump = root / '.deploy-backups' / 'release-0' / 'pre-deploy.sql.gz.enc'
            dump.write_text(PREVIOUS + ' ' + 'x' * maintenance.REFERENCE_FILE_LIMIT)
            (root / '.deploy-backups' / 'release-0' / 'rollout-1' / 'docker-compose.yaml').unlink()
            self.assertNotIn(PREVIOUS, maintenance.rollback_digests(root))
            self.assertIn(CURRENT, maintenance.rollback_digests(root))

    def test_missing_live_compose_or_rollback_points_preserve_release_images(self):
        old = {'Id': 'old-backend', 'Created': '2020-01-01T00:00:00Z',
               'RepoDigests': [f'{PREPARED}backend@sha256:' + 'd' * 64]}
        with tempfile.TemporaryDirectory() as folder:
            root = self.deployment(folder)
            (root / 'docker-compose.yaml').write_text('services: {}\n')
            with self.assertRaises(RuntimeError):
                self.run_cleanup(root, [old])
        with tempfile.TemporaryDirectory() as folder:
            root = self.deployment(folder, rollback=())
            with self.assertRaises(RuntimeError):
                self.run_cleanup(root, [old])

    def test_docker_nanosecond_creation_times_parse_on_older_python(self):
        self.assertEqual(maintenance.created_at('2026-11-05T00:39:12.123456789Z').isoformat(),
                         '2026-11-05T00:39:12.123456+00:00')
        self.assertEqual(maintenance.created_at('2026-11-05T00:39:12+08:00').isoformat(),
                         '2026-11-05T00:39:12+08:00')
        with self.assertRaises(ValueError):
            maintenance.created_at('yesterday')

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
