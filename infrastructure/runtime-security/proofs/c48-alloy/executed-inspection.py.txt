"""Stopped-image proof of the C48 official Ubuntu apt-only updates (Keycloak C45 -> C48, Alloy C43 -> C48)."""
import argparse
import copy
import fnmatch
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
import tempfile
import uuid

ADVISORY = 'CVE-2026-109518'
SPEC = {
    'keycloak': {
        'parent': 'ghcr.io/claidd/otziv-security@sha256:6c6f398170954330c03dc2c16db1b96c5ebf2cb29546fe59542b6dcc7b60ee5e',
        'parentConfig': 'sha256:ba4b2b070f6b58a0f74725860b143e722372a556002e28079654ce2be44048b3',
        'inventorySha256': 'cda895082b4c69c324d248a6e2c07da90d4bb830440a1d932dd20ee8fdce21ad',
        'version': '2.4.15ubuntu0.1', 'previous': '2.4.14',
        'packages': {'libapt-pkg6.0': 'libapt-pkg6.0:amd64', 'apt': 'apt'},
        'protected': ['opt/keycloak/providers/otziv-security-generation.jar', 'opt/keycloak/bin/kc.sh'],
    },
    'alloy': {
        'parent': 'ghcr.io/claidd/otziv-security@sha256:27905df1862decf6e9c2543cb476e70e83d68351f7a5bb17194a6a094764db32',
        'parentConfig': 'sha256:a40087d44026f83768b1d6c6a94005478bbc357c56e0943ef8b63ac4ba486aa5',
        'inventorySha256': '8acf21f15177a65942b580739aa97908a89b9709f22e8a8f40474beb8edcf5ec',
        'version': '2.8.4ubuntu0.1', 'previous': '2.8.3',
        'packages': {'libapt-pkg6.0t64': 'libapt-pkg6.0t64:amd64', 'apt': 'apt'},
        'protected': ['usr/bin/alloy', 'usr/share/otziv-build/alloy.packages.txt'],
    },
}
# Fixed dpkg path filters of both parent images; their bytes are compared below.
DPKG_CONFIG = {'etc/dpkg/dpkg.cfg': 'fead43b89af3ea5691c48f32d7fe1ba0f7ab229fb5d230f612d76fe8e6f5a015',
               'etc/dpkg/dpkg.cfg.d/docker-apt-speedup': '94372f4a7672c283c0fe956b0638ec8731e21584d880ba782a1a338b906caeb1',
               'etc/dpkg/dpkg.cfg.d/excludes': '61159013263411a7c7a4157a6ddcd4eca3329ee698e508850070af11338098db'}
METADATA = {'etc/ld.so.cache', 'var/cache/ldconfig/aux-cache', 'var/lib/dpkg/status',
            'var/lib/dpkg/status-old', 'var/log/dpkg.log', 'var/lib/dpkg/lock', 'var/lib/dpkg/lock-frontend'}
spec = importlib.util.spec_from_file_location('apt_c48_base', Path(__file__).with_name('ssl-refresh-inspection.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
hash_bytes = lambda value: hashlib.sha256(value).hexdigest()


def captured_name(name, packages):
    return (name == 'var/lib/dpkg/status' or name.startswith('etc/dpkg/dpkg.cfg')
            or any(name == 'var/lib/dpkg/info/' + info + '.list' for info in packages.values()))


def inspect(reference, directory, packages):
    image = json.loads(base.docker('image', 'inspect', reference))[0]
    owner = 'otziv-apt-c48-inspection-' + uuid.uuid4().hex
    base.docker('create', '--name', owner, '--label', 'com.otziv.apt-c48-inspection.owner=' + owner,
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
                    capture = captured_name(name, packages)
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
        assert container['Config']['Labels']['com.otziv.apt-c48-inspection.owner'] == owner and not container['State']['Running']
        base.docker('rm', '-v', owner)


def filters(image):
    """dpkg reads dpkg.cfg.d fragments in name order, then dpkg.cfg; the last matching filter wins."""
    names = sorted(name for name in image['captured'] if name.startswith('etc/dpkg/dpkg.cfg.d/'))
    rules = []
    for name in names + ['etc/dpkg/dpkg.cfg']:
        for line in image['captured'].get(name, '').splitlines():
            line = line.strip()
            if line.startswith(('path-exclude', 'path-include')):
                kind, pattern = re.fullmatch(r'(path-exclude|path-include)[= ](/\S+)', line).groups()
                assert not set('[]\\') & set(pattern), 'apt_c48_unsupported_dpkg_filter'
                rules.append((kind, pattern))
    return rules


def excluded(path, rules):
    result = False
    for kind, pattern in rules:
        if fnmatch.fnmatchcase('/' + path, pattern): result = kind == 'path-exclude'
    return result


def resolve(path, inventory):
    """Resolve parent-directory symlinks (merged /usr), never the final component."""
    parts, resolved = PurePosixPath(path).parts, []
    for index, part in enumerate(parts):
        resolved.append(part)
        current = '/'.join(resolved)
        if index < len(parts) - 1 and inventory.get(current, {}).get('kind') == 'symlink':
            target = inventory[current]['target']
            joined = PurePosixPath(target.lstrip('/')) if target.startswith('/') else PurePosixPath(*resolved[:-1], target)
            normalized = []
            for item in joined.parts:
                if item == '..': normalized.pop()
                elif item != '.': normalized.append(item)
            resolved = normalized
    return '/'.join(resolved)


def verify(component, before, after, commit, package):
    item = SPEC[component]
    assert before['reference'] == item['parent'] and before['imageId'] in (item['parentConfig'], item['parent'].split('@')[1])
    assert re.fullmatch('[a-f0-9]{40}', commit)
    assert not before['containerExecuted'] and not after['containerExecuted']
    assert after['rootfs'][:len(before['rootfs'])] == before['rootfs'] and len(after['rootfs']) > len(before['rootfs'])
    expected = copy.deepcopy(before['configuration'])
    expected['Labels'].update({'otziv.apt': item['version'], 'otziv.apt-advisory': ADVISORY,
                               'com.otziv.publication.revision': commit})
    assert expected == after['configuration'], 'apt_c48_unrelated_configuration'
    assert package['schema'] == 'otziv-official-apt-packages-v1' and package['component'] == component
    assert package['parent'] == item['parent'] and package['advisory'] == ADVISORY
    assert {entry['package']: entry['dpkgInfoName'] for entry in package['packages']} == item['packages'], 'apt_c48_package_set'
    for name, digest in DPKG_CONFIG.items():
        assert before['inventory'].get(name) == after['inventory'].get(name), 'apt_c48_dpkg_configuration_changed'
        assert before['captured'].get(name) == after['captured'].get(name), 'apt_c48_dpkg_configuration_changed'
        assert after['inventory'][name]['sha256'] == digest, 'apt_c48_dpkg_configuration'
    for image in (before, after):
        configs = sorted(name for name in image['inventory'] if name.startswith('etc/dpkg/dpkg.cfg.d/'))
        assert configs == sorted(name for name in DPKG_CONFIG if name.startswith('etc/dpkg/dpkg.cfg.d/')), 'apt_c48_dpkg_fragments'
    for name in DPKG_CONFIG:
        assert hash_bytes(after['captured'][name].encode()) == after['inventory'][name]['sha256'], 'apt_c48_captured_metadata_digest'
    rules = filters(after)
    old_packages, new_packages = [base.packages(image['captured']['var/lib/dpkg/status']) for image in (before, after)]
    names = set(item['packages'])
    assert {k: v for k, v in old_packages.items() if k not in names} == {k: v for k, v in new_packages.items() if k not in names}, 'apt_c48_unrelated_package'
    allowed = set(METADATA)
    for entry in package['packages']:
        name, info = entry['package'], entry['dpkgInfoName']
        assert entry['version'] == item['version'] and entry['previousVersion'] == item['previous']
        assert old_packages[name]['Version'] == item['previous'], 'apt_c48_source_package'
        assert new_packages[name]['Version'] == item['version'] and new_packages[name]['Status'] == 'install ok installed', 'apt_c48_target_package'
        prefix = 'var/lib/dpkg/info/' + info + '.'
        old_listed = {line.strip('/') for line in before['captured'][prefix + 'list'].splitlines()} - {'.', ''}
        listed = {line.strip('/') for line in after['captured'][prefix + 'list'].splitlines()} - {'.', ''}
        assert listed == set(entry['payload']), 'apt_c48_package_manifest'
        for path, row in entry['payload'].items():
            actual = resolve(path, after['inventory'])
            assert actual == resolve(path, before['inventory']), 'apt_c48_path_resolution_changed'
            allowed.add(actual)
            if row['kind'] == 'directory':
                if actual in before['inventory']:
                    assert after['inventory'].get(actual) == before['inventory'][actual], 'apt_c48_existing_directory:' + path
                elif actual in after['inventory']:
                    assert after['inventory'][actual] == row, 'apt_c48_official_directory:' + path
                else:
                    assert excluded(path, rules), 'apt_c48_missing_directory:' + path
            elif excluded(path, rules):
                assert actual not in after['inventory'], 'apt_c48_filtered_path_present:' + path
                assert actual not in before['inventory'] or path in old_listed, 'apt_c48_filtered_path_owner:' + path
            else:
                assert after['inventory'].get(actual) == row, 'apt_c48_official_payload:' + path
        for control, row in entry['control'].items():
            if control != 'control':
                assert after['inventory'].get(prefix + control) == row, 'apt_c48_official_control:' + control
                allowed.add(prefix + control)
        allowed.add(prefix + 'list')
        for image in (before, after):
            assert hash_bytes(image['captured'][prefix + 'list'].encode()) == image['inventory'][prefix + 'list']['sha256'], 'apt_c48_captured_metadata_digest'
    for image in (before, after):
        assert hash_bytes(image['captured']['var/lib/dpkg/status'].encode()) == image['inventory']['var/lib/dpkg/status']['sha256'], 'apt_c48_captured_metadata_digest'
    changed = sorted(path for path in before['inventory'].keys() | after['inventory'].keys()
                     if before['inventory'].get(path) != after['inventory'].get(path))
    assert any(path.startswith('usr/lib/x86_64-linux-gnu/libapt-pkg.so.') for path in changed), 'apt_c48_library_not_changed'
    assert not set(changed) - allowed, 'apt_c48_unrelated_payload:' + ','.join(sorted(set(changed) - allowed)[:15])
    for path in item['protected']:
        assert after['inventory'][path] == before['inventory'][path] and path not in changed, 'apt_c48_protected_changed:' + path
    return changed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--component', choices=sorted(SPEC), required=True)
    for name in ('candidate', 'publication-commit', 'temporary-directory', 'output'): parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    item = SPEC[args.component]
    assert re.fullmatch(r'ghcr\.io/claidd/otziv-security@sha256:[a-f0-9]{64}', args.candidate)
    temporary_parent, output = Path(args.temporary_directory).resolve(strict=True), Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    package_bytes = Path(__file__).with_name('c48-' + args.component + '-apt-packages.json').read_bytes()
    assert hash_bytes(package_bytes) == item['inventorySha256']
    with tempfile.TemporaryDirectory(prefix='apt-c48-owned-', dir=temporary_parent) as directory:
        temporary = Path(directory).resolve()
        assert temporary.is_relative_to(temporary_parent) and temporary != temporary_parent
        before, after = [inspect(reference, temporary, item['packages']) for reference in (item['parent'], args.candidate)]
        changed = verify(args.component, before, after, args.publication_commit, json.loads(package_bytes))
        payload = (json.dumps({'schema': 'otziv-apt-c48-inventory-v1', 'component': args.component,
                               'parent': before, 'candidate': after}, sort_keys=True, separators=(',', ':')) + '\n').encode()
        (output / 'runtime.json.gz').write_bytes(gzip.compress(payload, mtime=0))
        receipt = {'schema': 'otziv-apt-c48-inspection-v1', 'result': 'PASS', 'component': args.component,
                   'parent': item['parent'], 'parentImageId': before['imageId'], 'candidate': args.candidate,
                   'candidateImageId': after['imageId'], 'runtimeSha256': hash_bytes(payload),
                   'changedFiles': changed, 'packageInventorySha256': hash_bytes(package_bytes),
                   'inspectedContainersExecuted': False, 'executedScriptSha256': hash_bytes(Path(__file__).read_bytes()),
                   'inventoryScriptSha256': hash_bytes(Path(__file__).with_name('ssl-refresh-inspection.py').read_bytes())}
        (output / 'inspection.json').write_bytes((json.dumps(receipt, indent=2) + '\n').encode())
        print(json.dumps(receipt))


if __name__ == '__main__': main()
