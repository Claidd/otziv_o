"""Record the exact official Debian zlib1g package used by the C51 PostgreSQL overlay.

The .deb bytes are pinned by SHA-256. The accepted C36 image deliberately has no
dpkg, so its payload and control members are read with dpkg-deb in a disposable,
network-less container of the same pinned Debian trixie build image that the
recipe uses (the binary package format is never parsed by hand). The exact dpkg
records the overlay writes are derived from those members. With --verify-archive
an additional disposable build-image container authenticates Debian's signed
trixie-security index with the image's debian-archive-keyring and must report the
same file, size and SHA-256.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

ADVISORIES = ['CVE-2026-76844', 'CVE-2026-85091']
PARENT = 'ghcr.io/claidd/otziv-security@sha256:26663e414ba75586834873d6a43d8c1a105c12f58804e7fb961581d61a6dbdd5'
BUILDER = 'node:22-trixie-slim@sha256:7b8a0c89c54499bee567618f96578e1a12a800f062fbdbfd1fb6a443fa6f6284'
PACKAGE = 'zlib1g'
VERSION = '1:1.3.dfsg+really1.3.1-1+deb13u1'
PREVIOUS_VERSION = '1:1.3.dfsg+really1.3.1-1+b1'
FILENAME = 'pool/updates/main/z/zlib/zlib1g_1.3.dfsg+really1.3.1-1+deb13u1_amd64.deb'
URL = 'https://deb.debian.org/debian-security/pool/updates/main/z/zlib/zlib1g_1.3.dfsg%2breally1.3.1-1%2bdeb13u1_amd64.deb'
SHA256 = '8c27c342230503b73dfde629642c3b352e87b554e06d60cf58772f236a305ceb'
ISOLATION = ['--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
             '--memory', '512m', '--pids-limit', '64', '--user', '65534:65534']


def docker(*args, timeout=300):
    return subprocess.run(['docker', *args], check=True, capture_output=True, timeout=timeout).stdout


def fields(text):
    values, key = {}, None
    for line in text.splitlines():
        if line[:1] in (' ', '\t'):
            assert key, 'zlib_c51_control_continuation'
            values[key] += '\n' + line
        elif line:
            key, value = line.split(':', 1)
            assert key not in values, 'zlib_c51_control_duplicate_field'
            values[key] = value.strip()
    return values


def members(stream, root_required):
    rows, texts, order = {}, {}, []
    with tarfile.open(fileobj=io.BytesIO(stream), mode='r:') as archive:
        for member in archive:
            order.append(member.name)
            name = member.name.removeprefix('./').rstrip('/')
            if name in ('', '.'):
                assert member.isdir(), 'zlib_c51_archive_root'
                continue
            assert not name.startswith('/') and '..' not in name.split('/'), 'zlib_c51_archive_path'
            row = {'mode': member.mode, 'uid': member.uid, 'gid': member.gid}
            if member.isfile():
                content = archive.extractfile(member).read()
                row.update(kind='file', sha256=hashlib.sha256(content).hexdigest())
                texts[name] = content
            elif member.issym():
                row.update(kind='symlink', target=member.linkname)
            elif member.islnk():
                row.update(kind='hardlink', target=member.linkname.removeprefix('./'))
            elif member.isdir():
                row['kind'] = 'directory'
            else:
                raise ValueError('zlib_c51_unexpected_archive_member')
            assert name not in rows, 'zlib_c51_duplicate_archive_member'
            rows[name] = row
    assert rows or not root_required, 'zlib_c51_empty_archive'
    return rows, texts, order


def inventory(directory):
    debs = sorted(path for path in directory.iterdir() if path.name.endswith('.deb'))
    assert len(debs) == 1, 'zlib_c51_single_deb_required'
    raw = debs[0].read_bytes()
    assert hashlib.sha256(raw).hexdigest() == SHA256, 'zlib_c51_official_deb_digest'
    mount = ['--mount', 'type=bind,source=' + str(directory) + ',target=/debs,readonly']
    run = ['run', '--rm', *ISOLATION, *mount, '--entrypoint', 'dpkg-deb', BUILDER]
    payload, _, order = members(docker(*run, '--fsys-tarfile', '/debs/' + debs[0].name), True)
    control, texts, _ = members(docker(*run, '--ctrl-tarfile', '/debs/' + debs[0].name), True)
    control_text = texts['control'].decode('utf-8')
    values = fields(control_text)
    assert values['Package'] == PACKAGE and values['Version'] == VERSION, 'zlib_c51_control_identity'
    assert values['Architecture'] == 'amd64' and values.get('Multi-Arch') == 'same', 'zlib_c51_control_architecture'
    assert values.get('Source', 'zlib') == 'zlib', 'zlib_c51_control_source'
    assert all(row['kind'] in ('directory', 'file', 'symlink') and
               (path == 'usr' or path.startswith(('usr/lib', 'usr/share'))) for path, row in payload.items()), \
        'zlib_c51_payload_location'
    info = PACKAGE + ':amd64'
    # The exact records builds/c51-postgres/update-status.mjs writes for dpkg.
    listing = '\n'.join('/.' if name in ('.', './') else
                        (name[1:] if name.startswith('./') else name).rstrip('/') or '/.' for name in order) + '\n'
    status = control_text.rstrip() + '\nStatus: install ok installed'
    files = {'var/lib/dpkg/info/' + info + '.' + name: row['sha256'] for name, row in control.items()
             if name != 'control'}
    files['var/lib/dpkg/info/' + info + '.list'] = hashlib.sha256(listing.encode()).hexdigest()
    return {'schema': 'otziv-official-debian-package-v1', 'component': 'postgres', 'advisories': ADVISORIES,
            'distribution': 'debian-13 trixie-security', 'parent': PARENT, 'builder': BUILDER,
            'package': PACKAGE, 'version': VERSION, 'previousVersion': PREVIOUS_VERSION, 'architecture': 'amd64',
            'dpkgInfoName': info, 'url': URL, 'sha256': SHA256, 'bytes': len(raw),
            'controlFields': {key: values.get(key) for key in ('Package', 'Source', 'Version', 'Architecture',
                                                               'Multi-Arch', 'Depends')},
            'conffiles': sorted(texts.get('conffiles', b'').decode('utf-8').split()),
            'control': control, 'payload': payload,
            'dpkgStatusRecord': status, 'dpkgList': listing, 'dpkgInfoFiles': dict(sorted(files.items()))}


def verify_archive(value):
    # Disposable network container of the exact build image; apt verifies
    # InRelease with debian-archive-keyring before apt-cache reports any record.
    script = ('set -eu; apt-get -qq update >/dev/null; apt-get download --print-uris "$1"; echo "--"; '
              'apt-cache show --no-all-versions "$1"')
    output = docker('run', '--rm', '--user', '0', '--cap-drop', 'ALL', '--cap-add', 'CHOWN', '--cap-add', 'DAC_OVERRIDE',
                    '--cap-add', 'FOWNER', '--cap-add', 'SETUID', '--cap-add', 'SETGID', '--security-opt',
                    'no-new-privileges:true', '--entrypoint', 'sh', BUILDER, '-c', script, 'verify',
                    PACKAGE + '=' + VERSION, timeout=600).decode('utf-8')
    printed, _, text = output.partition('\n--\n')
    uris = [line.split() for line in printed.splitlines() if line.startswith("'")]
    assert len(uris) == 1, 'zlib_c51_archive_uri_count'
    uri, _, size, digest = uris[0]
    record = fields(text.strip())
    assert record['Package'] == PACKAGE and record['Version'] == VERSION, 'zlib_c51_archive_identity'
    assert record['Filename'] == FILENAME, 'zlib_c51_archive_pool'
    assert record['SHA256'] == value['sha256'] == digest.removeprefix('SHA256:'), 'zlib_c51_archive_sha256'
    assert int(record['Size']) == int(size) == value['bytes'], 'zlib_c51_archive_size'
    assert uri.strip("'").replace('http://', 'https://', 1) == value['url'], 'zlib_c51_archive_url'
    value['signedArchiveIndex'] = {'verifiedWith': 'apt-get update, apt-get download --print-uris and apt-cache show '
                                                   'in the disposable pinned trixie build image',
                                   'suite': 'trixie-security', 'uri': uri.strip("'"), 'filename': record['Filename'],
                                   'size': int(record['Size']), 'sha256': record['SHA256']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--deb-directory', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--verify-archive', action='store_true')
    args = parser.parse_args()
    value = inventory(args.deb_directory.resolve(strict=True))
    if args.verify_archive:
        verify_archive(value)
    args.output.write_bytes((json.dumps(value, indent=2, sort_keys=True) + '\n').encode())
    print(json.dumps({'package': PACKAGE, 'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest(),
                      'payload': len(value['payload']), 'infoFiles': sorted(value['dpkgInfoFiles'])}))


if __name__ == '__main__':
    main()
