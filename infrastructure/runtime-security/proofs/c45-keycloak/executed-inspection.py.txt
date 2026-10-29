"""Stopped-image proof of the C43 -> C45 official Ubuntu Brotli-only update."""
import argparse
import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tarfile
import tempfile
import uuid

PARENT = 'ghcr.io/claidd/otziv-security@sha256:6df30e829eec786f9f7b51f56a5e0c10451e96b4ff086157129c649df80632f1'
PARENT_CONFIG = 'sha256:8ff19b507872c8b27b444184c93bae61a2835ed889b410324e7436923d57c240'
PACKAGE_INVENTORY_SHA = '8ea4699bd36f21de6174106ffa18fd76fb6fab34a3d34deeb3bdd61b4fd62b0a'
PACKAGE_SHA = '908295e77a65219a62114c3c79690a3c99712274d1317c3ba47ffa2f8ea52ca5'
VERSION = '1.0.9-2ubuntu0.1'
PREFIX = 'var/lib/dpkg/info/libbrotli1:amd64.'
METADATA = {'etc/ld.so.cache', 'var/cache/ldconfig/aux-cache', 'var/lib/dpkg/status',
            'var/lib/dpkg/status-old', 'var/log/dpkg.log', 'var/lib/dpkg/lock', 'var/lib/dpkg/lock-frontend'}
spec = importlib.util.spec_from_file_location('brotli_c45_base', Path(__file__).with_name('ssl-refresh-inspection.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
hash_bytes = lambda value: hashlib.sha256(value).hexdigest()


def inspect(reference, directory):
    image = json.loads(base.docker('image', 'inspect', reference))[0]
    owner = 'otziv-brotli-inspection-' + uuid.uuid4().hex
    base.docker('create', '--name', owner, '--label', 'com.otziv.brotli-inspection.owner=' + owner,
                '--network', 'none', '--entrypoint', '/not-executed', reference)
    try:
        path = directory / (owner + '.tar')
        base.docker('export', '--output', str(path), owner)
        inventory, captured = {}, {}
        with tarfile.open(path) as archive:
            for member in archive:
                name = member.name.removeprefix('./').rstrip('/')
                assert name and not name.startswith('/') and '..' not in name.split('/')
                if name in base.GENERATED or name == 'dev' or name.startswith('dev/'): continue
                row = {'mode': member.mode, 'uid': member.uid, 'gid': member.gid}
                if member.isfile():
                    digest, content = hashlib.sha256(), bytearray()
                    capture = name == 'var/lib/dpkg/status' or name == PREFIX + 'list'
                    with archive.extractfile(member) as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                            digest.update(chunk)
                            if capture: content.extend(chunk)
                    row.update(kind='file', sha256=digest.hexdigest())
                    if capture: captured[name] = content.decode('utf-8')
                elif member.issym() or member.islnk():
                    row.update(kind='symlink' if member.issym() else 'hardlink', target=member.linkname)
                elif member.isdir(): row['kind'] = 'directory'
                else: raise ValueError('unexpected_filesystem_object')
                assert name not in inventory
                inventory[name] = row
        return {'reference': reference, 'imageId': image['Id'], 'rootfs': image['RootFS']['Layers'],
                'configuration': {key: image['Config'].get(key) for key in base.FIELDS},
                'inventory': inventory, 'captured': captured, 'containerExecuted': False}
    finally:
        container = json.loads(base.docker('inspect', owner))[0]
        assert container['Config']['Labels']['com.otziv.brotli-inspection.owner'] == owner and not container['State']['Running']
        base.docker('rm', '-v', owner)


def verify(before, after, commit, package):
    assert before['reference'] == PARENT and before['imageId'] in (PARENT_CONFIG, PARENT.split('@')[1])
    assert re.fullmatch('[a-f0-9]{40}', commit)
    assert not before['containerExecuted'] and not after['containerExecuted']
    assert after['rootfs'][:len(before['rootfs'])] == before['rootfs'] and len(after['rootfs']) > len(before['rootfs'])
    expected = copy.deepcopy(before['configuration'])
    expected['Labels'].update({'otziv.brotli': VERSION, 'otziv.brotli-advisory': 'CVE-2026-111497',
                               'com.otziv.publication.revision': commit})
    assert expected == after['configuration'], 'brotli_c45_unrelated_configuration'
    assert package['sha256'] == PACKAGE_SHA and package['package'] == 'libbrotli1' and package['version'] == VERSION
    old_packages, new_packages = [base.packages(image['captured']['var/lib/dpkg/status']) for image in (before, after)]
    assert {k:v for k,v in old_packages.items() if k != 'libbrotli1'} == {k:v for k,v in new_packages.items() if k != 'libbrotli1'}, 'brotli_c45_unrelated_package'
    assert old_packages['libbrotli1']['Version'] == '1.0.9-2build6'
    assert new_packages['libbrotli1']['Version'] == VERSION and new_packages['libbrotli1']['Status'] == 'install ok installed'
    for path, expected_row in package['payload'].items():
        assert after['inventory'][path] == expected_row, 'brotli_c45_official_payload:' + path
    for name, expected_row in package['control'].items():
        if name != 'control': assert after['inventory'][PREFIX + name] == expected_row, 'brotli_c45_official_control:' + name
    listed = {line.lstrip('/').rstrip('/') for line in after['captured'][PREFIX + 'list'].splitlines()} - {'.', ''}
    assert listed == set(package['payload']), 'brotli_c45_package_manifest'
    control_paths = {PREFIX + name for name in package['control'] if name != 'control'} | {PREFIX + 'list'}
    allowed = set(package['payload']) | control_paths | METADATA
    changed = sorted(path for path in before['inventory'].keys() | after['inventory'].keys()
                     if before['inventory'].get(path) != after['inventory'].get(path))
    assert any(path.startswith('usr/lib/') for path in changed), 'brotli_c45_library_not_changed'
    assert not set(changed) - allowed, 'brotli_c45_unrelated_payload:' + ','.join(sorted(set(changed) - allowed)[:15])
    return changed


def main():
    parser = argparse.ArgumentParser()
    for name in ('candidate', 'publication-commit', 'temporary-directory', 'output'): parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    assert re.fullmatch(r'ghcr\.io/claidd/otziv-security@sha256:[a-f0-9]{64}', args.candidate)
    temporary_parent, output = Path(args.temporary_directory).resolve(strict=True), Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    package_bytes = Path(__file__).with_name('c45-brotli-package.json').read_bytes()
    assert hash_bytes(package_bytes) == PACKAGE_INVENTORY_SHA
    with tempfile.TemporaryDirectory(prefix='brotli-c45-owned-', dir=temporary_parent) as directory:
        temporary = Path(directory).resolve()
        assert temporary.is_relative_to(temporary_parent) and temporary != temporary_parent
        before, after = [inspect(reference, temporary) for reference in (PARENT, args.candidate)]
        changed = verify(before, after, args.publication_commit, json.loads(package_bytes))
        payload = (json.dumps({'schema': 'otziv-keycloak-brotli-c45-inventory-v1', 'parent': before, 'candidate': after}, sort_keys=True, separators=(',', ':')) + '\n').encode()
        (output / 'runtime.json.gz').write_bytes(gzip.compress(payload, mtime=0))
        receipt = {'schema': 'otziv-keycloak-brotli-c45-inspection-v1', 'result': 'PASS',
                   'parent': PARENT, 'parentImageId': before['imageId'], 'candidate': args.candidate,
                   'candidateImageId': after['imageId'], 'runtimeSha256': hash_bytes(payload),
                   'changedFiles': changed, 'packageInventorySha256': hash_bytes(package_bytes),
                   'inspectedContainersExecuted': False, 'executedScriptSha256': hash_bytes(Path(__file__).read_bytes()),
                   'inventoryScriptSha256': hash_bytes(Path(__file__).with_name('ssl-refresh-inspection.py').read_bytes())}
        (output / 'inspection.json').write_bytes((json.dumps(receipt, indent=2) + '\n').encode())
        print(json.dumps(receipt))


if __name__ == '__main__': main()
