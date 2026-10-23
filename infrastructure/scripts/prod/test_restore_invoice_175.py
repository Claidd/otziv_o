"""Fast render tests always run. Real MySQL is opt in and always disposable:

  $env:OTZIV_REPAIR175_MYSQL_TESTS='1'
  python -B -m unittest discover -s infrastructure/scripts/prod -p test_restore_invoice_175.py -v

Uses the already-reviewed local image; no production host/port or database allowed.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
from unittest.mock import patch
import uuid

SCRIPT = Path(__file__).with_name('restore_invoice_175.py')
spec = importlib.util.spec_from_file_location('repair175', SCRIPT)
repair = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repair)
REVISION = 'a' * 40


class RenderTests(unittest.TestCase):
    def test_dry_run_has_no_durable_mutation(self):
        sql = repair.render_sql(apply=False, revision=REVISION)
        self.assertNotIn('UPDATE common_invoices', sql)
        self.assertNotIn('INSERT INTO business_audit_events', sql)
        self.assertTrue(sql.rstrip().endswith('ROLLBACK;'))
        self.assertNotIn('--force', repair.MYSQL_COMMAND)
        self.assertIn('--skip-reconnect', repair.MYSQL_COMMAND)

    def test_apply_requires_audit_and_immutable_digest_before_commit(self):
        sql = repair.render_sql(apply=True, revision=REVISION, operator='test')
        self.assertLess(sql.index('UPDATE common_invoices'), sql.index('INSERT INTO business_audit_events'))
        self.assertLess(sql.index("VALUES ('required_audit'"), sql.index('COMMIT;'))
        self.assertLess(sql.index("VALUES ('financial_snapshot_unchanged'"), sql.index('COMMIT;'))
        self.assertNotIn('__', sql)

    def test_untrusted_cli_values_are_rejected(self):
        with self.assertRaises(ValueError):
            repair.render_sql(apply=True, revision=REVISION, operator="x'; COMMIT; --")
        with self.assertRaises(ValueError):
            repair.render_sql(apply=False, revision='main')

    def test_failed_mysql_does_not_claim_success_or_expose_row_values(self):
        failure = subprocess.CompletedProcess([], 1, '', "ERROR 3819 (HY000) at line 2: private row")
        with patch.object(repair.subprocess, 'run', return_value=failure):
            with self.assertRaisesRegex(RuntimeError, '3819') as result:
                repair.execute_sql('-- guard: example\nINSERT bad;', 'fixture')
            self.assertNotIn('private row', str(result.exception))


@unittest.skipUnless(os.environ.get('OTZIV_REPAIR175_MYSQL_TESTS') == '1', 'explicit disposable MySQL opt in')
class MysqlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'otziv-repair175-test-' + uuid.uuid4().hex[:12]
        image = os.environ.get('OTZIV_REPAIR175_MYSQL_IMAGE', 'otziv-mysql:openssl-c37')
        subprocess.run(['docker', 'run', '--detach', '--name', cls.container, '--network', 'none',
                        '--tmpfs', '/var/lib/mysql', '--label', 'otziv.test=repair175',
                        '-e', 'MYSQL_ROOT_PASSWORD=disposable-root', '-e', 'MYSQL_DATABASE=repair175_test',
                        '-e', 'MYSQL_USER=repair175', '-e', 'MYSQL_PASSWORD=disposable-user', image],
                       check=True, capture_output=True, text=True)
        cls.addClassCleanup(lambda: subprocess.run(['docker', 'rm', '-f', cls.container],
                                                   check=False, capture_output=True))
        until = time.monotonic() + 100
        while time.monotonic() < until:
            result = cls.mysql('SELECT 1;', check=False)
            if result.returncode == 0:
                return
            time.sleep(1)
        raise RuntimeError('Disposable MySQL did not become ready')

    @classmethod
    def mysql(cls, sql, *, check=True):
        result = subprocess.run(['docker', 'exec', '-i', cls.container, 'sh', '-c', repair.MYSQL_COMMAND],
                                input=sql, text=True, encoding='utf-8', capture_output=True, timeout=30)
        if check and result.returncode:
            raise AssertionError(result.stderr)
        return result

    def setUp(self):
        tables = self.mysql('SHOW TABLES;').stdout.splitlines()
        if tables:
            self.mysql('SET FOREIGN_KEY_CHECKS=0; DROP TABLE ' + ','.join('`' + t + '`' for t in tables) + ';')
        self.mysql(SCRIPT.with_name('restore_invoice_175_test_fixture.sql').read_text(encoding='utf-8'))

    def snapshot(self):
        snapshot = {}
        for table in self.mysql('SHOW TABLES;').stdout.splitlines():
            snapshot[table] = self.mysql(f'SELECT * FROM `{table}` ORDER BY 1;').stdout
        return snapshot

    def run_repair(self, *, apply):
        return repair.execute_sql(repair.render_sql(apply=apply, revision=REVISION, operator='test'), self.container)

    def test_dry_run_unchanged_apply_preserves_every_financial_field_and_is_idempotent(self):
        before = self.snapshot()
        dry = self.run_repair(apply=False)
        self.assertEqual('eligible', dry['result'])
        self.assertEqual(before, self.snapshot())
        result = self.run_repair(apply=True)
        self.assertEqual('applied', result['result'])
        self.assertEqual(result['financial_digest_before'], result['financial_digest_after'])
        self.assertEqual(dry['financial_digest_before'], result['financial_digest_before'])
        after = self.snapshot()
        for table in before.keys() - {'common_invoices', 'business_audit_events'}:
            self.assertEqual(before[table], after[table], table)
        self.assertEqual('ARCHIVED\tNULL', self.mysql(
            'SELECT status,next_reminder_at FROM common_invoices WHERE invoice_id=175;').stdout.strip())
        self.assertEqual('PARTIALLY_PAID\tARCHIVED', self.mysql(
            "SELECT old_value,new_value FROM business_audit_events WHERE action='common_invoice_archive_restored';").stdout.strip())
        replay = self.run_repair(apply=True)
        self.assertEqual('already_applied', replay['result'])
        self.assertEqual(after, self.snapshot())

    def test_negative_evidence_and_delivery_guards_leave_all_rows_unchanged(self):
        changes = {
            'active_child': "UPDATE orders SET order_status=3 WHERE order_id=25646;",
            'wrong_paid_amount': "UPDATE payment_links SET confirmed_amount_kopecks=140000 WHERE id=7406;",
            'wrong_source': "UPDATE common_invoice_orders SET source_payment_link_id=7407 WHERE invoice_order_id=596;",
            'refund': "UPDATE payment_links SET provider_terminal_status='REFUNDED' WHERE id=7406;",
            'lease': "UPDATE payment_links SET bank_init_nonce='active' WHERE id=7406;",
            'allocation_return': "UPDATE contractor_payment_allocations SET returned_kopecks=1 WHERE id=1145;",
            'allocation_source': "UPDATE contractor_payment_allocations SET source_id=7407 WHERE id=1145;",
            'allocation_profile': "UPDATE contractor_payment_allocations SET recipient_profile_id=16 WHERE id=1145;",
            'allocation_claim': "UPDATE contractor_payment_allocations SET reconcile_claim_token='claimed' WHERE id=1145;",
            'archive_audit': "UPDATE business_audit_events SET action='other' WHERE event_id=41615;",
            'membership': "UPDATE common_invoice_orders SET active_membership=0 WHERE invoice_order_id=594;",
            'successor': "INSERT INTO common_invoices(invoice_id,supersedes_invoice_id) VALUES(999,175);",
            'common_ref': "INSERT INTO common_invoice_payment_refs VALUES(1,175);",
            'common_queue': "INSERT INTO common_invoice_message_queue VALUES('pending',175,'UNKNOWN');",
            'return_outbox': "INSERT INTO payment_link_return_reconciliation_outbox VALUES(1,7406,'PENDING');",
            'notification': "INSERT INTO common_invoice_payment_notification_outbox VALUES(1,175);",
            'scheduled_unknown': "INSERT INTO scheduled_client_message_state(state_id,order_id,delivery_status,sent_count) VALUES(1,25645,'UNKNOWN',3);",
            'manual_unknown': "INSERT INTO manager_daily_control_concrete_items VALUES(1,'COMMON_INVOICE',175); INSERT INTO manager_client_message_queue(operation_id,card_id,state,attempts) VALUES('manual',1,'UNKNOWN',2);",
            'worker_pending': "INSERT INTO manager_daily_control_concrete_items VALUES(1,'WORKER_ORDER_NEW',25645); INSERT INTO manager_client_message_queue(operation_id,card_id,state,attempts) VALUES('worker',1,'QUEUED',0);",
            'recovery': "INSERT INTO review_recovery_tasks VALUES(1,25645,'PLANNED');",
            'unproven_archive': "UPDATE common_invoices SET status='ARCHIVED',next_reminder_at=NULL WHERE invoice_id=175;",
        }
        for name, sql in changes.items():
            with self.subTest(name=name):
                self.setUp()
                self.mysql(sql)
                before = self.snapshot()
                with self.assertRaises(RuntimeError):
                    self.run_repair(apply=True)
                self.assertEqual(before, self.snapshot())

    def test_audit_insert_failure_rolls_back_invoice_update(self):
        self.mysql("ALTER TABLE business_audit_events ADD CONSTRAINT fixture_audit_failure CHECK(action<>'common_invoice_archive_restored');")
        before = self.snapshot()
        with self.assertRaises(RuntimeError):
            self.run_repair(apply=True)
        self.assertEqual(before, self.snapshot())

    def test_replay_rejects_changed_protected_financial_history(self):
        self.run_repair(apply=True)
        self.mysql("UPDATE payment_links SET protected_note='changed source evidence' WHERE id=7406;")
        before = self.snapshot()
        with self.assertRaises(RuntimeError):
            self.run_repair(apply=True)
        self.assertEqual(before, self.snapshot())

    def test_replay_rejects_changed_later_order_audit_without_second_write(self):
        self.run_repair(apply=True)
        self.mysql("UPDATE business_audit_events SET details='changed historical evidence' WHERE event_id=84423;")
        before = self.snapshot()
        with self.assertRaises(RuntimeError):
            self.run_repair(apply=True)
        self.assertEqual(before, self.snapshot())

    def test_archive_identity_mixed_collations_are_binary_exact(self):
        sql = repair.render_sql(apply=False, revision=REVISION)
        legacy = sql.replace('CAST(entity_id AS BINARY)=CAST(CAST(order_id AS CHAR) AS BINARY)',
                             'entity_id=CAST(order_id AS CHAR)')
        with self.assertRaisesRegex(RuntimeError, '1267'):
            repair.execute_sql(legacy, self.container)
        self.assertEqual('eligible', self.run_repair(apply=False)['result'])
        self.mysql("UPDATE business_audit_events SET entity_id='025645' WHERE event_id=41615;")
        before = self.snapshot()
        with self.assertRaises(RuntimeError):
            self.run_repair(apply=True)
        self.assertEqual(before, self.snapshot())


if __name__ == '__main__':
    unittest.main()
