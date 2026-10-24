"""SQL guards on a disposable local MySQL; enable OTZIV_REMINDER_MYSQL_TESTS=1."""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('restart', Path(__file__).with_name('restart_reminders_20261024.py'))
restart = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restart)


class InputTests(unittest.TestCase):
    def test_apply_requires_evidence_and_sql_values_are_not_shell_code(self):
        with self.assertRaises(ValueError):
            restart.render_sql(apply=True, not_before='2030-01-01T10:00:00')
        with self.assertRaises(ValueError):
            restart.render_sql(operator="owner'; DROP TABLE orders;--", not_before='2030-01-01T10:00:00')
        sql = restart.render_sql(not_before='2030-01-01T10:00:00')
        self.assertNotIn('UPDATE ', sql)
        self.assertIn('ROLLBACK;', sql)


@unittest.skipUnless(os.environ.get('OTZIV_REMINDER_MYSQL_TESTS') == '1', 'explicit local disposable DB test')
class MySqlTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.container = 'otziv-reminder-test-' + uuid.uuid4().hex[:10]
        subprocess.run(['docker','run','--detach','--name',cls.container,'--network','none',
                        '--tmpfs','/var/lib/mysql','--label','otziv.test=reminder-restart',
                        '-e','MYSQL_ROOT_PASSWORD=disposable','-e','MYSQL_DATABASE=restart_test',
                        'otziv-mysql:openssl-c37'],check=True,capture_output=True)
        cls.addClassCleanup(lambda: subprocess.run(['docker','rm','-f',cls.container],capture_output=True))
        for _ in range(100):
            if cls.mysql('SELECT 1;',check=False).returncode == 0: return
            time.sleep(1)
        raise RuntimeError('Disposable DB unavailable')

    @classmethod
    def mysql(cls,sql,check=True):
        r=subprocess.run(['docker','exec','-i',cls.container,'sh','-c',restart.MYSQL_COMMAND],
                         input=sql,text=True,encoding='utf-8',capture_output=True,timeout=30)
        if check and r.returncode: raise AssertionError(r.stderr)
        return r

    def setUp(self):
        tables=self.mysql('SHOW TABLES;').stdout.splitlines()
        if tables: self.mysql('DROP TABLE '+','.join('`'+t+'`' for t in tables)+';')
        self.mysql("""
CREATE TABLE order_statuses(order_status_id BIGINT PRIMARY KEY,order_status_title VARCHAR(80));
INSERT INTO order_statuses VALUES(1,'Напоминание'),(2,'Выставлен счет'),(3,'На проверке'),(4,'Оплачено');
CREATE TABLE orders(order_id BIGINT PRIMARY KEY,order_status BIGINT,order_pay_day DATE,order_complete TINYINT DEFAULT 0);
INSERT INTO orders(order_id,order_status) VALUES(24951,1),(26868,1),(27217,2),(27922,3),(27932,3);
CREATE TABLE payment_links(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(40),confirmed_amount_kopecks BIGINT);
INSERT INTO payment_links VALUES(1,26868,'WAITING_MANUAL_PAYMENT',NULL),(2,27217,'WAITING_MANUAL_PAYMENT',NULL);
CREATE TABLE common_billing_accounts(account_id BIGINT PRIMARY KEY);
INSERT INTO common_billing_accounts VALUES(7);
CREATE TABLE common_invoices(invoice_id BIGINT PRIMARY KEY,account_id BIGINT,status VARCHAR(30),amount_kopecks BIGINT,
paid_kopecks BIGINT,closed_at DATETIME(6),payment_message_operation_id VARCHAR(160),payment_message_operation_kind VARCHAR(30),
payment_message_confirmed TINYINT,payment_message_channel VARCHAR(30),last_error VARCHAR(500),next_reminder_at DATETIME(6),updated_at DATETIME(6));
CREATE TABLE common_invoice_orders(invoice_order_id BIGINT PRIMARY KEY,invoice_id BIGINT,order_id BIGINT,active_membership TINYINT,ready TINYINT,paid TINYINT);
INSERT INTO common_invoice_orders VALUES(1,115,24951,1,1,0);
CREATE TABLE scheduled_client_message_state(state_id BIGINT PRIMARY KEY,order_id BIGINT,scenario VARCHAR(60),state_status VARCHAR(30),
target_key VARCHAR(100),delivery_token VARCHAR(64),delivery_status VARCHAR(32),delivery_message TEXT,delivery_envelope MEDIUMTEXT,
delivery_channel VARCHAR(32),delivery_task_id BIGINT,delivery_prepared_at DATETIME(6),delivery_recovery_checked_at DATETIME(6),
last_error_code VARCHAR(100),last_error_message VARCHAR(1000),sent_count INT,consecutive_failures INT,
last_success_at DATETIME(6),next_attempt_at DATETIME(6),locked_until DATETIME(6),updated_at DATETIME(6));
""")
        migrations=ROOT/'backend/src/main/resources/db/migration'
        self.mysql((migrations/'V1_10_311__common_invoice_message_queue.sql').read_text())
        self.mysql((migrations/'V1_10_341__authorized_reminder_restart.sql').read_text())
        for sid,oid,scenario,op in restart.STATES:
            envelope=json.dumps({'operationId':op,'token':'token-'+str(sid),'targetKey':'order:'+str(oid)})
            self.mysql(f"""INSERT INTO scheduled_client_message_state
(state_id,order_id,scenario,state_status,target_key,delivery_token,delivery_status,delivery_envelope,
delivery_prepared_at,last_error_code,sent_count,consecutive_failures,last_success_at)
VALUES({sid},{oid},'{scenario}','ACTIVE','order:{oid}','token-{sid}','UNKNOWN',{restart.literal(envelope)},
TIMESTAMPADD(DAY,-3,NOW()),'state_transaction_outcome_uncertain',2,4,TIMESTAMPADD(DAY,-5,NOW()));""")
        op=restart.literal(restart.INVOICE_OPERATION)
        self.mysql(f"INSERT INTO common_invoices(invoice_id,account_id,status,amount_kopecks,paid_kopecks,payment_message_operation_id,payment_message_operation_kind,payment_message_confirmed) VALUES(115,7,'REMINDER',120000,0,{op},'REMINDER',0);")
        self.mysql(f"INSERT INTO common_invoice_message_queue(operation_id,invoice_id,envelope_ciphertext,state,attempts,created_at) VALUES({op},115,'enc:original','UNKNOWN',1,TIMESTAMPADD(DAY,-3,NOW()));")
        self.when=self.mysql("SELECT DATE_FORMAT(TIMESTAMPADD(HOUR,3,NOW()),'%Y-%m-%dT%H:%i:%s');").stdout.strip()

    def snapshot(self):
        return {t:self.mysql('SELECT * FROM `'+t+'` ORDER BY 1;').stdout for t in self.mysql('SHOW TABLES;').stdout.splitlines()}

    def apply(self,apply=True):
        return restart.execute_sql(restart.render_sql(apply=apply,operator='owner',evidence_hash='a'*64,not_before=self.when),self.container)

    def test_preview_apply_history_money_and_replay(self):
        before=self.snapshot()
        self.assertEqual('eligible',self.apply(False)['result'])
        self.assertEqual(before,self.snapshot())
        self.assertEqual('applied',self.apply()['result'])
        after=self.snapshot()
        for t in ('orders','payment_links','common_invoice_orders','common_billing_accounts'):
            self.assertEqual(before[t],after[t])
        self.assertEqual('5',self.mysql('SELECT COUNT(*) FROM client_reminder_restart_audit;').stdout.strip())
        self.assertEqual(['1\t2\t0\tNULL']*4,self.mysql('SELECT dispatch_generation,sent_count,consecutive_failures,delivery_envelope FROM scheduled_client_message_state ORDER BY state_id;').stdout.splitlines())
        self.assertEqual('SUPERSEDED\t1\tenc:original',self.mysql('SELECT state,attempts,envelope_ciphertext FROM common_invoice_message_queue;').stdout.strip())
        self.mysql("UPDATE scheduled_client_message_state SET sent_count=sent_count+1,delivery_status='PREPARED';")
        later=self.snapshot()
        self.assertEqual('already_applied',self.apply()['result'])
        self.assertEqual(later,self.snapshot())

    def test_changed_business_delivery_or_lease_rolls_back_everything(self):
        variants=["UPDATE orders SET order_status=4 WHERE order_id=26868;",
                  "UPDATE payment_links SET confirmed_amount_kopecks=225000 WHERE id=1;",
                  "UPDATE scheduled_client_message_state SET locked_until=TIMESTAMPADD(MINUTE,5,NOW()) WHERE state_id=2183632;",
                  "UPDATE scheduled_client_message_state SET delivery_token='changed' WHERE state_id=2183632;",
                  "UPDATE common_invoices SET paid_kopecks=120000;",
                  "UPDATE common_invoice_message_queue SET state='SENDING',lease_until=TIMESTAMPADD(MINUTE,5,NOW());",
                  "INSERT INTO common_invoice_message_queue(operation_id,invoice_id,envelope_ciphertext) VALUES('competing',115,'enc:other');"]
        for change in variants:
            with self.subTest(change=change):
                self.setUp(); self.mysql(change); before=self.snapshot()
                with self.assertRaises(RuntimeError): self.apply()
                self.assertEqual(before,self.snapshot())

    def test_audit_failure_after_state_updates_rolls_back_whole_batch(self):
        self.mysql(f"INSERT INTO client_reminder_restart_audit(request_key,subject_type,subject_id,old_operation_id,actor,reason,previous_snapshot,next_attempt_at) VALUES('other','COMMON_INVOICE',115,{restart.literal(restart.INVOICE_OPERATION)},'owner','old','old',NOW());")
        before=self.snapshot()
        with self.assertRaises(RuntimeError): self.apply()
        self.assertEqual(before,self.snapshot())

    def test_concurrent_restart_applies_only_once(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(lambda _:self.apply()['result'],range(2)))
        self.assertEqual(['already_applied','applied'],sorted(results))
        self.assertEqual('4',self.mysql('SELECT SUM(dispatch_generation) FROM scheduled_client_message_state;').stdout.strip())

    def test_invoice_only_does_not_touch_order_queues_and_is_idempotent(self):
        before = self.snapshot()
        sql = restart.render_sql(apply=True, operator='owner', evidence_hash='b'*64,
                                 not_before=self.when, invoice_only=True)
        self.assertEqual(1, restart.execute_sql(sql, self.container)['subjects'])
        after = self.snapshot()
        self.assertEqual(before['scheduled_client_message_state'], after['scheduled_client_message_state'])
        self.assertEqual(before['orders'], after['orders'])
        self.assertEqual('already_applied', restart.execute_sql(sql, self.container)['result'])
        self.assertEqual(after, self.snapshot())


if __name__=='__main__': unittest.main()
