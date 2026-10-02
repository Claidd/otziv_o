#!/usr/bin/env python3
"""Archive/restore retained Git artifacts using the dedicated private backup S3.

Requires Python 3.10+ and cryptography. No production credentials are required
for prepare, local verification or tests. Never prints credentials/HTTP bodies.
Encryption is the existing DatabaseBackupService OTZIVDB2 envelope; the payload
is a tar archive, NOT a database dump. Restore only into a new directory.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import hashlib
import hmac
import http.client
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import tarfile
import tempfile
import urllib.parse
import uuid
import xml.etree.ElementTree as ET

SCHEMA = 'otziv-retained-artifacts-v1'
MAGIC = b'OTZIVDB2'
CHUNK = 4 * 1024 * 1024
HEADER = struct.Struct('>8sIQ8s')
PREFIX = 'retained-artifacts/v1/'
HASH = re.compile(r'^[0-9a-f]{64}$')


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as f:
        while chunk := f.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: dict) -> None:
    with path.open('x', encoding='utf-8', newline='\n') as f:
        json.dump(value, f, ensure_ascii=False, indent=2)
        f.write('\n')


def safe_path(name: str) -> bool:
    p = PurePosixPath(name)
    reserved = re.compile(r'^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)', re.I)
    return bool(name) and not p.is_absolute() and '..' not in p.parts and str(p) == name and '\\' not in name and ':' not in name and all(not part.endswith((' ', '.')) and not reserved.match(part) for part in p.parts)


def load_manifest(path: Path) -> dict:
    value = json.loads(path.read_text(encoding='utf-8'))
    if value.get('schema') != SCHEMA or not re.fullmatch('[0-9a-f]{40}', value.get('revision', '')):
        raise ValueError('Invalid artifact manifest identity')
    files = value.get('files', [])
    if not files or len(files) > 10000 or len({f['path'].casefold() for f in files}) != len(files):
        raise ValueError('Empty, duplicate or excessive manifest entries')
    for f in files:
        if not safe_path(f['path']) or not f['path'].startswith(('mobile/builds/', 'generated-assets/')):
            raise ValueError('Unsafe artifact path')
        if not HASH.fullmatch(f['sha256']) or not isinstance(f['bytes'], int) or not 0 <= f['bytes'] <= 2**31:
            raise ValueError('Invalid artifact size/hash')
        if f['mode'] not in ('100644', '100755') or not re.fullmatch('[0-9a-f]{40}', f['gitBlob']):
            raise ValueError('Invalid artifact Git metadata')
    if not HASH.fullmatch(value.get('bundleSha256', '')) or not isinstance(value.get('bundleBytes'), int) or not 0 < value['bundleBytes'] <= 4 * 2**30:
        raise ValueError('Invalid bundle hash/size')
    return value


def prepare(repo: Path, revision: str, out: Path) -> dict:
    revision = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', '--verify', revision + '^{commit}'], text=True).strip()
    tree = subprocess.check_output(['git', '-C', str(repo), 'ls-tree', '-rz', revision, '--', 'mobile/builds', 'generated-assets'])
    out.mkdir(parents=True, exist_ok=False)
    entries = []
    with tarfile.open(out / 'artifacts.tar', 'w', format=tarfile.PAX_FORMAT) as archive:
        for record in tree.split(b'\0'):
            if not record:
                continue
            meta, raw_path = record.split(b'\t', 1)
            mode, kind, blob = meta.decode().split()
            name = raw_path.decode('utf-8')
            if kind != 'blob' or mode not in ('100644', '100755') or not safe_path(name):
                raise ValueError('Only ordinary tracked artifact files can be archived')
            data = subprocess.check_output(['git', '-C', str(repo), 'cat-file', 'blob', blob])
            item = tarfile.TarInfo(name)
            item.size, item.mode, item.mtime = len(data), int(mode[-3:], 8), 0
            archive.addfile(item, io.BytesIO(data))
            entries.append({'path': name, 'gitBlob': blob, 'mode': mode, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    value = {'schema': SCHEMA, 'revision': revision, 'files': entries, 'bundleSha256': sha(out / 'artifacts.tar'), 'bundleBytes': (out / 'artifacts.tar').stat().st_size}
    write_json(out / 'manifest.json', value)
    verify_tar(out / 'artifacts.tar', value)
    return {'files': len(entries), 'bytes': sum(f['bytes'] for f in entries), 'bundleSha256': value['bundleSha256']}


def verify_tar(path: Path, manifest: dict, destination: Path | None = None) -> None:
    if sha(path) != manifest['bundleSha256'] or path.stat().st_size != manifest['bundleBytes']:
        raise ValueError('Archive differs from the reviewed manifest')
    expected = {f['path']: f for f in manifest['files']}
    seen = set()
    with tarfile.open(path, 'r:') as archive:
        for member in archive:
            f = expected.get(member.name)
            if f is None or member.name in seen or not member.isfile() or not safe_path(member.name):
                raise ValueError('Unexpected, duplicate or nonregular archive entry')
            if member.size != f['bytes'] or member.mode != int(f['mode'][-3:], 8):
                raise ValueError('Archive entry metadata mismatch')
            seen.add(member.name)
            digest = hashlib.sha256()
            source = archive.extractfile(member)
            target = None
            try:
                if destination:
                    full = destination / member.name
                    full.parent.mkdir(parents=True, exist_ok=True)
                    target = full.open('xb')
                while data := source.read(1024 * 1024):
                    digest.update(data)
                    if target:
                        target.write(data)
            finally:
                source.close()
                if target:
                    target.close()
            if digest.hexdigest() != f['sha256']:
                raise ValueError('Archive entry hash mismatch')
            if destination:
                (destination / member.name).chmod(int(f['mode'][-3:], 8))
    if seen != expected.keys():
        raise ValueError('Archive is missing manifest files')


def aes():
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return AESGCM


def encrypt(source: Path, target: Path, key: bytes) -> None:
    size = source.stat().st_size
    if len(key) != 32 or not 0 < size <= CHUNK * 2**32:
        raise ValueError('Invalid encryption key or input size')
    nonce_prefix = os.urandom(8)
    header = HEADER.pack(MAGIC, CHUNK, size, nonce_prefix)
    cipher = aes()(key)
    created = False
    try:
        with source.open('rb') as src, target.open('xb') as dst:
            created = True
            dst.write(header)
            remaining = size
            for index in range((size + CHUNK - 1) // CHUNK):
                data = src.read(min(CHUNK, remaining))
                if len(data) != min(CHUNK, remaining):
                    raise ValueError('Encryption input changed')
                index_bytes = struct.pack('>I', index)
                dst.write(cipher.encrypt(nonce_prefix + index_bytes, data, header + index_bytes + struct.pack('>I', len(data))))
                remaining -= len(data)
            if src.read(1):
                raise ValueError('Encryption input grew')
    except BaseException:
        if created:
            target.unlink(missing_ok=True)
        raise


def decrypt(source: Path, target: Path, key: bytes) -> None:
    if len(key) != 32:
        raise ValueError('Invalid encryption key')
    created = False
    try:
        with source.open('rb') as src:
            header = src.read(HEADER.size)
            magic, chunk, size, prefix = HEADER.unpack(header)
            if magic != MAGIC or not 65536 <= chunk <= 67108864 or not 0 < size <= chunk * 2**32:
                raise ValueError('Invalid OTZIVDB2 envelope')
            count = (size + chunk - 1) // chunk
            if source.stat().st_size != HEADER.size + size + 16 * count:
                raise ValueError('Envelope length mismatch')
            cipher = aes()(key)
            with target.open('xb') as dst:
                created = True
                remaining = size
                for index in range(count):
                    length = min(chunk, remaining)
                    index_bytes = struct.pack('>I', index)
                    dst.write(cipher.decrypt(prefix + index_bytes, src.read(length + 16), header + index_bytes + struct.pack('>I', length)))
                    remaining -= length
    except BaseException:
        if created:
            target.unlink(missing_ok=True)
        raise


def load_config(path: Path) -> dict:
    env = {}
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
        if not raw.strip() or raw.lstrip().startswith('#') or '=' not in raw:
            continue
        k, v = raw.split('=', 1)
        v = v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in '\"\'':
            v = v[1:-1]
        env[k.strip()] = v
    for key in ('BACKUP_S3_ENDPOINT', 'BACKUP_S3_REGION', 'BACKUP_S3_BUCKET', 'BACKUP_S3_PROJECT', 'BACKUP_S3_ACCESS_KEY', 'BACKUP_S3_SECRET_KEY', 'BACKUP_ENCRYPTION_KEY_BASE64'):
        if not env.get(key):
            raise ValueError('Missing dedicated backup configuration: ' + key)
    for key in ('BACKUP_S3_INDEPENDENT_CONFIRMED', 'BACKUP_DESTINATION_PRIVATE_CONFIRMED', 'BACKUP_ENCRYPTION_AT_REST_CONFIRMED'):
        if env.get(key) != 'true':
            raise ValueError('Backup destination has not been confirmed: ' + key)
    if env['BACKUP_S3_BUCKET'] == env.get('S3_BUCKET') or env['BACKUP_S3_ACCESS_KEY'] == env.get('S3_ACCESS_KEY'):
        raise ValueError('Backup storage must be independent of application S3')
    if env.get('BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION', 'true') not in ('true', 'false'):
        raise ValueError('Invalid backup SSE policy')
    if not re.fullmatch('[A-Za-z0-9][A-Za-z0-9._-]{0,127}', env['BACKUP_S3_PROJECT']):
        raise ValueError('Invalid backup project identity')
    lock = env.get('BACKUP_S3_OBJECT_LOCK_ENABLED', 'false')
    days = int(env.get('BACKUP_S3_RETENTION_DAYS', '0'))
    if lock not in ('true', 'false') or not 0 <= days <= 36500 or (lock == 'true') != (days > 0):
        raise ValueError('Invalid backup Object Lock/retention policy')
    if env.get('BACKUP_S3_OBJECT_LOCK_MODE', 'GOVERNANCE') not in ('GOVERNANCE', 'COMPLIANCE'):
        raise ValueError('Invalid backup Object Lock mode')
    parsed = urllib.parse.urlsplit(env['BACKUP_S3_ENDPOINT'])
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Backup endpoint must be a literal HTTPS endpoint')
    key = base64.b64decode(env['BACKUP_ENCRYPTION_KEY_BASE64'], validate=True)
    if len(key) != 32:
        raise ValueError('Backup encryption key must contain exactly 32 bytes')
    env['key'] = key
    return env


class Storage:
    def __init__(self, config: dict):
        self.config = config
        self.endpoint = urllib.parse.urlsplit(config['BACKUP_S3_ENDPOINT'].rstrip('/'))
        self.prefix = 'backup/' + config['BACKUP_S3_PROJECT'] + '/' + PREFIX

    def request(self, method: str, key: str = '', query: dict | None = None, *, source: Path | None = None, output: Path | None = None, expected: dict | None = None, headers: dict | None = None, authenticated: bool = True):
        c = self.config
        uri = urllib.parse.quote('/'.join((self.endpoint.path.rstrip('/'), c['BACKUP_S3_BUCKET'], key)).rstrip('/'), safe='/-_.~')
        query_string = urllib.parse.urlencode(sorted((query or {}).items()), quote_via=urllib.parse.quote, safe='-_.~')
        digest = sha(source) if source else hashlib.sha256(b'').hexdigest()
        values = {k.lower(): str(v) for k, v in (headers or {}).items()}
        values['host'] = self.endpoint.netloc
        if source:
            values['content-length'] = str(source.stat().st_size)
        if authenticated:
            now = dt.datetime.now(dt.timezone.utc)
            timestamp, date = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
            values.update({'x-amz-date': timestamp, 'x-amz-content-sha256': digest})
            names = ';'.join(sorted(values))
            canonical = '\n'.join((method, uri, query_string, ''.join(f'{k}:{values[k]}\n' for k in sorted(values)), names, digest))
            scope = f"{date}/{c['BACKUP_S3_REGION']}/s3/aws4_request"
            signing = ('AWS4' + c['BACKUP_S3_SECRET_KEY']).encode()
            for part in (date, c['BACKUP_S3_REGION'], 's3', 'aws4_request'):
                signing = hmac.digest(signing, part.encode(), 'sha256')
            sign_text = '\n'.join(('AWS4-HMAC-SHA256', timestamp, scope, hashlib.sha256(canonical.encode()).hexdigest()))
            signature = hmac.new(signing, sign_text.encode(), hashlib.sha256).hexdigest()
            values['authorization'] = f"AWS4-HMAC-SHA256 Credential={c['BACKUP_S3_ACCESS_KEY']}/{scope}, SignedHeaders={names}, Signature={signature}"
        connection = http.client.HTTPSConnection(self.endpoint.hostname, self.endpoint.port, timeout=300)
        source_stream = source.open('rb') if source else None
        try:
            connection.request(method, uri + ('?' + query_string if query_string else ''), body=source_stream, headers=values)
            response = connection.getresponse()
            result_headers = {k.lower(): v for k, v in response.getheaders()}
            body = b''
            if response.status == 200 and output:
                if expected is None:
                    raise ValueError('A download must specify exact bounded metadata')
                self.validate_headers(result_headers, expected)
                with output.open('xb') as dst:
                    remaining = expected['ciphertextBytes']
                    while remaining:
                        chunk = response.read(min(1024 * 1024, remaining))
                        if not chunk:
                            raise ValueError('Truncated artifact download')
                        dst.write(chunk)
                        remaining -= len(chunk)
                    if response.read(1):
                        raise ValueError('Artifact download exceeds the expected length')
            else:
                body = response.read(1024 * 1024)
            return response.status, result_headers, body
        finally:
            if source_stream:
                source_stream.close()
            connection.close()

    def validate_headers(self, headers: dict, receipt: dict) -> None:
        if headers.get('x-amz-version-id') != receipt['versionId']:
            raise ValueError('Version-bound artifact response mismatch')
        if headers.get('x-amz-meta-sha256') != receipt['ciphertextSha256'] or int(headers.get('content-length', '-1')) != receipt['ciphertextBytes']:
            raise ValueError('Remote artifact size/hash metadata mismatch')
        if self.config.get('BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION', 'true') == 'true' and headers.get('x-amz-server-side-encryption') != 'AES256':
            raise ValueError('Required server-side encryption is unconfirmed')

    def privacy(self, key: str = '', version: str | None = None) -> dict:
        query = {'acl': ''}
        if version:
            query['versionId'] = version
        status, _, body = self.request('GET', key, query)
        if status != 200:
            raise ValueError(f'Cannot verify private ACL (HTTP {status})')
        root = ET.fromstring(body)
        for uri in root.iter():
            if uri.tag.endswith('URI') and ('AllUsers' in (uri.text or '') or 'AuthenticatedUsers' in (uri.text or '')):
                raise ValueError('Artifact destination has a public ACL grant')
        return {'aclVerifiedPrivate': True}

    def retention(self) -> dict:
        status, _, body = self.request('GET', query={'lifecycle': ''})
        if status == 404 and ET.fromstring(body).findtext('Code') == 'NoSuchLifecycleConfiguration':
            return {'lifecycle': 'NO_CONFIGURATION'}
        if status != 200:
            raise ValueError(f'Cannot verify lifecycle preservation (HTTP {status})')
        root = ET.fromstring(body)
        for rule in root:
            tags = {e.tag.rsplit('}', 1)[-1]: e.text for e in rule.iter()}
            if tags.get('Status') == 'Enabled' and any(k in tags for k in ('Expiration', 'NoncurrentVersionExpiration')):
                prefix = tags.get('Prefix') or ''
                if self.prefix.startswith(prefix) or prefix.startswith(self.prefix):
                    raise ValueError('Backup lifecycle may expire retained artifacts')
        return {'lifecycle': 'NO_MATCHING_EXPIRATION'}

    def verify_object_retention(self, receipt: dict) -> None:
        if self.config.get('BACKUP_S3_OBJECT_LOCK_ENABLED', 'false') != 'true':
            return
        expected = receipt.get('retention', {})
        if expected.get('mode') != self.config.get('BACKUP_S3_OBJECT_LOCK_MODE', 'GOVERNANCE') or not expected.get('retainUntil'):
            raise ValueError('Receipt does not confirm the required retention policy')
        status, _, body = self.request('GET', receipt['objectKey'], {'retention': '', 'versionId': receipt['versionId']})
        if status != 200:
            raise ValueError('Object Lock retention is unconfirmed')
        fields = {e.tag.rsplit('}', 1)[-1]: e.text for e in ET.fromstring(body).iter()}
        actual_date = dt.datetime.fromisoformat(fields.get('RetainUntilDate', '').replace('Z', '+00:00'))
        if fields.get('Mode') != expected['mode'] or actual_date < dt.datetime.fromisoformat(expected['retainUntil'].replace('Z', '+00:00')):
            raise ValueError('Object Lock retention does not match the requested policy')


def locator(config: dict) -> str:
    return hashlib.sha256((config['BACKUP_S3_ENDPOINT'].rstrip('/') + '/' + config['BACKUP_S3_BUCKET']).encode()).hexdigest()


def upload_verify(storage: Storage, encrypted: Path, key: str, download: Path) -> dict:
    if not key.startswith(storage.prefix):
        raise ValueError('Refusing write outside the retained-artifacts prefix')
    privacy, retention = storage.privacy(), storage.retention()
    status, _, _ = storage.request('HEAD', key)
    # Least-privilege S3 principals can receive 403 for absent keys without
    # ListBucket permission. The conditional PUT remains mandatory in that case.
    if status not in (403, 404):
        raise ValueError(f'New immutable object path is not confirmed absent (HTTP {status})')
    expected_hash = sha(encrypted)
    headers = {'if-none-match': '*', 'x-amz-meta-sha256': expected_hash, 'content-type': 'application/octet-stream'}
    sse = storage.config.get('BACKUP_S3_REQUIRE_SERVER_SIDE_ENCRYPTION', 'true') == 'true'
    if sse:
        headers['x-amz-server-side-encryption'] = 'AES256'
    retention_proof = None
    if storage.config.get('BACKUP_S3_OBJECT_LOCK_ENABLED', 'false') == 'true':
        until = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=int(storage.config['BACKUP_S3_RETENTION_DAYS']))).strftime('%Y-%m-%dT%H:%M:%SZ')
        mode = storage.config.get('BACKUP_S3_OBJECT_LOCK_MODE', 'GOVERNANCE')
        headers.update({'x-amz-object-lock-mode': mode, 'x-amz-object-lock-retain-until-date': until})
        md5 = hashlib.md5(usedforsecurity=False)
        with encrypted.open('rb') as src:
            while chunk := src.read(1024 * 1024):
                md5.update(chunk)
        headers['content-md5'] = base64.b64encode(md5.digest()).decode()
        retention_proof = {'mode': mode, 'retainUntil': until, 'requestedDays': int(storage.config['BACKUP_S3_RETENTION_DAYS'])}
    status, result, _ = storage.request('PUT', key, source=encrypted, headers=headers)
    if status not in (200, 201):
        raise ValueError(f'Artifact upload failed (HTTP {status}); inspect exact new key before retry')
    version = result.get('x-amz-version-id')
    if not version or version == 'null':
        raise ValueError('Storage did not return an immutable object version; no deletion is permitted')
    receipt = {'schema': SCHEMA, 'objectKey': key, 'versionId': version, 'ciphertextSha256': expected_hash, 'ciphertextBytes': encrypted.stat().st_size, 'destinationSha256': locator(storage.config), 'clientSideEncryption': 'OTZIVDB2_AES_256_GCM', 'retention': retention_proof, **privacy, **retention}
    storage.verify_object_retention(receipt)
    download_verify(storage, receipt, download)
    storage.privacy(key, version)
    anonymous_status, _, _ = storage.request('GET', key, {'versionId': version}, authenticated=False)
    if anonymous_status not in (403, 404):
        raise ValueError('Anonymous access was not denied')
    receipt.update({'anonymousDownloadDenied': True, 'versionBoundDownloadVerified': True, 'serverSideEncryption': result.get('x-amz-server-side-encryption', 'NONE_REPORTED')})
    return receipt


def download_verify(storage: Storage, receipt: dict, destination: Path) -> None:
    if receipt.get('schema') != SCHEMA or receipt.get('destinationSha256') != locator(storage.config):
        raise ValueError('Receipt belongs to another destination/schema')
    key, version = receipt['objectKey'], receipt['versionId']
    if not key.startswith(storage.prefix) or not version or version == 'null' or not HASH.fullmatch(receipt['ciphertextSha256']) or not isinstance(receipt['ciphertextBytes'], int) or not 0 < receipt['ciphertextBytes'] <= 5 * 2**30:
        raise ValueError('Invalid immutable artifact receipt')
    query = {'versionId': version}
    for method in ('HEAD', 'GET'):
        status, headers, _ = storage.request(method, key, query, output=destination if method == 'GET' else None, expected=receipt)
        if status != 200 or headers.get('x-amz-version-id') != version:
            raise ValueError('Version-bound artifact request failed')
        storage.validate_headers(headers, receipt)
    storage.verify_object_retention(receipt)
    if sha(destination) != receipt['ciphertextSha256'] or destination.stat().st_size != receipt['ciphertextBytes']:
        raise ValueError('Downloaded ciphertext differs from the receipt')


def verify_migration_records(directory: Path) -> dict:
    """Offline CI binding checks for reviewed receipts; never a fresh S3 audit."""
    manifest_path = directory / 'manifest.json'
    manifest = load_manifest(manifest_path)
    receipt = json.loads((directory / 'storage-receipt.json').read_text(encoding='utf-8'))
    restored = json.loads((directory / 'second-machine-restore.json').read_text(encoding='utf-8'))
    for value in (receipt, restored):
        if value.get('schema') != SCHEMA or value.get('manifestSha256') != sha(manifest_path):
            raise ValueError('Recovery evidence does not bind the manifest')
        for name in ('authenticatedEnvelopeVerified', 'versionBoundDownloadVerified'):
            if value.get(name) is not True:
                raise ValueError('Recovery evidence is missing a mandatory verification')
    if receipt.get('bundleSha256') != manifest['bundleSha256'] or receipt.get('allFilesVerified') != len(manifest['files']):
        raise ValueError('Storage receipt does not cover the complete archive')
    for name in ('aclVerifiedPrivate', 'anonymousDownloadDenied'):
        if receipt.get(name) is not True:
            raise ValueError('Storage privacy verification is missing')
    if receipt.get('lifecycle') not in ('NO_CONFIGURATION', 'NO_MATCHING_EXPIRATION'):
        raise ValueError('Archive expiration policy is unconfirmed')
    if not receipt.get('versionId') or receipt['versionId'] == 'null' or not HASH.fullmatch(receipt.get('ciphertextSha256', '')):
        raise ValueError('Immutable object evidence is invalid')
    for name in ('objectKey', 'versionId', 'ciphertextSha256'):
        if restored.get(name) != receipt[name]:
            raise ValueError('Second-machine proof used a different stored object')
    if restored.get('allFileHashesVerified') is not True or restored.get('verifiedFiles') != len(manifest['files']) or restored.get('verifiedBytes') != sum(f['bytes'] for f in manifest['files']):
        raise ValueError('Second-machine proof does not cover every artifact')
    if restored.get('machineRole') != 'production-vps-isolated-recovery' or restored.get('usedPreexistingRemoteKey') is not True:
        raise ValueError('Independent machine/key recovery proof is missing')
    apks = {f['path']: f for f in manifest['files'] if f['path'].endswith('.apk')}
    if apks:
        apk_proof = json.loads((directory / 'apk-verification.json').read_text(encoding='utf-8'))
        if apk_proof.get('schema') != 'otziv-retained-apk-verification-v1' or apk_proof.get('manifestSha256') != sha(manifest_path):
            raise ValueError('APK verification does not bind this manifest')
        verified = apk_proof.get('files', [])
        if len(verified) != len(apks) or {a['path'] for a in verified} != apks.keys():
            raise ValueError('APK verification coverage is incomplete')
        for a in verified:
            if a.get('sha256') != apks[a['path']]['sha256'] or a.get('signatureVerified') is not True or a.get('v2SignatureVerified') is not True:
                raise ValueError('APK signature proof does not cover the restored bytes')
            if a.get('packageName') != 'com.hunt.otziv' or a.get('signerCertificateSha256') != 'a15a162afe1f808f9586dd3f129f9e61f4be49ccff708ca99c6a0714004251d5':
                raise ValueError('Unexpected archived APK identity')
            code = a.get('versionCode')
            if not isinstance(code, int) or code <= 0 or a.get('versionName') != f'1.0.{code}' or not a['path'].endswith(f'-v1.0.{code}-code{code}.apk') or a.get('debuggable') is not (code == 53):
                raise ValueError('Archived APK version/debug policy mismatch')
    files = {f['path']: f['gitBlob'] for f in manifest['files'] if not f['path'].endswith(('.py', '.json'))}
    return {'schema': SCHEMA, 'result': 'PASS', 'files': files, 'manifestSha256': sha(manifest_path)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    prep = commands.add_parser('prepare')
    prep.add_argument('--repo', type=Path, default=Path('.'))
    prep.add_argument('--revision', default='HEAD')
    prep.add_argument('--out', type=Path, required=True)
    check = commands.add_parser('verify-records')
    check.add_argument('--directory', type=Path, required=True)
    canary = commands.add_parser('canary')
    canary.add_argument('--env', type=Path, required=True)
    canary.add_argument('--out', type=Path, required=True)
    publish = commands.add_parser('publish')
    publish.add_argument('--env', type=Path, required=True)
    publish.add_argument('--manifest', type=Path, required=True)
    publish.add_argument('--bundle', type=Path, required=True)
    publish.add_argument('--receipt', type=Path, required=True)
    restore = commands.add_parser('restore')
    restore.add_argument('--env', type=Path, required=True)
    restore.add_argument('--manifest', type=Path, required=True)
    restore.add_argument('--receipt', type=Path, required=True)
    restore.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'verify-records':
        print(json.dumps(verify_migration_records(args.directory)))
        return
    if args.command == 'prepare':
        print(json.dumps(prepare(args.repo, args.revision, args.out)))
        return
    config = load_config(args.env)
    storage = Storage(config)
    if args.command == 'canary':
        args.out.mkdir(parents=True, exist_ok=False)
        plain = args.out / 'canary.txt'
        plain.write_bytes(b'OTZIV retained artifact storage canary\n' + os.urandom(32))
        encrypted = args.out / 'canary.enc'
        encrypt(plain, encrypted, config['key'])
        receipt = upload_verify(storage, encrypted, storage.prefix + 'canaries/' + uuid.uuid4().hex + '.enc', args.out / 'download.enc')
        decrypt(args.out / 'download.enc', args.out / 'restored.txt', config['key'])
        if sha(plain) != sha(args.out / 'restored.txt'):
            raise ValueError('Canary decrypt mismatch')
        receipt['authenticatedEnvelopeVerified'] = True
        write_json(args.out / 'receipt.json', receipt)
        print(json.dumps({'result': 'PASS', 'versionBoundDownloadVerified': True, 'privateAcl': True, 'authenticatedEnvelopeVerified': True}))
        return
    manifest = load_manifest(args.manifest)
    with tempfile.TemporaryDirectory(prefix='otziv-artifact-transfer-') as folder:
        work = Path(folder)
        if args.command == 'publish':
            verify_tar(args.bundle, manifest)
            encrypted = work / 'artifacts.enc'
            encrypt(args.bundle, encrypted, config['key'])
            key = storage.prefix + manifest['bundleSha256'] + '/' + sha(encrypted) + '.tar.otzivdb2'
            receipt = upload_verify(storage, encrypted, key, work / 'download.enc')
            decrypt(work / 'download.enc', work / 'download.tar', config['key'])
            verify_tar(work / 'download.tar', manifest)
            receipt.update({'manifestSha256': sha(args.manifest), 'bundleSha256': manifest['bundleSha256'], 'authenticatedEnvelopeVerified': True, 'allFilesVerified': len(manifest['files']), 'verifiedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat()})
            write_json(args.receipt, receipt)
        else:
            receipt = json.loads(args.receipt.read_text(encoding='utf-8'))
            if receipt.get('manifestSha256') != sha(args.manifest) or receipt.get('bundleSha256') != manifest['bundleSha256']:
                raise ValueError('Receipt does not bind this manifest')
            if args.out.exists():
                raise ValueError('Restore output must be a new directory')
            download_verify(storage, receipt, work / 'download.enc')
            decrypt(work / 'download.enc', work / 'download.tar', config['key'])
            verify_tar(work / 'download.tar', manifest)
            args.out.mkdir(parents=True)
            verify_tar(work / 'download.tar', manifest, args.out)
            write_json(args.out / 'restore-proof.json', {'schema': SCHEMA, 'manifestSha256': sha(args.manifest), 'ciphertextSha256': receipt['ciphertextSha256'], 'objectKey': receipt['objectKey'], 'versionId': receipt['versionId'], 'verifiedFiles': len(manifest['files']), 'verifiedBytes': sum(f['bytes'] for f in manifest['files']), 'allFileHashesVerified': True, 'authenticatedEnvelopeVerified': True, 'versionBoundDownloadVerified': True, 'verifiedAtUtc': dt.datetime.now(dt.timezone.utc).isoformat()})
    print(json.dumps({'result': 'PASS', 'command': args.command, 'files': len(manifest['files'])}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never include HTTP bodies, connection URLs or configuration values.
        print('Artifact operation failed: ' + type(error).__name__ + ': ' + (str(error) if isinstance(error, ValueError) else 'see operation prerequisites'), file=__import__('sys').stderr)
        raise SystemExit(1)
