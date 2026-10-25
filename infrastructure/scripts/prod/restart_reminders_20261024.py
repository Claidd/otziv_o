"""Owner-authorized new reminder cycle for five reviewed October 22 operations.

Default validates and rolls back. Apply only after fresh receipt/history checks
and a production backup, on the exact deployed revision. This deliberately does
not claim non-delivery: original UNKNOWN operations and payment records survive.
No provider calls. The normal workers apply business checks, time windows and
rate limits to the next notification. Replaying this command cannot restart twice.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess

REQUEST = 'owner-reminder-restart-20261024'
# Latest human conversation seen in the reviewed chats + the normal two-day gap.
# These are lower bounds, not claims that the messages confirm approval/payment.
CONTACT_COOLDOWNS = {2183632: '2026-10-25 20:59:00', 115: '2026-10-25 11:43:00'}
STATES = (
    (2168681, 26868, 'PAYMENT_REMINDER', 'wa-ffbf8176d45110b1fe6a1628593c9cb092d03f2c38eb8fce8af87c11b7c1239a'),
    (2186311, 27217, 'PAYMENT_REMINDER', 'wa-f722cdf68e26d09120a23557d4562f16ffe3efa6c13901095afd70f2802afbfc'),
    (2183632, 27922, 'REVIEW_CHECK_REMINDER', 'wa-7bf937aab94560474e3fb5dc38b2068ee625a95e177adb02d0779684800d7430'),
    (2183455, 27932, 'REVIEW_CHECK_REMINDER', 'wa-1d8b586a6bb0f7abb8e4d852ac4721dc618962eed680aeb4fec90d0b41bf6c2c'),
)
INVOICE_OPERATION = 'invoice-message:115:4107e213-a7ba-41fc-ab63-8437ec1487f5'
MYSQL_COMMAND = ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root "$MYSQL_DATABASE" '
                 '--batch --raw --skip-column-names --default-character-set=utf8mb4 --skip-reconnect')


def literal(value):
    return "CONVERT(X'" + str(value).encode('utf-8').hex() + "' USING utf8mb4)"


def render_sql(*, apply=False, operator='dry-run', evidence_hash='0' * 64, not_before, invoice_only=False):
    if not re.fullmatch(r'[A-Za-z0-9_.:@-]{1,100}', operator):
        raise ValueError('Invalid operator')
    if not re.fullmatch(r'[a-f0-9]{64}', evidence_hash):
        raise ValueError('Invalid history evidence digest')
    if apply and (operator == 'dry-run' or evidence_hash == '0' * 64):
        raise ValueError('Apply requires the authorizing operator and fresh history evidence')
    when = dt.datetime.fromisoformat(not_before)
    if when.tzinfo or when.microsecond:
        raise ValueError('Use database-local seconds for not-before')
    request = literal(REQUEST + ('-invoice' if invoice_only else ''))
    states = () if invoice_only else STATES
    reason = literal('Owner authorized a new reminder after history review; old delivery remains UNKNOWN. evidence=' + evidence_hash)
    sql = ["""SET NAMES utf8mb4;
SET SESSION innodb_lock_wait_timeout=10;
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
CREATE TEMPORARY TABLE restart_guards(name VARCHAR(80), passed TINYINT NOT NULL CHECK(passed=1));
START TRANSACTION;
SELECT order_id FROM orders WHERE order_id IN(24951,26868,27217,27922,27932) ORDER BY order_id FOR UPDATE;
SELECT id FROM payment_links WHERE order_id IN(24951,26868,27217,27922,27932) ORDER BY order_id,id FOR UPDATE;
SELECT account_id FROM common_billing_accounts WHERE account_id=(SELECT account_id FROM common_invoices WHERE invoice_id=115) FOR UPDATE;
SELECT invoice_id FROM common_invoices WHERE invoice_id=115 FOR UPDATE;
SELECT invoice_order_id FROM common_invoice_orders WHERE invoice_id=115 ORDER BY invoice_order_id FOR UPDATE;
SELECT state_id FROM scheduled_client_message_state WHERE order_id IN(24951,26868,27217,27922,27932) ORDER BY state_id FOR UPDATE;
SELECT operation_id FROM common_invoice_message_queue WHERE invoice_id=115 ORDER BY operation_id FOR UPDATE;
""", f"SELECT subject_id FROM client_reminder_restart_audit WHERE request_key={request} ORDER BY subject_type,subject_id FOR UPDATE;",
           f"SET @done=(SELECT COUNT(*) FROM client_reminder_restart_audit WHERE request_key={request});",
           "INSERT INTO restart_guards VALUES('atomic_prior_run',@done IN(0,5));",
           f"SET @restart_at={literal(when.isoformat(sep=' '))};",
           "INSERT INTO restart_guards VALUES('schedule',@done=5 OR (@restart_at>=CURRENT_TIMESTAMP(6) AND @restart_at<TIMESTAMPADD(DAY,7,CURRENT_TIMESTAMP(6))));"]
    for state_id, order_id, scenario, operation in states:
        op = literal(operation)
        statuses = "('Выставлен счет','Напоминание')" if scenario == 'PAYMENT_REMINDER' else "('На проверке')"
        sql.append(f"""INSERT INTO restart_guards SELECT 'state_{state_id}',@done=5 OR COUNT(*)=1
FROM scheduled_client_message_state s JOIN orders o ON o.order_id=s.order_id
JOIN order_statuses os ON os.order_status_id=o.order_status
WHERE s.state_id={state_id} AND s.order_id={order_id} AND s.scenario='{scenario}' AND s.state_status='ACTIVE'
 AND os.order_status_title IN {statuses} AND o.order_pay_day IS NULL AND o.order_complete=0
 AND s.dispatch_generation=0 AND s.delivery_status='UNKNOWN'
 AND s.last_error_code='state_transaction_outcome_uncertain'
 AND (s.locked_until IS NULL OR s.locked_until<CURRENT_TIMESTAMP(6))
 AND s.delivery_prepared_at<TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP(6))
 AND JSON_VALID(s.delivery_envelope)
 AND JSON_UNQUOTE(JSON_EXTRACT(s.delivery_envelope,'$.operationId'))={op}
 AND JSON_UNQUOTE(JSON_EXTRACT(s.delivery_envelope,'$.token'))=s.delivery_token
 AND JSON_UNQUOTE(JSON_EXTRACT(s.delivery_envelope,'$.targetKey'))=s.target_key
 AND NOT EXISTS(SELECT 1 FROM payment_links p WHERE p.order_id=o.order_id AND
 (p.status IN('CONFIRMED','PAID') OR COALESCE(p.confirmed_amount_kopecks,0)>0))
 AND NOT EXISTS(SELECT 1 FROM common_invoice_orders m WHERE m.order_id=o.order_id AND m.active_membership=1);
INSERT INTO restart_guards SELECT 'replay_{state_id}',@done=0 OR COUNT(*)=1 FROM client_reminder_restart_audit
 WHERE request_key={request} AND subject_type='SCHEDULED' AND subject_id={state_id} AND old_operation_id={op};""")
    sql.append(f"""INSERT INTO restart_guards SELECT 'invoice',@done=5 OR COUNT(*)=1 FROM common_invoices i
WHERE i.invoice_id=115 AND i.status='REMINDER' AND i.amount_kopecks=120000 AND i.paid_kopecks=0
 AND i.closed_at IS NULL AND i.payment_message_operation_id={literal(INVOICE_OPERATION)}
 AND i.payment_message_operation_kind='REMINDER' AND i.payment_message_confirmed=0
 AND EXISTS(SELECT 1 FROM common_invoice_orders m JOIN orders o ON o.order_id=m.order_id
 JOIN order_statuses os ON os.order_status_id=o.order_status WHERE m.invoice_id=115 AND m.order_id=24951
 AND m.active_membership=1 AND m.ready=1 AND m.paid=0 AND os.order_status_title='Напоминание' AND o.order_pay_day IS NULL)
 AND (SELECT COUNT(*) FROM common_invoice_orders WHERE invoice_id=115)=1;
INSERT INTO restart_guards SELECT 'invoice_queue',@done=5 OR (COUNT(*)=1 AND SUM(
 operation_id={literal(INVOICE_OPERATION)} AND state='UNKNOWN' AND attempts=1
 AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP(6))
 AND created_at<TIMESTAMPADD(HOUR,-24,CURRENT_TIMESTAMP(6)))=1)
 FROM common_invoice_message_queue WHERE invoice_id=115 AND state NOT IN('SENT','SUPERSEDED');
INSERT INTO restart_guards SELECT 'replay_invoice',@done=0 OR COUNT(*)=1 FROM client_reminder_restart_audit
 WHERE request_key={request} AND subject_type='COMMON_INVOICE' AND subject_id=115 AND old_operation_id={literal(INVOICE_OPERATION)};
""")
    if apply:
        for state_id, order_id, scenario, operation in states:
            due = f"GREATEST(@restart_at,{literal(CONTACT_COOLDOWNS[state_id])})" if state_id in CONTACT_COOLDOWNS else '@restart_at'
            sql.append(f"""INSERT INTO client_reminder_restart_audit
(request_key,subject_type,subject_id,old_operation_id,actor,reason,previous_snapshot,new_generation,next_attempt_at)
SELECT {request},'SCHEDULED',state_id,{literal(operation)},{literal(operator)},{reason},
 JSON_OBJECT('envelope',delivery_envelope,'token',delivery_token,'status',delivery_status,
 'prepared_at',delivery_prepared_at,'channel',delivery_channel,'last_error_code',last_error_code,
 'last_error_message',last_error_message,'sent_count',sent_count,'consecutive_failures',consecutive_failures,
 'last_success_at',last_success_at,'next_attempt_at',next_attempt_at,'generation',dispatch_generation),
 dispatch_generation+1,{due} FROM scheduled_client_message_state WHERE state_id={state_id} AND @done=0;
UPDATE scheduled_client_message_state SET dispatch_generation=dispatch_generation+1,
 consecutive_failures=0,next_attempt_at={due},last_error_code=NULL,last_error_message=NULL,locked_until=NULL,
 delivery_token=NULL,delivery_status=NULL,delivery_message=NULL,delivery_envelope=NULL,delivery_channel=NULL,
 delivery_task_id=NULL,delivery_prepared_at=NULL,delivery_recovery_checked_at=NULL,updated_at=CURRENT_TIMESTAMP(6)
 WHERE state_id={state_id} AND @done=0;
INSERT INTO restart_guards VALUES('updated_{state_id}',ROW_COUNT()=IF(@done=0,1,0));""")
        invoice_due = f"GREATEST(@restart_at,{literal(CONTACT_COOLDOWNS[115])})"
        sql.append(f"""INSERT INTO client_reminder_restart_audit
(request_key,subject_type,subject_id,old_operation_id,actor,reason,previous_snapshot,new_generation,next_attempt_at)
SELECT {request},'COMMON_INVOICE',115,q.operation_id,{literal(operator)},{reason},
 JSON_OBJECT('envelope_ciphertext',q.envelope_ciphertext,'state',q.state,'attempts',q.attempts,
 'error_code',q.error_code,'created_at',q.created_at,'next_attempt_at',q.next_attempt_at,
 'invoice_status',i.status,'amount_kopecks',i.amount_kopecks,'paid_kopecks',i.paid_kopecks,
 'invoice_last_error',i.last_error,'invoice_next_reminder',i.next_reminder_at),NULL,{invoice_due}
 FROM common_invoice_message_queue q JOIN common_invoices i ON i.invoice_id=q.invoice_id
 WHERE q.operation_id={literal(INVOICE_OPERATION)} AND @done=0;
UPDATE common_invoice_message_queue SET state='SUPERSEDED',error_code='owner_authorized_new_reminder',
 claim_token=NULL,lease_until=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE operation_id={literal(INVOICE_OPERATION)} AND @done=0;
INSERT INTO restart_guards VALUES('updated_invoice_queue',ROW_COUNT()=IF(@done=0,1,0));
UPDATE common_invoices SET payment_message_operation_id=NULL,payment_message_operation_kind=NULL,
 payment_message_confirmed=0,payment_message_channel=NULL,last_error=NULL,next_reminder_at={invoice_due},
 updated_at=CURRENT_TIMESTAMP(6) WHERE invoice_id=115 AND @done=0;
INSERT INTO restart_guards VALUES('updated_invoice',ROW_COUNT()=IF(@done=0,1,0));
COMMIT;""")
    else:
        sql.append('ROLLBACK;')
    outcome = 'applied' if apply else 'eligible'
    sql.append(f"SELECT JSON_OBJECT('request_key',{request},'result',IF(@done=5,'already_applied','{outcome}'),'next_attempt_at',@restart_at,'subjects',5);")
    result = '\n'.join(sql)
    if invoice_only:
        result = result.replace('@done IN(0,5)', '@done IN(0,1)').replace('@done=5', '@done=1').replace("'subjects',5", "'subjects',1")
    return result


def execute_sql(sql, container='my-mysql'):
    result = subprocess.run(['docker','exec','-i',container,'sh','-c',MYSQL_COMMAND],
                            input=sql,text=True,encoding='utf-8',capture_output=True,timeout=90)
    if result.returncode:
        # Do not expose snapshots, recipients, ciphertext or client text in errors.
        code = re.search(r'ERROR (\d+)', result.stderr)
        raise RuntimeError('Restart rolled back; SQL guard/error ' + (code[1] if code else 'unavailable'))
    rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
    if len(rows) != 1:
        raise RuntimeError('Restart result unconfirmed; inspect the audit before any retry')
    return rows[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision',required=True)
    parser.add_argument('--operator',default='dry-run')
    parser.add_argument('--history-evidence-sha256',default='0'*64)
    parser.add_argument('--not-before',required=True,help='Timestamp in database timezone (Asia/Irkutsk on production)')
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--invoice-only',action='store_true',help='Restore invoice 115 only; use the repair button for individual orders')
    args = parser.parse_args()
    if args.apply and not args.invoice_only:
        raise SystemExit("Use the repair button for orders; production apply requires --invoice-only")
    if not re.fullmatch('[a-f0-9]{40}',args.revision): raise SystemExit('Full deployed SHA required')
    manifest=json.loads(Path('/docker/.deploy-capacity.json').read_text())
    if manifest.get('revision') != args.revision: raise SystemExit('Deployed revision mismatch')
    sql=render_sql(apply=args.apply,operator=args.operator,evidence_hash=args.history_evidence_sha256,not_before=args.not_before,invoice_only=args.invoice_only)
    print(json.dumps(execute_sql(sql)))


if __name__ == '__main__': main()
