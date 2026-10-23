#!/usr/bin/env python3
"""Explicit, one-invoice operational repair. Default: validate and ROLLBACK.

Run on the Docker host after deploying the archive lifecycle guards:
  python3 -B restore_invoice_175.py --revision <40-character-deployed-sha>
  python3 -B restore_invoice_175.py --revision <same-sha> --operator <actor> --apply

No application endpoints/providers are called. mysql runs without --force:
any guard/audit/SQL error exits the client and rolls back the open transaction.
The database credentials remain inside the existing MySQL container.
Replay is deliberately strict: any protected row change (including later
reconciliation timestamps) requires a fresh operator review, never another write.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

SQL_PATH = Path(__file__).with_suffix('.sql')
MYSQL_COMMAND = ('MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE" '
                 '--batch --raw --skip-column-names --default-character-set=utf8mb4 --skip-reconnect')
REPAIR_KEY = 'invoice-175-archive-20261023'


def financial_digest_sql() -> str:
    """Hash every stored column of the protected rows, including future columns.

    HEX makes binary/text/numeric/NULL values unambiguous without printing them.
    Only the three permitted invoice fields are excluded. No dynamic data values
    are used as SQL identifiers; names come from INFORMATION_SCHEMA and are quoted.
    """
    tables = [
        ('common_invoices', 'invoice_id', 'invoice_id=175', ('status', 'next_reminder_at', 'updated_at')),
        ('orders', 'order_id', 'order_id IN (25645,25646,25647)', ()),
        ('common_invoice_orders', 'invoice_order_id', 'invoice_id=175', ()),
        ('payment_links', 'id', 'id=7406', ()),
        ('contractor_payment_allocations', 'id', 'id=1145', ()),
        ('business_audit_events', 'event_id',
         "(order_id IN (25645,25646,25647) OR (entity_type=''common_invoice'' AND entity_id=''175'')) "
         "AND NOT(action=''common_invoice_archive_restored'' AND entity_type=''common_invoice'' AND entity_id=''175'')", ()),
    ]
    statements = ['SET SESSION group_concat_max_len=16777216;']
    for index, (table, key, predicate, excluded) in enumerate(tables):
        exclusions = ''.join(f" AND COLUMN_NAME<>'{name}'" for name in excluded)
        statements.append(f"""SELECT GROUP_CONCAT(CONCAT(QUOTE(COLUMN_NAME),',HEX(CAST(`',
 REPLACE(COLUMN_NAME,'`','``'),'` AS BINARY))') ORDER BY ORDINAL_POSITION SEPARATOR ',')
 INTO @digest_columns FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}'{exclusions};
SET @digest_query=CONCAT('SELECT SHA2(GROUP_CONCAT(JSON_OBJECT(',@digest_columns,
 ') ORDER BY `{key}` SEPARATOR 0x2c),256) INTO @digest_{index} FROM `{table}` WHERE {predicate}');
PREPARE repair_175_digest FROM @digest_query;
EXECUTE repair_175_digest;
SET @digest_warnings=@@warning_count;
DEALLOCATE PREPARE repair_175_digest;
INSERT INTO repair_175_guards VALUES ('digest_{table}',@digest_warnings=0 AND @digest_{index} IS NOT NULL);""")
    statements.append('SET @financial_digest=SHA2(CONCAT_WS(\'|\',' +
                      ','.join(f'@digest_{i}' for i in range(len(tables))) + '),256);')
    return '\n'.join(statements)


def checked(value: str, pattern: str, name: str) -> str:
    if re.fullmatch(pattern, value) is None:
        raise ValueError(f'invalid {name}')
    return value


def render_sql(*, apply: bool, revision: str, operator: str = 'dry-run', run_id: str | None = None) -> str:
    checked(revision, r'[0-9a-f]{40}', 'revision')
    checked(operator, r'[A-Za-z0-9][A-Za-z0-9_.:@-]{0,99}', 'operator')
    run_id = str(uuid.UUID(run_id)) if run_id else str(uuid.uuid4())
    template = SQL_PATH.read_text(encoding='utf-8')
    # Include both executable sources so replay cannot silently use a different plan.
    plan_hash = hashlib.sha256(template.encode() + Path(__file__).read_text(encoding='utf-8').encode()).hexdigest()
    metadata = dict(repair_key=REPAIR_KEY, run_id=run_id, revision=revision,
                    plan_sha256=plan_hash, amount_kopecks=450000, paid_kopecks=150000,
                    source_payment_link_id=7406, order_ids=[25645, 25646, 25647],
                    preserved_manual_archive_at='2026-07-30 21:31:26.321616')
    # All supplied strings have restrictive grammars; the rest is fixed JSON metadata.
    details = json.dumps(metadata, separators=(',', ':'), ensure_ascii=True)
    result = ("SELECT JSON_OBJECT('invoice_id',175,'mode','MODE','result',"
              "IF(@repair_needed=1,'RESULT','already_applied'),'guards',"
              "(SELECT COUNT(*) FROM repair_175_guards),'paid_kopecks',150000,"
              "'financial_digest_before',@before_digest,'financial_digest_after',@financial_digest);")
    if apply:
        finalize = f"""-- guard: atomic mutation and required audit
UPDATE common_invoices SET status='ARCHIVED',next_reminder_at=NULL,updated_at=CURRENT_TIMESTAMP(6)
 WHERE invoice_id=175 AND status='PARTIALLY_PAID' AND @repair_needed=1;
SET @changed_rows=ROW_COUNT();
INSERT INTO repair_175_guards VALUES ('changed_rows',@changed_rows=@repair_needed);
INSERT INTO business_audit_events
 (created_at,actor,source,action,entity_type,entity_id,order_id,review_id,old_value,new_value,details)
 SELECT CURRENT_TIMESTAMP(6),'{operator}','operational_repair','common_invoice_archive_restored',
 'common_invoice','175',NULL,NULL,'PARTIALLY_PAID','ARCHIVED',
 JSON_SET(CAST('{details}' AS JSON),'$.financial_digest',@before_digest) WHERE @repair_needed=1;
SET @audit_rows=ROW_COUNT();
INSERT INTO repair_175_guards VALUES ('required_audit',@audit_rows=@repair_needed);
{financial_digest_sql()}
INSERT INTO repair_175_guards VALUES ('financial_snapshot_unchanged',@before_digest=@financial_digest);
COMMIT;
{result.replace('MODE', 'apply').replace('RESULT', 'applied')}
"""
    else:
        finalize = result.replace('MODE', 'dry-run').replace('RESULT', 'eligible') + '\nROLLBACK;\n'
    return (template.replace('__PLAN_HASH__', f"'{plan_hash}'")
            .replace('__SNAPSHOT__', financial_digest_sql()).replace('__FINALIZE__', finalize))


def execute_sql(sql: str, container: str, *, timeout: int = 90) -> dict:
    checked(container, r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', 'container')
    completed = subprocess.run(['docker', 'exec', '-i', container, 'sh', '-c', MYSQL_COMMAND],
                               input=sql, text=True, encoding='utf-8', capture_output=True, timeout=timeout)
    if completed.returncode:
        # Error text can contain values from constraints; emit only a code and guard location.
        match = re.search(r'ERROR (\d+).*?at line (\d+)', completed.stderr)
        code, line = match.groups() if match else ('client_failure', '0')
        guard = 'connection_or_schema'
        for source_line in sql.splitlines()[:int(line)]:
            if source_line.startswith('-- guard:'):
                guard = source_line.removeprefix('-- guard:').strip()
        raise RuntimeError(f'MySQL {code}: {guard}; no successful commit confirmed')
    results = [json.loads(line) for line in completed.stdout.splitlines() if line.startswith('{')]
    if len(results) != 1 or results[0].get('invoice_id') != 175:
        raise RuntimeError('Missing final result; outcome unconfirmed. Rerun dry-run before another apply.')
    return results[0]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--container', default='my-mysql')
    parser.add_argument('--revision', required=True)
    parser.add_argument('--operator', default='dry-run')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args(argv)
    if args.apply and args.operator == 'dry-run':
        parser.error('--apply requires an explicit --operator')
    try:
        sql = render_sql(apply=args.apply, revision=args.revision, operator=args.operator)
        print(json.dumps(execute_sql(sql, args.container), ensure_ascii=False))
        return 0
    except (ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({'status': 'not_confirmed', 'error': str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
