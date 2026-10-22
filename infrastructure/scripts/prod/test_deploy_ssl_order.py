"""Keep the production C35/C36 cutover behind its PostgreSQL backup."""

from pathlib import Path
import unittest


DEPLOY_SCRIPT = Path(__file__).with_name('deploy-prod.ps1')


class CoordinatedSslDeployOrderTest(unittest.TestCase):
    def test_identity_backup_precedes_keycloak_and_postgres_changes(self):
        source = DEPLOY_SCRIPT.read_text(encoding='utf-8')
        start = source.index('if [ "`$coordinated_ssl_enabled" = "1" ]; then')
        end = source.index('# Refuse ordinary database image/storage changes', start)
        cutover = source[start:end]

        self.assertEqual(cutover.count('coordinated_ssl_refresh.py'), 2)
        prepare = cutover.index('--prepare-keycloak-cutover')
        activate = cutover.index('compose up -d --no-deps --pull never --force-recreate keycloak')
        verify_issuer = cutover.index('if [ "`$c35_expected_id" !=')
        change_postgres = cutover.rindex('coordinated_ssl_refresh.py')
        self.assertLess(prepare, activate)
        self.assertLess(activate, verify_issuer)
        self.assertLess(verify_issuer, change_postgres)
        self.assertEqual(cutover.count('C23 PostgreSQL changed'), 2)

    def test_failed_keycloak_activation_restores_c26_before_postgres_cutover(self):
        source = DEPLOY_SCRIPT.read_text(encoding='utf-8')
        start = source.index('if [ "`$coordinated_ssl_enabled" = "1" ]; then')
        end = source.index('# Refuse ordinary database image/storage changes', start)
        cutover = source[start:end]

        protect = cutover.index('c26_rollback_compose="`$backup_dir/keycloak-c26-rollback.compose.json"')
        activate = cutover.index('compose up -d --no-deps --pull never --force-recreate keycloak')
        rescue = cutover.index('compose -f "`$c26_rollback_compose" up -d --no-deps --pull never --force-recreate keycloak')
        verify = cutover.index('assert_running_service_image keycloak "`$c26_keycloak_image"')
        change_postgres = cutover.rindex('coordinated_ssl_refresh.py')
        self.assertLess(protect, activate)
        self.assertLess(activate, rescue)
        self.assertLess(rescue, verify)
        self.assertLess(verify, change_postgres)
        self.assertIn('CRITICAL: PostgreSQL identity changed; refusing an unreviewed C26 rollback.', cutover)
        self.assertIn('C26 Keycloak restored; the release remains failed and protected by its lock.', cutover)


if __name__ == '__main__':
    unittest.main()
