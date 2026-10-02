"""Offline archive recovery guards; never contact S3 or read production env."""
import base64
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
from unittest import mock

import retained_artifacts as r


class ArchiveTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.key = bytes(range(32))
        self.source = self.path / 'plain'
        self.source.write_bytes(b'artifact bytes\n' * 10000)

    def test_multichunk_encryption_and_recovery(self):
        self.source.write_bytes(b'x' * (r.CHUNK + 31))
        r.encrypt(self.source, self.path / 'encrypted', self.key)
        r.decrypt(self.path / 'encrypted', self.path / 'restored', self.key)
        self.assertEqual(r.sha(self.source), r.sha(self.path / 'restored'))

    def test_existing_java_otzivdb2_envelope_is_readable(self):
        # Generated independently with JDK 25 Cipher AES/GCM/NoPadding using
        # DatabaseBackupService's exact header, nonce and AAD serialization.
        vector = 'T1RaSVZEQjIAAQAAAAAAAAAAADIAAQIDBAUGB2ZQDC0kIzlD9oyV+QvwHITPtSg2nxILlkCmUsHR+MAHh8b2kzVc+tgtYIrw7fdmVvhXWT/NG6H7PSmNqeFUiNVEuA=='
        (self.path / 'java.enc').write_bytes(base64.b64decode(vector))
        r.decrypt(self.path / 'java.enc', self.path / 'restored', self.key)
        self.assertEqual((self.path / 'restored').read_bytes(), b'OTZIV retained artifact Java compatibility vector\n')

    def test_ciphertext_header_tag_truncation_and_wrong_key_fail_without_output(self):
        r.encrypt(self.source, self.path / 'encrypted', self.key)
        data = (self.path / 'encrypted').read_bytes()
        cases = [data[:-1], data + b'x']
        for position in (0, 10, 25, r.HEADER.size + 5, len(data) - 1):
            changed = bytearray(data)
            changed[position] ^= 1
            cases.append(changed)
        for value in cases:
            with self.subTest(kind=len(value), head=bytes(value[:12])):
                (self.path / 'corrupt').write_bytes(value)
                with self.assertRaises(Exception):
                    r.decrypt(self.path / 'corrupt', self.path / 'restored', self.key)
                self.assertFalse((self.path / 'restored').exists())
        with self.assertRaises(Exception):
            r.decrypt(self.path / 'encrypted', self.path / 'restored', b'Z' * 32)
        self.assertFalse((self.path / 'restored').exists())

    def test_existing_output_is_never_changed_or_removed(self):
        r.encrypt(self.source, self.path / 'encrypted', self.key)
        for function, source in ((r.encrypt, self.source), (r.decrypt, self.path / 'encrypted')):
            with self.assertRaises(FileExistsError):
                function(source, self.source, self.key)
            self.assertEqual(self.source.read_bytes(), b'artifact bytes\n' * 10000)

    def test_unsafe_and_windows_ambiguous_paths(self):
        for value in ('../x', '/x', 'a/../x', 'C:/x', 'a\\x', 'a//x', 'a/./x', 'a/NUL.png', 'a/COM1', 'a/file.', 'a/file '):
            with self.subTest(path=value):
                self.assertFalse(r.safe_path(value))
        self.assertTrue(r.safe_path('generated-assets/images/\u043e\u0431\u044b\u0447\u043d\u044b\u0439.png'))

    def manifest_for(self, entries):
        archive = self.path / 'data.tar'
        with tarfile.open(archive, 'w') as tar:
            for name, data, type_value in entries:
                item = tarfile.TarInfo(name)
                item.mode, item.size, item.type = 0o644, len(data), type_value
                tar.addfile(item, io.BytesIO(data))
        f = {'path': 'generated-assets/x', 'mode': '100644', 'gitBlob': '1' * 40, 'bytes': 1, 'sha256': __import__('hashlib').sha256(b'x').hexdigest()}
        return archive, {'schema': r.SCHEMA, 'revision': '2' * 40, 'files': [f], 'bundleSha256': r.sha(archive), 'bundleBytes': archive.stat().st_size}

    def test_tar_exact_bytes_roundtrip(self):
        archive, manifest = self.manifest_for([('generated-assets/x', b'x', tarfile.REGTYPE)])
        output = self.path / 'restored'
        output.mkdir()
        r.verify_tar(archive, manifest, output)
        self.assertEqual((output / 'generated-assets/x').read_bytes(), b'x')

    def test_duplicate_symlink_missing_extra_and_corrupt_archive_rejected(self):
        for entries in [[], [('generated-assets/x', b'x', tarfile.SYMTYPE)], [('generated-assets/x', b'x', tarfile.REGTYPE)] * 2, [('extra', b'x', tarfile.REGTYPE)], [('generated-assets/x', b'z', tarfile.REGTYPE)]]:
            with self.subTest(entries=entries):
                archive, manifest = self.manifest_for(entries)
                with self.assertRaises(ValueError):
                    r.verify_tar(archive, manifest)

    def test_manifest_case_collisions_rejected(self):
        _, manifest = self.manifest_for([('generated-assets/x', b'x', tarfile.REGTYPE)])
        manifest['files'].append({**manifest['files'][0], 'path': 'generated-assets/X'})
        p = self.path / 'manifest.json'
        p.write_text(json.dumps(manifest))
        with self.assertRaises(ValueError):
            r.load_manifest(p)

    def test_offline_migration_requires_matching_complete_remote_restore(self):
        _, manifest = self.manifest_for([('generated-assets/x', b'x', tarfile.REGTYPE)])
        r.write_json(self.path / 'manifest.json', manifest)
        receipt = {'schema': r.SCHEMA, 'manifestSha256': r.sha(self.path / 'manifest.json'), 'bundleSha256': manifest['bundleSha256'], 'allFilesVerified': 1, 'authenticatedEnvelopeVerified': True, 'versionBoundDownloadVerified': True, 'aclVerifiedPrivate': True, 'anonymousDownloadDenied': True, 'lifecycle': 'NO_CONFIGURATION', 'objectKey': 'backup/fixture/retained-artifacts/v1/x', 'versionId': 'exact-version', 'ciphertextSha256': '3' * 64}
        proof = {**receipt, 'allFileHashesVerified': True, 'verifiedFiles': 1, 'verifiedBytes': 1, 'machineRole': 'production-vps-isolated-recovery', 'usedPreexistingRemoteKey': True}
        r.write_json(self.path / 'storage-receipt.json', receipt)
        target = self.path / 'second-machine-restore.json'
        r.write_json(target, proof)
        self.assertEqual(r.verify_migration_records(self.path)['files'], {'generated-assets/x': '1' * 40})
        for key, value in [('versionId', 'different'), ('manifestSha256', '4' * 64), ('verifiedFiles', 0), ('verifiedBytes', 0), ('allFileHashesVerified', False), ('usedPreexistingRemoteKey', False), ('machineRole', 'same-workstation'), ('authenticatedEnvelopeVerified', False)]:
            target.write_text(json.dumps({**proof, key: value}))
            with self.subTest(field=key), self.assertRaises(ValueError):
                r.verify_migration_records(self.path)

    def test_actual_hygiene_accepts_archived_deletion_and_rejects_missing_proof_new_binary(self):
        import shutil
        import subprocess
        # All Git writes belong to this disposable fixture, never the checkout.
        root = Path(__file__).resolve().parents[3]
        def git(*args):
            return subprocess.check_output(['git', '-C', str(self.path), *args], stderr=subprocess.STDOUT, text=True).strip()
        git('init', '--quiet', '--initial-branch', 'fixture')
        git('config', 'user.name', 'Artifact recovery fixture')
        git('config', 'user.email', 'fixture@example.invalid')
        git('config', 'commit.gpgsign', 'false')
        git('config', 'core.hooksPath', str(self.path / 'unused-fixture-hooks'))
        media = self.path / 'generated-assets/x.png'
        media.parent.mkdir()
        media.write_bytes(b'x')
        git('add', 'generated-assets/x.png')
        git('commit', '--quiet', '-m', 'Retained fixture')
        base = git('rev-parse', 'HEAD')
        blob = git('rev-parse', 'HEAD:generated-assets/x.png')
        records = self.path / 'infrastructure/artifact-recovery'
        records.mkdir(parents=True)
        manifest = {'schema': r.SCHEMA, 'revision': base, 'bundleSha256': '2' * 64, 'bundleBytes': 10240, 'files': [{'path': 'generated-assets/x.png', 'mode': '100644', 'gitBlob': blob, 'bytes': 1, 'sha256': __import__('hashlib').sha256(b'x').hexdigest()}]}
        r.write_json(records / 'manifest.json', manifest)
        receipt = {'schema': r.SCHEMA, 'manifestSha256': r.sha(records / 'manifest.json'), 'bundleSha256': manifest['bundleSha256'], 'allFilesVerified': 1, 'authenticatedEnvelopeVerified': True, 'versionBoundDownloadVerified': True, 'aclVerifiedPrivate': True, 'anonymousDownloadDenied': True, 'lifecycle': 'NO_CONFIGURATION', 'objectKey': 'backup/fixture/retained-artifacts/v1/x', 'versionId': 'exact-version', 'ciphertextSha256': '3' * 64}
        proof = {**receipt, 'allFileHashesVerified': True, 'verifiedFiles': 1, 'verifiedBytes': 1, 'machineRole': 'production-vps-isolated-recovery', 'usedPreexistingRemoteKey': True}
        r.write_json(records / 'storage-receipt.json', receipt)
        r.write_json(records / 'second-machine-restore.json', proof)
        for name in ('infrastructure/scripts/prod/retained_artifacts.py', 'infrastructure/scripts/security/check-repository-hygiene.ps1'):
            target = self.path / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / name, target)
        git('rm', '--quiet', 'generated-assets/x.png')
        git('add', 'infrastructure')
        git('commit', '--quiet', '-m', 'Verified archive fixture')
        command = ['pwsh', '-NoProfile', '-File', str(self.path / 'infrastructure/scripts/security/check-repository-hygiene.ps1'), '-BaseRevision', base]
        def check():
            return subprocess.run(command, cwd=self.path, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        result = check()
        self.assertEqual(result.returncode, 0, result.stdout)
        proof_path = records / 'second-machine-restore.json'
        proof_path.unlink()
        self.assertNotEqual(check().returncode, 0)

        r.write_json(proof_path, proof)
        manifest['files'][0]['gitBlob'] = '1' * 40
        (records / 'manifest.json').write_text(json.dumps(manifest))
        for name, value in [('storage-receipt.json', receipt), ('second-machine-restore.json', proof)]:
            (records / name).write_text(json.dumps({**value, 'manifestSha256': r.sha(records / 'manifest.json')}))
        self.assertNotEqual(check().returncode, 0)
        manifest['files'][0]['gitBlob'] = blob
        (records / 'manifest.json').write_text(json.dumps(manifest))
        for name, value in [('storage-receipt.json', receipt), ('second-machine-restore.json', proof)]:
            (records / name).write_text(json.dumps({**value, 'manifestSha256': r.sha(records / 'manifest.json')}))
        media.parent.mkdir(exist_ok=True)
        media.write_bytes(b'new binary')
        git('add', 'generated-assets/x.png')
        self.assertNotEqual(check().returncode, 0)

    def test_apk_evidence_must_bind_signature_identity_and_restored_bytes(self):
        import shutil
        source = Path(__file__).resolve().parents[3] / 'infrastructure/artifact-recovery'
        for name in ('manifest.json', 'storage-receipt.json', 'second-machine-restore.json', 'apk-verification.json'):
            shutil.copyfile(source / name, self.path / name)
        self.assertEqual(r.verify_migration_records(self.path)['result'], 'PASS')
        target = self.path / 'apk-verification.json'
        original = json.loads(target.read_text(encoding='utf-8'))
        for key, value in [('sha256', '0' * 64), ('signatureVerified', False), ('signerCertificateSha256', '0' * 64), ('packageName', 'com.example.other'), ('versionCode', 999), ('debuggable', False)]:
            corrupted = json.loads(json.dumps(original))
            corrupted['files'][0][key] = value
            target.write_text(json.dumps(corrupted), encoding='utf-8')
            with self.subTest(field=key), self.assertRaises(ValueError):
                r.verify_migration_records(self.path)


class StorageTests(unittest.TestCase):
    def setUp(self):
        self.storage = r.Storage({'BACKUP_S3_ENDPOINT': 'https://example.invalid', 'BACKUP_S3_PROJECT': 'fixture', 'BACKUP_S3_BUCKET': 'fixture', 'BACKUP_S3_REGION': 'test', 'BACKUP_S3_ACCESS_KEY': 'fake', 'BACKUP_S3_SECRET_KEY': 'fake', 'BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION': 'true'})
        self.receipt = {'schema': r.SCHEMA, 'destinationSha256': r.locator(self.storage.config), 'objectKey': self.storage.prefix + 'x', 'versionId': 'version+1/x', 'ciphertextSha256': '0' * 64, 'ciphertextBytes': 32}
        self.headers = {'x-amz-version-id': 'version+1/x', 'x-amz-meta-sha256': '0' * 64, 'content-length': '32', 'x-amz-server-side-encryption': 'AES256'}

    def test_head_metadata_mismatch_rejected(self):
        self.storage.validate_headers(self.headers, self.receipt)
        for key, value in [('x-amz-version-id', 'other'), ('x-amz-meta-sha256', '1' * 64), ('content-length', '33'), ('x-amz-server-side-encryption', '')]:
            with self.subTest(header=key), self.assertRaises(ValueError):
                self.storage.validate_headers({**self.headers, key: value}, self.receipt)

    def test_lifecycle_not_any_404(self):
        for code in ('NoSuchBucket', 'AccessDenied'):
            with mock.patch.object(self.storage, 'request', return_value=(404, {}, f'<Error><Code>{code}</Code></Error>'.encode())):
                with self.assertRaises(ValueError):
                    self.storage.retention()
        with mock.patch.object(self.storage, 'request', return_value=(404, {}, b'<Error><Code>NoSuchLifecycleConfiguration</Code></Error>')):
            self.assertEqual(self.storage.retention()['lifecycle'], 'NO_CONFIGURATION')

    def test_matching_enabled_expiration_rejected(self):
        for expiration in ('Expiration', 'NoncurrentVersionExpiration'):
            xml = f'<LifecycleConfiguration><Rule><Status>Enabled</Status><Filter><Prefix>backup/fixture/</Prefix></Filter><{expiration}><Days>90</Days></{expiration}></Rule></LifecycleConfiguration>'.encode()
            with mock.patch.object(self.storage, 'request', return_value=(200, {}, xml)), self.assertRaises(ValueError):
                self.storage.retention()

    def test_public_acl_rejected(self):
        for group in ('AllUsers', 'AuthenticatedUsers'):
            xml = f'<AccessControlPolicy><Grant><Grantee><URI>http://acs.amazonaws.com/groups/global/{group}</URI></Grantee></Grant></AccessControlPolicy>'.encode()
            with mock.patch.object(self.storage, 'request', return_value=(200, {}, xml)), self.assertRaises(ValueError):
                self.storage.privacy()

    def test_lock_receipt_missing_or_insufficient_rejected(self):
        self.storage.config.update({'BACKUP_S3_OBJECT_LOCK_ENABLED': 'true', 'BACKUP_S3_OBJECT_LOCK_MODE': 'GOVERNANCE'})
        with self.assertRaises(ValueError):
            self.storage.verify_object_retention(self.receipt)
        receipt = {**self.receipt, 'retention': {'mode': 'GOVERNANCE', 'retainUntil': '2030-01-01T00:00:00Z'}}
        for xml in (b'<Retention><Mode>GOVERNANCE</Mode><RetainUntilDate>2029-01-01T00:00:00Z</RetainUntilDate></Retention>', b'<Retention><Mode>COMPLIANCE</Mode><RetainUntilDate>2030-01-01T00:00:00Z</RetainUntilDate></Retention>'):
            with mock.patch.object(self.storage, 'request', return_value=(200, {}, xml)), self.assertRaises(ValueError):
                self.storage.verify_object_retention(receipt)

    def test_download_headers_checked_before_output_file_created(self):
        response = mock.MagicMock()
        response.status = 200
        response.getheaders.return_value = list({**self.headers, 'content-length': '9999999999'}.items())
        connection = mock.MagicMock()
        connection.getresponse.return_value = response
        with tempfile.TemporaryDirectory() as work, mock.patch.object(r.http.client, 'HTTPSConnection', return_value=connection):
            output = Path(work) / 'download'
            with self.assertRaises(ValueError):
                self.storage.request('GET', self.receipt['objectKey'], {'versionId': self.receipt['versionId']}, output=output, expected=self.receipt)
            self.assertFalse(output.exists())
            response.read.assert_not_called()
            path = connection.request.call_args.args[1]
            self.assertIn('versionId=version%2B1%2Fx', path)


if __name__ == '__main__':
    unittest.main()
