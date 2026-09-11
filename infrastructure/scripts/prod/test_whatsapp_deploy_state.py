import copy
import unittest
from pathlib import Path
import os
import shutil
import subprocess
from whatsapp_deploy_state import qr_pending


class WhatsAppDeployStateTest(unittest.TestCase):
    def setUp(self):
        self.data = {'expectedClientId': 'whatsapp_vika',
                     '/ready': {'http': 503, 'body': {'state': 'qr', 'hasQr': True, 'authenticated': False,
                                                    'ready': False, 'gatewayAuthRequired': True,
                                                    'clientId': 'whatsapp_vika'}},
                     '/internal/operation-metrics': {'http': 200, 'body': {'writer': True, 'acceptingNew': True,
                         'recoveryRequired': False, 'persistenceFailures': 0, 'readFailures': 0, 'unknown': 1}},
                     '/internal/inbox-metrics': {'http': 200, 'body': {'healthy': True, 'persistenceFailures': 0,
                                                                     'historyDiscoveryFailed': False}}}

    def test_qr_wait_preserves_existing_unknown_operation(self):
        self.assertTrue(qr_pending(self.data))

    def test_authenticated_but_stuck_is_not_a_qr_wait(self):
        for key, value in [('state', 'authenticated'), ('authenticated', True), ('ready', True),
                           ('hasQr', False), ('gatewayAuthRequired', False), ('clientId', 'another-client')]:
            with self.subTest(key=key):
                data = copy.deepcopy(self.data)
                data['/ready']['body'][key] = value
                self.assertFalse(qr_pending(data))

    def test_storage_and_auth_failures_are_never_accepted(self):
        for path, key, value in [('/internal/operation-metrics', 'writer', False),
                                ('/internal/operation-metrics', 'acceptingNew', False),
                                ('/internal/operation-metrics', 'recoveryRequired', True),
                                ('/internal/operation-metrics', 'persistenceFailures', 1),
                                ('/internal/operation-metrics', 'readFailures', 1),
                                ('/internal/inbox-metrics', 'healthy', False),
                                ('/internal/inbox-metrics', 'persistenceFailures', 1),
                                ('/internal/inbox-metrics', 'historyDiscoveryFailed', True)]:
            with self.subTest(path=path, key=key):
                data = copy.deepcopy(self.data)
                data[path]['body'][key] = value
                self.assertFalse(qr_pending(data))
        for path in self.data:
            if path == 'expectedClientId':
                continue
            data = copy.deepcopy(self.data)
            data[path]['http'] = 401
            self.assertFalse(qr_pending(data))

    def test_incomplete_response_fails_closed(self):
        self.assertFalse(qr_pending({}))
        self.assertFalse(qr_pending([]))
        for path in self.data:
            data = copy.deepcopy(self.data)
            del data[path]
            self.assertFalse(qr_pending(data))


    def test_rollout_only_accepts_named_qr_waits_and_keeps_other_health_failures(self):
        source = Path(__file__).with_name('deploy-prod.ps1').read_text(encoding='utf-8')
        function = source.split('whatsapp_qr_pending=$whatsAppQrPendingQuoted', 1)[1]
        function = function.split('\nremove_service_containers()', 1)[0].replace('`$', '$')
        bash = str(Path(os.environ.get('ProgramFiles', 'C:/Program Files')) / 'Git/bin/bash.exe') if os.name == 'nt' else shutil.which('bash')
        for allow, service, probe_status, expected in [('whatsapp_vika', 'whatsapp_vika', 0, 0),
                ('', 'whatsapp_vika', 0, 1), ('whatsapp_vika', 'whatsapp_lika', 0, 1),
                ('whatsapp_vika', 'app', 0, 1), ('whatsapp_vika', 'whatsapp_vika', 1, 1)]:
            with self.subTest(allow=allow, service=service, probe=probe_status):
                stubs = """
                compose() { printf container; }
                docker() {
                  case "$*" in *State.Status*) printf running;; *State.Health*) printf unhealthy;; esac
                }
                python3() { return PROBE_STATUS; }
                """.replace('PROBE_STATUS', str(probe_status))
                script = stubs + "\nwhatsapp_qr_pending='" + allow + "'\n" + function + '\nwait_service_healthy ' + service + ' 0\n'
                result = subprocess.run([bash, '-s'], input=script, text=True, capture_output=True, timeout=10)
                self.assertEqual(expected, result.returncode, result.stdout + result.stderr)
                if expected == 0:
                    self.assertIn('delivery readiness remains unavailable', result.stdout)


if __name__ == '__main__':
    unittest.main()
