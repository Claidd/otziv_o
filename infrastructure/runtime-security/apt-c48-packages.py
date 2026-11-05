"""Record the exact official Ubuntu apt packages used by the C48 overlays.

The .deb bytes are pinned by SHA-256. Their payload and control members are read
with dpkg-deb in a disposable, network-less container of the accepted parent
image (the binary package format is never parsed by hand). With --verify-archive
an additional disposable parent container authenticates Ubuntu's signed archive
index with the image's own ubuntu-keyring and must report the same file, size
and SHA-256 for every package.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

ADVISORY = 'CVE-2026-109518'
POOL = 'https://security.ubuntu.com/ubuntu/pool/main/a/apt/'
COMPONENTS = {
    'keycloak': {
        'parent': 'ghcr.io/claidd/otziv-security@sha256:6c6f398170954330c03dc2c16db1b96c5ebf2cb29546fe59542b6dcc7b60ee5e',
        'distribution': 'ubuntu-22.04', 'version': '2.4.15ubuntu0.1', 'previousVersion': '2.4.14',
        'packages': [('libapt-pkg6.0', '3a6fbab029e15ca7b8da642c17ec110995a6e3a0bc40eaa08e239cfc94d6c426'),
                     ('apt', '2d4db9a3017c5364e50af5bc2f0c76828b52933b682eff4728f1211e081c6736')]},
    'alloy': {
        'parent': 'ghcr.io/claidd/otziv-security@sha256:27905df1862decf6e9c2543cb476e70e83d68351f7a5bb17194a6a094764db32',
        'distribution': 'ubuntu-24.04', 'version': '2.8.4ubuntu0.1', 'previousVersion': '2.8.3',
        'packages': [('libapt-pkg6.0t64', 'a50f3e68a793f90b05f44a4b5f2f59f3ec7687e39b5d8bbd1da44c84671fa3d6'),
                     ('apt', 'ad2ea7f107f250ba88b0bfdba31812a341e3247589647aa2b03b9dc4e8abf8ed')]},
}
ISOLATION = ['--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
             '--memory', '512m', '--pids-limit', '64', '--user', '65534:65534']


def docker(*args, timeout=300):
    return subprocess.run(['docker', *args], check=True, capture_output=True, timeout=timeout).stdout


def fields(text):
    values, key = {}, None
    for line in text.splitlines():
        if line[:1] in (' ', '\t'):
            assert key, 'apt_c48_control_continuation'
            values[key] += '\n' + line
        elif line:
            key, value = line.split(':', 1)
            assert key not in values, 'apt_c48_control_duplicate_field'
            values[key] = value.strip()
    return values


def members(stream, root_required):
    rows, texts = {}, {}
    with tarfile.open(fileobj=io.BytesIO(stream), mode='r:') as archive:
        for member in archive:
            name = member.name.removeprefix('./').rstrip('/')
            if name in ('', '.'):
                assert member.isdir(), 'apt_c48_archive_root'
                continue
            assert not name.startswith('/') and '..' not in name.split('/'), 'apt_c48_archive_path'
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
                raise ValueError('apt_c48_unexpected_archive_member')
            assert name not in rows, 'apt_c48_duplicate_archive_member'
            rows[name] = row
    assert rows or not root_required, 'apt_c48_empty_archive'
    return rows, texts


def inventory(component, directory):
    spec = COMPONENTS[component]
    mount = ['--mount', 'type=bind,source=' + str(directory) + ',target=/debs,readonly']
    records = []
    for package, digest in spec['packages']:
        name = f"{package}_{spec['version']}_amd64.deb"
        raw = (directory / name).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == digest, 'apt_c48_official_deb_digest:' + name
        run = ['run', '--rm', *ISOLATION, *mount, '--entrypoint', 'dpkg-deb', spec['parent']]
        payload, _ = members(docker(*run, '--fsys-tarfile', '/debs/' + name), True)
        control, texts = members(docker(*run, '--ctrl-tarfile', '/debs/' + name), True)
        values = fields(texts['control'].decode('utf-8'))
        assert values['Package'] == package and values['Version'] == spec['version'], 'apt_c48_control_identity'
        assert values['Architecture'] == 'amd64', 'apt_c48_control_architecture'
        assert values.get('Source', 'apt') == 'apt', 'apt_c48_control_source'
        info = package + (':amd64' if values.get('Multi-Arch') == 'same' else '')
        records.append({'package': package, 'version': spec['version'], 'previousVersion': spec['previousVersion'],
                        'architecture': 'amd64', 'dpkgInfoName': info, 'url': POOL + name, 'sha256': digest,
                        'bytes': len(raw), 'controlFields': {key: values[key] for key in
                                                             ('Package', 'Version', 'Architecture', 'Depends')},
                        'conffiles': sorted(texts.get('conffiles', b'').decode('utf-8').split()),
                        'control': control, 'payload': payload})
    return {'schema': 'otziv-official-apt-packages-v1', 'component': component, 'advisory': ADVISORY,
            'distribution': spec['distribution'], 'parent': spec['parent'], 'packages': records}


def verify_archive(component, value):
    # Disposable network container of the exact parent; apt verifies InRelease
    # with the image's ubuntu-keyring before apt-cache reports any record.
    spec = COMPONENTS[component]
    script = 'set -eu; apt-get -qq update >/dev/null; for p in "$@"; do apt-cache show --no-all-versions "$p"; echo; done'
    wanted = [item['package'] + '=' + item['version'] for item in value['packages']]
    output = docker('run', '--rm', '--user', '0', '--cap-drop', 'ALL', '--cap-add', 'CHOWN', '--cap-add', 'DAC_OVERRIDE',
                    '--cap-add', 'FOWNER', '--cap-add', 'SETUID', '--cap-add', 'SETGID', '--security-opt',
                    'no-new-privileges:true', '--entrypoint', 'sh', spec['parent'], '-c', script, 'verify', *wanted,
                    timeout=600).decode('utf-8')
    records = [fields(block) for block in output.strip().split('\n\n') if block.strip()]
    assert len(records) == len(value['packages']), 'apt_c48_archive_record_count'
    for item, record in zip(value['packages'], records):
        assert record['Package'] == item['package'] and record['Version'] == item['version'], 'apt_c48_archive_identity'
        assert record['SHA256'] == item['sha256'], 'apt_c48_archive_sha256'
        assert int(record['Size']) == item['bytes'], 'apt_c48_archive_size'
        assert POOL.removeprefix('https://security.ubuntu.com/ubuntu/') + record['Filename'].split('/')[-1] == \
            record['Filename'], 'apt_c48_archive_pool'
        item['signedArchiveIndex'] = {'verifiedWith': 'apt-get update in the disposable parent image',
                                      'filename': record['Filename'], 'size': int(record['Size']),
                                      'sha256': record['SHA256']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--component', choices=sorted(COMPONENTS), required=True)
    parser.add_argument('--deb-directory', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--verify-archive', action='store_true')
    args = parser.parse_args()
    value = inventory(args.component, args.deb_directory.resolve(strict=True))
    if args.verify_archive:
        verify_archive(args.component, value)
    args.output.write_bytes((json.dumps(value, indent=2, sort_keys=True) + '\n').encode())
    print(json.dumps({'component': args.component, 'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest(),
                      'packages': {item['package']: len(item['payload']) for item in value['packages']}}))


if __name__ == '__main__':
    main()
