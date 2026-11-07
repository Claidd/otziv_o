"""Keep the production C36 -> C51 PostgreSQL refresh behind every earlier database step."""

from pathlib import Path
import unittest


DEPLOY_SCRIPT = Path(__file__).with_name('deploy-prod.ps1')
FRONT_SCRIPT = Path(__file__).resolve().parents[3] / 'deploy.ps1'
INVOCATION = 'python3 infrastructure/scripts/prod/coordinated_postgres_zlib_refresh.py'


class CoordinatedPostgresDeployOrderTest(unittest.TestCase):
    def setUp(self):
        self.source = DEPLOY_SCRIPT.read_text(encoding='utf-8').replace('\r\n', '\n')

    def section(self):
        start = self.source.index('if [ "`$coordinated_postgres_enabled" = "1" ]; then')
        end = self.source.index('\nfi\n', start)
        return start, self.source[start:end]

    def test_refresh_follows_the_backup_and_earlier_cutovers_and_precedes_the_guard(self):
        start, refresh = self.section()
        backup = self.source.index('Verified local encrypted DB backup')
        mysql = self.source.index('python3 infrastructure/scripts/prod/coordinated_mysql_cutover.py')
        ssl = self.source.rindex('python3 infrastructure/scripts/prod/coordinated_ssl_refresh.py')
        guard = self.source.index('guard_database_images\nrequire_compose_service')
        rollout = self.source.index('compose up -d --no-deps mysql keycloak-postgres')
        self.assertLess(backup, mysql)
        self.assertLess(mysql, ssl)
        self.assertLess(ssl, start)
        self.assertLess(start, guard)
        self.assertLess(guard, rollout)
        self.assertEqual(self.source.count(INVOCATION), 1)
        self.assertEqual(refresh.count(INVOCATION), 1)
        self.assertIn('--explicit-reviewed-postgres-refresh', refresh)
        self.assertIn('--lock-token "`$deploy_lock_token"', refresh)
        # The procedure alone changes keycloak-postgres; this block starts nothing else.
        self.assertNotIn('compose up', refresh)
        self.assertNotIn('docker start', refresh)

    def test_refresh_is_explicit_bundled_and_requires_protected_main_ci(self):
        self.assertIn('coordinated_postgres_enabled=$coordinatedPostgresEnabled', self.source)
        self.assertIn("$coordinatedPostgresEnabled = if ($CoordinatedPostgresRefresh) { '1' } else { '0' }", self.source)
        self.assertIn('if (($CoordinatedMysqlRefresh -or $CoordinatedSslRefresh -or $CoordinatedPostgresRefresh) '
                      '-and -not $RequireMainCi) {', self.source)
        self.assertIn('"infrastructure\\scripts\\prod\\coordinated_postgres_zlib_refresh.py",', self.source)
        self.assertIn('-CoordinatedPostgresRefresh:$CoordinatedPostgresRefresh', self.source)
        front = FRONT_SCRIPT.read_text(encoding='utf-8')
        self.assertIn('[switch]$CoordinatedPostgresRefresh', front)
        self.assertIn('-CoordinatedPostgresRefresh:$CoordinatedPostgresRefresh', front)
        self.assertIn('CoordinatedPostgresRefresh = $CoordinatedPostgresRefresh', front)


if __name__ == '__main__':
    unittest.main()
