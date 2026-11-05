"""Credential encryption audit: inventory, read-only SQL and a disposable MySQL run.

The MySQL case starts the pinned production MySQL image with synthetic rows only. Enable it with
OTZIV_CREDENTIAL_AUDIT_MYSQL_TESTS=1 (needs a local Docker engine and the pinned image).
"""
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[3]
JAVA = ROOT / 'backend/src/main/java/com/hunt/otziv'
spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('credential_encryption_audit.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)

MYSQL_IMAGE = 'ghcr.io/claidd/otziv-security@sha256:0ad40a668c292ebe70297cd4df66b2e9c5067fad3bfdb54179347a597e1ac48f'


def converter_columns():
    found = set()
    for path in JAVA.rglob('*.java'):
        text = path.read_text(encoding='utf-8')
        if 'EncryptedCredentialConverter.class' not in text or 'security/credentials' in path.as_posix():
            continue
        table = re.search(r'@Table\(\s*(?:name\s*=\s*)?"([a-z0-9_]+)"', text)
        assert table, path
        for match in re.finditer(r'@Convert\(converter\s*=\s*EncryptedCredentialConverter\.class\)', text):
            column = re.search(r'@Column\(\s*name\s*=\s*"([a-z0-9_]+)"', text[match.end():match.end() + 400])
            assert column, path
            found.add((table.group(1), column.group(1)))
    return found


def backfill_columns():
    text = (JAVA / 'security/credentials/CredentialEncryptionBackfill.java').read_text(encoding='utf-8')
    return set(re.findall(r'new CredentialColumn\(\s*"([a-z0-9_]+)",\s*"[a-z0-9_]+",\s*"([a-z0-9_]+)"\s*\)', text))


def strip_calls(text, opener):
    """Remove every balanced opener(...) call from text."""
    result, index = [], 0
    while True:
        start = text.find(opener, index)
        if start < 0:
            return ''.join(result) + text[index:]
        result.append(text[index:start])
        depth, position = 0, start + len(opener) - 1
        while True:
            depth += {'(': 1, ')': -1}.get(text[position], 0)
            position += 1
            if depth == 0:
                break
        index = position


class InventoryTests(unittest.TestCase):
    def test_every_encrypted_column_in_the_code_is_counted(self):
        listed = {(c.table, c.column) for c in audit.COLUMNS}
        self.assertEqual(len(listed), len(audit.COLUMNS), 'duplicate audit column')
        self.assertLessEqual(converter_columns(), {(c.table, c.column) for c in audit.COLUMNS if c.source == 'converter'})
        self.assertEqual(backfill_columns(), {(c.table, c.column) for c in audit.COLUMNS if c.backfill})

    def test_direct_cipher_writers_are_counted(self):
        writers = [p for p in JAVA.rglob('*.java')
                   if re.search(r'cipher\.encrypt\(', p.read_text(encoding='utf-8'), re.IGNORECASE)
                   and 'security/credentials' not in p.as_posix()]
        self.assertTrue(writers)
        sources = '\n'.join(p.read_text(encoding='utf-8') for p in writers)
        sources += (JAVA / 'whatsapp/repository/WhatsAppBusinessOperationRepository.java').read_text(encoding='utf-8')
        for c in audit.COLUMNS:
            if c.source == 'envelope':
                self.assertRegex(sources, re.escape(c.table) + r'[\s\S]{0,400}' + re.escape(c.column), c)


class SqlTests(unittest.TestCase):
    def test_statements_are_read_only_and_select_only_aggregates(self):
        sql = audit.inventory_sql() + audit.audit_sql(audit.COLUMNS)
        self.assertNotRegex(sql.upper(), r'\b(UPDATE|INSERT|DELETE|REPLACE|ALTER|DROP|CREATE|GRANT|INTO OUTFILE)\b')
        self.assertIn('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;', sql)
        for statement in re.findall(r"SELECT JSON_OBJECT\('kind', 'counts'.*?;", sql):
            projection = statement.split(' FROM (SELECT ')[0]
            # Every reference to the stored value sits inside SUM(...); none is projected as is.
            self.assertNotRegex(strip_calls(projection, 'SUM('), r'\bc\b')

    def test_identifiers_outside_the_static_list_are_rejected(self):
        with self.assertRaises(ValueError):
            audit.audit_sql([audit.Column('bots`; DROP TABLE bots; --', 'x', 'converter', False)])

    def test_summary_marks_strict_mode_readiness(self):
        columns = audit.COLUMNS[:2]
        present = {(columns[0].table, columns[0].column)}
        rows = [{'kind': 'counts', 'table': columns[0].table, 'column': columns[0].column,
                 'rows': 3, 'empty': 1, 'plaintext': 1, 'envelope': 1, 'malformed': 0},
                {'kind': 'keys', 'table': columns[0].table, 'column': columns[0].column, 'key': 'primary', 'count': 1}]
        summary = audit.summarize(columns, present, rows)
        self.assertFalse(summary['strictModeReady'])
        self.assertEqual(summary['needsMigration'], [f'{columns[0].table}.{columns[0].column}'])
        self.assertEqual(summary['totals']['absentColumns'], [f'{columns[1].table}.{columns[1].column}'])
        self.assertEqual(summary['totals']['envelopeKeyIds'], ['primary'])
        with self.assertRaises(RuntimeError):
            audit.summarize(columns, present, [])


@unittest.skipUnless(os.environ.get('OTZIV_CREDENTIAL_AUDIT_MYSQL_TESTS') == '1', 'explicit disposable MySQL test')
class MySqlTests(unittest.TestCase):
    SYNTHETIC_VALUE = 'synthetic-recipient-value-7f3a'

    @classmethod
    def setUpClass(cls):
        cls.container = 'otziv-credential-audit-test-' + uuid.uuid4().hex[:10]
        subprocess.run(['docker', 'run', '--detach', '--name', cls.container, '--network', 'none',
                        '--tmpfs', '/var/lib/mysql', '--label', 'otziv.test=credential-audit',
                        '-e', 'MYSQL_ROOT_PASSWORD=disposable', '-e', 'MYSQL_DATABASE=audit_test',
                        '-e', 'MYSQL_USER=audit_app', '-e', 'MYSQL_PASSWORD=disposable-app',
                        MYSQL_IMAGE], check=True, capture_output=True)
        cls.addClassCleanup(lambda: subprocess.run(['docker', 'rm', '-f', cls.container], capture_output=True))
        for _ in range(120):
            if cls.root('SELECT 1;', check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Disposable DB unavailable')
        valid = 'enc:v1:primary:' + 'A' * 40
        old_key = 'enc:v1:k2025:' + 'B' * 60
        cls.root(f"""
CREATE TABLE bots(bot_id BIGINT PRIMARY KEY, bot_password VARCHAR(255) NOT NULL);
INSERT INTO bots VALUES (1, '{valid}'), (2, '{cls.SYNTHETIC_VALUE}'), (3, ''), (4, '{old_key}'),
  (5, 'enc:not-an-envelope'), (6, 'ENC:v1:primary:{'C' * 40}');
CREATE TABLE telephones(telephone_id BIGINT PRIMARY KEY, telephone_google_password VARCHAR(255),
  telephone_avito_password VARCHAR(255), telephone_mail_password VARCHAR(255));
INSERT INTO telephones VALUES (1, NULL, '{valid}', NULL);
CREATE TABLE common_invoice_message_queue(operation_id VARCHAR(160) PRIMARY KEY, envelope_ciphertext MEDIUMTEXT);
INSERT INTO common_invoice_message_queue VALUES ('op-1', '{{"phone":"{cls.SYNTHETIC_VALUE}"}}');
""")

    @classmethod
    def root(cls, sql, check=True):
        result = subprocess.run(['docker', 'exec', '-i', cls.container, 'sh', '-c',
                                 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root "$MYSQL_DATABASE"'],
                                input=sql, text=True, encoding='utf-8', capture_output=True, timeout=60)
        if check and result.returncode:
            raise AssertionError(result.stderr)
        return result

    def test_counts_plaintext_without_printing_stored_values(self):
        before = self.root('CHECKSUM TABLE bots, telephones, common_invoice_message_queue;').stdout
        result = subprocess.run(['python', '-B', str(Path(audit.__file__)), '--container', self.container],
                                text=True, encoding='utf-8', capture_output=True, timeout=300)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(self.SYNTHETIC_VALUE, result.stdout + result.stderr)
        self.assertNotIn('A' * 40, result.stdout)
        summary = json.loads(result.stdout)
        columns = {(c['table'], c['column']): c for c in summary['columns']}
        bots = columns[('bots', 'bot_password')]
        # ENC: in upper case is plaintext for CredentialCipher.startsWith("enc:").
        self.assertEqual({k: bots[k] for k in ('rows', 'empty', 'plaintext', 'envelope', 'malformed')},
                         {'rows': 6, 'empty': 1, 'plaintext': 2, 'envelope': 2, 'malformed': 1})
        self.assertEqual(bots['keys'], {'primary': 1, 'k2025': 1})
        self.assertEqual(columns[('telephones', 'telephone_avito_password')]['envelope'], 1)
        self.assertEqual(columns[('telephones', 'telephone_google_password')]['empty'], 1)
        self.assertEqual(columns[('common_invoice_message_queue', 'envelope_ciphertext')]['plaintext'], 1)
        self.assertFalse(columns[('payment_links', 'manual_actual_receipt_url')]['present'])
        self.assertFalse(summary['strictModeReady'])
        self.assertEqual(sorted(summary['needsMigration']),
                         ['bots.bot_password', 'common_invoice_message_queue.envelope_ciphertext'])
        self.assertEqual(self.root('CHECKSUM TABLE bots, telephones, common_invoice_message_queue;').stdout, before)


if __name__ == '__main__':
    unittest.main()
