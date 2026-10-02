"""Restore the hash-pinned C26 evidence archive without GitHub or production credentials.

Only the three reviewed C26 directories are writable. Existing evidence and cached
archives must match the committed manifest; corruption is never silently replaced.
The unchanged validators continue reading the original paths and exact raw bytes.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile

SCHEMA = 'otziv-c26-evidence-archive-v1'
ROOTS = tuple('infrastructure/runtime-security/proofs/c26-' + name
              for name in ('keycloak', 'nginx', 'phpmyadmin'))
MANIFEST = 'infrastructure/runtime-security/evidence-archive-c26.json'
URL = 'https://github.com/Claidd/otziv_o/releases/download/runtime-evidence-c26-20261002/c26-evidence.zip'
MAX_ARCHIVE = 32 * 1024 * 1024
MAX_FILE = 8 * 1024 * 1024
MAX_TOTAL = 32 * 1024 * 1024
HASH = re.compile(r'^[a-f0-9]{64}$')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe_name(name):
    if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+', name):
        return False
    path = PurePosixPath(name)
    reserved = re.compile(r'^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)', re.I)
    return (str(path) == name and all(part not in ('.', '..') and not part.endswith('.')
            and not reserved.match(part) for part in path.parts)
            and any(name.startswith(root + '/') for root in ROOTS))


def validate_manifest(value):
    require(isinstance(value, dict) and set(value) == {'schema', 'sourceRevision', 'archive', 'files'},
            'Unexpected evidence manifest fields')
    require(value['schema'] == SCHEMA and isinstance(value['sourceRevision'], str)
            and re.fullmatch(r'[a-f0-9]{40}', value['sourceRevision']),
            'Invalid evidence source identity')
    archive = value['archive']
    require(isinstance(archive, dict) and set(archive) == {'url', 'sha256', 'bytes'}, 'Invalid archive identity')
    require(archive['url'] == URL and isinstance(archive['sha256'], str) and HASH.fullmatch(archive['sha256'])
            and type(archive['bytes']) is int and 0 < archive['bytes'] <= MAX_ARCHIVE, 'Invalid archive location, hash or size')
    files = value['files']
    require(isinstance(files, list) and 0 < len(files) <= 128, 'Invalid evidence file count')
    for item in files:
        require(isinstance(item, dict) and set(item) == {'path', 'sha256', 'bytes', 'mode'}, 'Invalid evidence file fields')
        require(safe_name(item['path']) and isinstance(item['sha256'], str) and HASH.fullmatch(item['sha256'])
                and type(item['bytes']) is int and 0 < item['bytes'] <= MAX_FILE
                and item['mode'] in ('100644', '100755'), 'Invalid evidence file identity')
    names = [item['path'] for item in files]
    require(names == sorted(names) and len({name.casefold() for name in names}) == len(names),
            'Unsorted or duplicate evidence paths')
    require(sum(item['bytes'] for item in files) <= MAX_TOTAL, 'Evidence exceeds total size limit')
    return value


def load_manifest(path):
    require(path.stat().st_size <= 128 * 1024, 'Evidence manifest is too large')
    return validate_manifest(json.loads(path.read_text(encoding='utf-8')))


def linked(path):
    return path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction())


def target_path(root, name):
    require(safe_name(name), 'Evidence path is outside the reviewed directories')
    return unlinked_path(root, name)


def unlinked_path(root, name):
    target = root / name
    current = root
    for part in PurePosixPath(name).parts:
        current = current / part
        require(not linked(current), 'Evidence path contains a link')
    require(target.resolve().is_relative_to(root), 'Evidence path escaped the destination')
    return target


def bounded_bytes(path, maximum):
    require(not linked(path) and path.is_file(), 'Evidence input must be an ordinary file')
    require(path.stat().st_size <= maximum, 'Evidence input exceeds its size bound')
    with path.open('rb') as source:
        data = source.read(maximum + 1)
    require(len(data) <= maximum, 'Evidence input grew past its size bound')
    return data


def check_existing(root, manifest):
    expected = {item['path']: item for item in manifest['files']}
    missing = []
    for name, item in expected.items():
        target = target_path(root, name)
        if not target.exists():
            missing.append(name)
            continue
        data = bounded_bytes(target, item['bytes'])
        require(len(data) == item['bytes'] and sha(data) == item['sha256'],
                'Existing evidence differs from the committed manifest: ' + name)
    # Local ignored scanner logs may coexist here. They are neither evidence nor
    # archive members, and are never read, changed or included in a deploy bundle.
    return missing


def archive_payloads(data, manifest):
    identity = manifest['archive']
    require(len(data) == identity['bytes'] and sha(data) == identity['sha256'], 'Evidence archive hash or size mismatch')
    expected = {item['path']: item for item in manifest['files']}
    payloads = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(len(archive.infolist()) == len(expected), 'Unexpected archive entry count')
        for member in archive.infolist():
            require(member.filename in expected and member.filename not in payloads, 'Unexpected or duplicate archive path')
            item = expected[member.filename]
            require(member.create_system == 3 and stat.S_ISREG(member.external_attr >> 16)
                    and stat.S_IMODE(member.external_attr >> 16) == int(item['mode'][-3:], 8)
                    and not member.flag_bits & 1 and member.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    and member.file_size == item['bytes'], 'Unsafe archive entry metadata')
            with archive.open(member) as source:
                payload = source.read(item['bytes'] + 1)
            require(len(payload) == item['bytes'] and sha(payload) == item['sha256'], 'Evidence member hash or size mismatch')
            payloads[member.filename] = payload
    require(payloads.keys() == expected.keys(), 'Evidence archive is incomplete')
    return payloads


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def download_bytes(manifest, opener=None):
    opener = opener or urllib.request.build_opener(NoRedirect())
    current = manifest['archive']['url']
    for _ in range(4):
        parsed = urllib.parse.urlsplit(current)
        require(parsed.scheme == 'https' and parsed.port in (None, 443) and not parsed.username
                and not parsed.password and not parsed.fragment
                and (current == URL or parsed.hostname in ('release-assets.githubusercontent.com', 'objects.githubusercontent.com')),
                'Untrusted evidence download destination')
        request = urllib.request.Request(current, headers={'User-Agent': 'otziv-c26-evidence', 'Accept': 'application/octet-stream'})
        try:
            with opener.open(request, timeout=45) as response:
                require(response.status == 200, 'Evidence download did not return HTTP 200')
                length = response.headers.get('Content-Length')
                require(length is None or int(length) == manifest['archive']['bytes'], 'Unexpected evidence download length')
                data = response.read(manifest['archive']['bytes'] + 1)
                archive_payloads(data, manifest)
                return data
        except urllib.error.HTTPError as error:
            if error.code not in (301, 302, 303, 307, 308):
                raise ValueError('Evidence download failed with HTTP ' + str(error.code)) from None
            location = error.headers.get('Location')
            require(isinstance(location, str) and location, 'Evidence redirect has no location')
            current = urllib.parse.urljoin(current, location)
    raise ValueError('Too many evidence download redirects')


def hydrate(root, manifest, archive_path=None, download=False, opener=None):
    root = root.resolve(strict=True)
    validate_manifest(manifest)
    missing = check_existing(root, manifest)
    if not missing:
        return {'files': len(manifest['files']), 'restored': 0, 'networkUsed': False}
    cache_name = '.codex-tmp/runtime-evidence/' + manifest['archive']['sha256'] + '.zip'
    cached = archive_path or unlinked_path(root, cache_name)
    network = False
    if cached.exists() or linked(cached):
        data = bounded_bytes(cached, manifest['archive']['bytes'])
    else:
        require(archive_path is None and download, 'Evidence is missing; provide the archive or pass --download')
        data = download_bytes(manifest, opener)
        cached.parent.mkdir(parents=True, exist_ok=True)
        cached = unlinked_path(root, cache_name)
        with cached.open('xb') as output:
            output.write(data)
        network = True
    payloads = archive_payloads(data, manifest)
    modes = {item['path']: int(item['mode'][-3:], 8) for item in manifest['files']}
    for name in missing:
        target = target_path(root, name)
        target.parent.mkdir(parents=True, exist_ok=True)
        target = target_path(root, name)
        created = False
        try:
            with target.open('xb') as output:
                created = True
                output.write(payloads[name])
            target.chmod(modes[name])
        except BaseException:
            if created:
                target.unlink(missing_ok=True)
            raise
    require(not check_existing(root, manifest), 'Evidence restoration is incomplete')
    return {'files': len(manifest['files']), 'restored': len(missing), 'networkUsed': network}


def prepare(root, revision, output):
    revision = subprocess.check_output(['git', '-C', str(root), 'rev-parse', '--verify', revision + '^{commit}'], text=True).strip()
    entries = subprocess.check_output(['git', '-C', str(root), 'ls-tree', '-rz', revision, '--', *ROOTS])
    files = []
    total = 0
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for entry in entries.split(b'\0'):
            if not entry:
                continue
            metadata, raw_name = entry.split(b'\t', 1)
            mode, kind, blob = metadata.decode().split()
            name = raw_name.decode('utf-8')
            require(kind == 'blob' and mode in ('100644', '100755') and safe_name(name), 'Unreviewed Git archive entry')
            size = int(subprocess.check_output(['git', '-C', str(root), 'cat-file', '-s', blob]))
            total += size
            require(0 < size <= MAX_FILE and total <= MAX_TOTAL and len(files) < 128, 'Git evidence exceeds size or count bounds')
            data = subprocess.check_output(['git', '-C', str(root), 'cat-file', 'blob', blob])
            member = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            member.create_system = 3
            member.external_attr = (stat.S_IFREG | int(mode[-3:], 8)) << 16
            member.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(member, data, compresslevel=9)
            files.append({'path': name, 'sha256': sha(data), 'bytes': len(data), 'mode': mode})
    data = buffer.getvalue()
    manifest = validate_manifest({'schema': SCHEMA, 'sourceRevision': revision,
                                 'archive': {'url': URL, 'sha256': sha(data), 'bytes': len(data)}, 'files': files})
    archive_payloads(data, manifest)
    output.mkdir(parents=True, exist_ok=False)
    (output / 'c26-evidence.zip').write_bytes(data)
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8', newline='\n')
    return {'files': len(files), 'rawBytes': sum(item['bytes'] for item in files), 'archiveBytes': len(data),
            'archiveSha256': sha(data), 'sourceRevision': revision}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('hydrate', 'verify', 'prepare'))
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--revision')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            require(args.revision and args.output, 'Prepare requires an explicit revision and new output directory')
            result = prepare(args.root, args.revision, args.output)
        else:
            manifest = load_manifest(args.manifest or args.root / MANIFEST)
            if args.command == 'verify':
                require(not args.download and args.archive is None, 'Verify is offline and only checks materialized evidence')
                require(not check_existing(args.root.resolve(strict=True), manifest), 'Evidence files are missing')
                result = {'files': len(manifest['files']), 'networkUsed': False}
            else:
                result = hydrate(args.root, manifest, args.archive, args.download)
        print(json.dumps({'result': 'PASS', **result}))
        return 0
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        # Do not expose signed CDN URLs, HTTP bodies or environment data.
        print('C26 evidence operation failed: ' + (str(error) if isinstance(error, ValueError) else type(error).__name__))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
