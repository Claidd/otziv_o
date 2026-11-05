"""Read-only count of credential columns still stored without CredentialCipher encryption.

Run on the VPS from the deployment directory (/docker), as the operator who may read production:

    python3 -B infrastructure/scripts/prod/credential_encryption_audit.py

The script opens one READ ONLY transaction with the scoped application account inside the MySQL
container and prints JSON with counts per table.column and the envelope key ids in use. It never
selects stored values into its output: plaintext, ciphertext, phones, names or URLs stay in MySQL.
Pass --container for a non-default container name. --print-sql shows the exact statements.

Interpretation:
  plaintext  > 0  value does not start with "enc:"; CredentialCipher.decrypt() returns it as-is
                  even when otziv.credential-encryption.required=true (audit finding).
  malformed  > 0  starts with "enc:" but is not an "enc:v1:<key>:<payload>" envelope; decrypt()
                  throws for it, so the row is unreadable (or a plaintext beginning with "enc:").
  keys            envelope key ids; any id missing from the active/previous keys is unreadable.
  backfill        whether CredentialEncryptionBackfill re-encrypts this column at startup.
"""
import argparse
import json
import re
import subprocess
import sys
from typing import NamedTuple


class Column(NamedTuple):
    table: str
    column: str
    source: str  # converter: JPA EncryptedCredentialConverter; envelope: CredentialCipher called directly
    backfill: bool


# Keep in sync with @Convert(converter = EncryptedCredentialConverter.class), direct CredentialCipher
# writers and CredentialEncryptionBackfill.CREDENTIAL_COLUMNS; test_credential_encryption_audit.py checks it.
COLUMNS = (
    Column('telephones', 'telephone_google_password', 'converter', True),
    Column('telephones', 'telephone_avito_password', 'converter', True),
    Column('telephones', 'telephone_mail_password', 'converter', True),
    Column('bots', 'bot_password', 'converter', True),
    Column('bad_review_tasks', 'bad_review_task_bot_password_snapshot', 'converter', True),
    Column('review_recovery_tasks', 'review_recovery_task_bot_password_snapshot', 'converter', True),
    Column('archive_bad_review_tasks', 'bad_review_task_bot_password_snapshot', 'converter', True),
    Column('contractor_payment_profiles', 'recipient_name', 'converter', False),
    Column('contractor_payment_profiles', 'payment_phone', 'converter', False),
    Column('contractor_payment_profiles', 'payment_comment', 'converter', True),
    Column('contractor_payment_allocations', 'recipient_name_snapshot', 'converter', False),
    Column('contractor_payment_allocations', 'payment_phone_snapshot', 'converter', False),
    Column('contractor_payment_allocations', 'payment_comment_snapshot', 'converter', True),
    Column('contractor_actual_payment_attributions', 'original_recipient_name_snapshot', 'converter', False),
    Column('contractor_actual_payment_attributions', 'actual_recipient_name_snapshot', 'converter', False),
    Column('contractor_actual_payment_attributions', 'receipt_url', 'converter', False),
    Column('manual_payment_task_ledger_entries', 'accounting_target_label_snapshot', 'converter', False),
    Column('manual_payment_task_ledger_entries', 'manual_phone_snapshot', 'converter', False),
    Column('manual_payment_task_ledger_entries', 'bank_recipient_name_snapshot', 'converter', False),
    Column('manual_payment_task_ledger_entries', 'manual_bank_name_snapshot', 'converter', False),
    Column('manual_payment_task_ledger_entries', 'manual_payment_url_snapshot', 'converter', False),
    Column('owner_manual_card_payment_approvals', 'receipt_url', 'converter', False),
    Column('payment_links', 'manual_actual_original_recipient_name_snapshot', 'converter', False),
    Column('payment_links', 'manual_actual_recipient_name_snapshot', 'converter', False),
    Column('payment_links', 'manual_actual_receipt_url', 'converter', False),
    Column('specialist_manual_payment_reviews', 'receipt_url', 'converter', False),
    Column('client_notification_followups', 'payload_ciphertext', 'envelope', False),
    Column('review_archive_notifications', 'envelope', 'envelope', False),
    Column('common_invoice_message_queue', 'envelope_ciphertext', 'envelope', False),
    Column('manager_client_message_queue', 'envelope_ciphertext', 'envelope', False),
    Column('whatsapp_business_send_operations', 'envelope_ciphertext', 'envelope', False),
)

# CredentialCipher: "enc:v1:" + keyId [A-Za-z0-9._-]{1,64} + ":" + base64url(12-byte nonce + ciphertext + 16-byte tag).
ENVELOPE_REGEX = '^enc:v1:[A-Za-z0-9._-]{1,64}:[A-Za-z0-9_-]{39,}$'
IDENTIFIER = re.compile(r'[a-z][a-z0-9_]{0,63}')
MYSQL_COMMAND = ('MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" '
                 '--batch --raw --skip-column-names --default-character-set=utf8mb4 --skip-reconnect')
SESSION_PREFIX = """SET NAMES utf8mb4;
SET SESSION MAX_EXECUTION_TIME=120000;
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
"""


def _identifier(value):
    if not IDENTIFIER.fullmatch(value):
        raise ValueError('Unexpected SQL identifier in the static column list')
    return '`' + value + '`'


def _literal(value):
    _identifier(value)
    return "'" + value + "'"


def inventory_sql(columns=COLUMNS):
    pairs = ', '.join(f'({_literal(c.table)}, {_literal(c.column)})' for c in columns)
    return SESSION_PREFIX + (
        "SELECT JSON_OBJECT('table', TABLE_NAME, 'column', COLUMN_NAME) FROM information_schema.COLUMNS "
        f"WHERE TABLE_SCHEMA = DATABASE() AND (TABLE_NAME, COLUMN_NAME) IN ({pairs});\n"
        "COMMIT;\n"
    )


def audit_sql(columns):
    statements = [SESSION_PREFIX]
    for c in columns:
        source = f'(SELECT {_identifier(c.column)} AS c FROM {_identifier(c.table)}) AS s'
        envelope = f"REGEXP_LIKE(c, '{ENVELOPE_REGEX}', 'c')"
        prefixed = "LEFT(CAST(c AS BINARY), 4) = 'enc:'"
        statements.append(
            "SELECT JSON_OBJECT('kind', 'counts', "
            f"'table', {_literal(c.table)}, 'column', {_literal(c.column)}, "
            "'rows', COUNT(*), "
            "'empty', COALESCE(SUM(c IS NULL OR c = ''), 0), "
            f"'plaintext', COALESCE(SUM(c <> '' AND NOT ({prefixed})), 0), "
            f"'envelope', COALESCE(SUM({envelope}), 0), "
            f"'malformed', COALESCE(SUM({prefixed} AND NOT {envelope}), 0)) "
            f"FROM {source};"
        )
        statements.append(
            "SELECT JSON_OBJECT('kind', 'keys', "
            f"'table', {_literal(c.table)}, 'column', {_literal(c.column)}, 'key', k, 'count', n) FROM ("
            "SELECT SUBSTRING_INDEX(SUBSTRING_INDEX(c, ':', 3), ':', -1) AS k, COUNT(*) AS n "
            f"FROM {source} WHERE {envelope} GROUP BY k) AS g;"
        )
    statements.append('COMMIT;\n')
    return '\n'.join(statements)


def run_sql(sql, container):
    result = subprocess.run(['docker', 'exec', '-i', container, 'sh', '-c', MYSQL_COMMAND],
                            input=sql, text=True, encoding='utf-8', capture_output=True, timeout=600)
    if result.returncode:
        # The statements select no stored values, but keep MySQL diagnostics short anyway.
        code = re.search(r'ERROR (\d+)', result.stderr)
        raise RuntimeError('Read-only audit failed; MySQL error ' + (code[1] if code else 'unavailable'))
    return [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]


def summarize(columns, present, rows):
    by_column = {(c.table, c.column): {'table': c.table, 'column': c.column, 'source': c.source,
                                       'backfill': c.backfill, 'present': (c.table, c.column) in present,
                                       'keys': {}} for c in columns}
    for row in rows:
        entry = by_column[(row['table'], row['column'])]
        if row['kind'] == 'counts':
            for key in ('rows', 'empty', 'plaintext', 'envelope', 'malformed'):
                entry[key] = int(row[key])
        elif row['kind'] == 'keys':
            entry['keys'][row['key']] = int(row['count'])
    entries = list(by_column.values())
    for entry in entries:
        if entry['present'] and 'rows' not in entry:
            raise RuntimeError(f"Missing counts for {entry['table']}.{entry['column']}")
    plaintext = sum(e.get('plaintext', 0) for e in entries)
    malformed = sum(e.get('malformed', 0) for e in entries)
    keys = sorted({k for e in entries for k in e['keys']})
    needs_migration = [f"{e['table']}.{e['column']}" for e in entries if e.get('plaintext', 0) or e.get('malformed', 0)]
    return {
        'schema': 'otziv-credential-encryption-audit-v1',
        'columns': entries,
        'totals': {'plaintext': plaintext, 'malformed': malformed, 'envelopeKeyIds': keys,
                   'absentColumns': [f"{e['table']}.{e['column']}" for e in entries if not e['present']]},
        'strictModeReady': plaintext == 0 and malformed == 0,
        'needsMigration': needs_migration,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--container', default='my-mysql')
    parser.add_argument('--print-sql', action='store_true', help='Print the read-only statements and exit')
    args = parser.parse_args(argv)
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', args.container):
        raise SystemExit('Invalid container name')
    if args.print_sql:
        print(inventory_sql())
        print(audit_sql(COLUMNS))
        return 0
    present = {(r['table'], r['column']) for r in run_sql(inventory_sql(), args.container)}
    existing = [c for c in COLUMNS if (c.table, c.column) in present]
    rows = run_sql(audit_sql(existing), args.container) if existing else []
    print(json.dumps(summarize(COLUMNS, present, rows), ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
