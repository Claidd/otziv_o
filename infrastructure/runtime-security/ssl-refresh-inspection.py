"""Inspect stopped parent/child images; accept only an exact OpenSSL package overlay."""
import argparse, gzip, hashlib, json, pathlib, subprocess, tarfile, tempfile, uuid

FIELDS = ['User', 'WorkingDir', 'Entrypoint', 'Cmd', 'Env', 'Healthcheck', 'ExposedPorts', 'Volumes', 'StopSignal', 'Labels']
PACKAGES = {'postgres': {'libssl3t64': '3.5.7-1~deb13u3', 'openssl': '3.5.7-1~deb13u3', 'openssl-provider-legacy': '3.5.7-1~deb13u3'},
            'keycloak': {'libssl3': '3.0.2-0ubuntu1.30', 'openssl': '3.0.2-0ubuntu1.30'},
            'alloy': {'libssl3t64': '3.0.13-0ubuntu3.16', 'openssl': '3.0.13-0ubuntu3.16'}}
METADATA = {'etc/ld.so.cache', 'var/cache/ldconfig/aux-cache', 'var/lib/apt/extended_states', 'var/lib/dpkg/status',
            'var/lib/dpkg/status-old', 'var/log/apt/history.log', 'var/log/apt/term.log', 'var/log/dpkg.log',
            'var/log/alternatives.log', 'var/lib/dpkg/lock', 'var/lib/dpkg/lock-frontend',
            'var/cache/debconf/templates.dat-old', 'var/log/apt/eipp.log.xz'}
GENERATED = {'etc/hosts', 'etc/hostname', 'etc/resolv.conf', '.dockerenv'}

def docker(*args):
    return subprocess.check_output(['docker', *args], stderr=subprocess.PIPE, timeout=300)

def packages(status):
    result = {}
    for block in status.strip().split('\n\n'):
        fields, key = {}, None
        for line in block.splitlines():
            if line.startswith(' ') and key:
                fields[key] += '\n' + line
            elif ':' in line:
                key, value = line.split(':', 1); fields[key] = value.lstrip()
        if fields:
            assert fields['Package'] not in result
            result[fields['Package']] = fields
    return result

def inspect(reference, directory):
    image = json.loads(docker('image', 'inspect', reference))[0]
    owner = 'otziv-ssl-inspection-' + uuid.uuid4().hex
    docker('create', '--name', owner, '--label', 'otziv.ssl.inspection.owner=' + owner,
           '--network', 'none', '--entrypoint', '/not-executed', reference)
    try:
        path = directory / (owner + '.tar'); docker('export', '--output', str(path), owner)
        inventory, captured = {}, {}
        with tarfile.open(path) as archive:
            for member in archive:
                name = member.name.removeprefix('./').rstrip('/')
                assert name and not name.startswith('/') and '..' not in name.split('/')
                if name in GENERATED or name == 'dev' or name.startswith('dev/') or member.isdir(): continue
                row = {'mode': member.mode, 'uid': member.uid, 'gid': member.gid}
                if member.isfile():
                    value = hashlib.sha256()
                    capture = name == 'var/lib/dpkg/status' or (name.startswith('var/lib/dpkg/info/') and name.endswith('.list'))
                    content = bytearray()
                    with archive.extractfile(member) as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                            value.update(chunk)
                            if capture: content.extend(chunk)
                    row.update(kind='file', sha256=value.hexdigest())
                    if capture: captured[name] = content.decode()
                elif member.issym() or member.islnk():
                    row.update(kind='symlink' if member.issym() else 'hardlink', target=member.linkname)
                else: raise ValueError('unexpected_filesystem_object')
                assert name not in inventory; inventory[name] = row
        return {'reference': reference, 'imageId': image['Id'], 'rootfs': image['RootFS']['Layers'],
                'configuration': {key: image['Config'].get(key) for key in FIELDS}, 'inventory': inventory,
                'captured': captured, 'containerExecuted': False}
    finally:
        container = json.loads(docker('inspect', owner))[0]
        assert container['Config']['Labels']['otziv.ssl.inspection.owner'] == owner and not container['State']['Running']
        docker('rm', '-v', owner)

def verify(parent, candidate, component):
    assert parent['configuration'] == candidate['configuration'], 'ssl_refresh_config_changed'
    assert candidate['rootfs'][:len(parent['rootfs'])] == parent['rootfs'], 'ssl_refresh_parent_layers_changed'
    assert len(candidate['rootfs']) > len(parent['rootfs']), 'ssl_refresh_missing_overlay'
    expected = PACKAGES[component]
    before, after = [packages(x['captured']['var/lib/dpkg/status']) for x in (parent, candidate)]
    assert {k:v for k,v in before.items() if k not in expected} == {k:v for k,v in after.items() if k not in expected}, 'ssl_refresh_unrelated_package_changed'
    for package, version in expected.items():
        assert after[package]['Version'] == version and after[package]['Status'] == 'install ok installed'
        assert before[package]['Version'] != version, 'ssl_refresh_package_not_updated'
    owned = set()
    for image in (parent, candidate):
        for name, text in image['captured'].items():
            if any(name in ('var/lib/dpkg/info/'+pkg+'.list', 'var/lib/dpkg/info/'+pkg+':amd64.list') for pkg in expected):
                owned.update(x.removeprefix('/').rstrip('/') for x in text.splitlines())
    def allowed(path):
        if path in METADATA: return True
        if any(path.startswith('var/lib/dpkg/info/'+pkg+suffix) for pkg in expected for suffix in ('.', ':amd64.')): return True
        if path.startswith('var/lib/dpkg/triggers/') or path.startswith('var/lib/apt/lists/') or path.startswith('var/cache/apt/'): return True
        # Membership in the authenticated package manifests is necessary, and
        # application payload locations are excluded even if a list is altered.
        return path in owned and not (path.startswith(('usr/local/', 'opt/', 'bin/')) or path == 'usr/bin/alloy')
    changed = sorted(path for path in parent['inventory'].keys() | candidate['inventory'].keys()
                     if parent['inventory'].get(path) != candidate['inventory'].get(path))
    unexpected = [path for path in changed if not allowed(path)]
    assert not unexpected, 'ssl_refresh_unrelated_files_changed:' + ','.join(unexpected[:15])
    return changed

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--component', choices=list(PACKAGES), required=True)
    for name in ['parent', 'candidate', 'output', 'temporary-directory']: parser.add_argument('--'+name, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='ssl-refresh-', dir=args.temporary_directory) as temporary:
        parent, candidate = [inspect(ref, pathlib.Path(temporary)) for ref in (args.parent, args.candidate)]
    changed = verify(parent, candidate, args.component)
    record = {'schema': 'otziv-ssl-refresh-inspection-v1', 'result': 'PASS', 'component': args.component,
              'productionAccess': False, 'ownedContainersRemaining': 0, 'images': [parent, candidate], 'changedFiles': changed,
              'packages': PACKAGES[args.component], 'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
    pathlib.Path(args.output).write_bytes(gzip.compress((json.dumps(record, separators=(',', ':'))+'\n').encode(), mtime=0))
    print(json.dumps({'result': 'PASS', 'component': args.component, 'changedFiles': len(changed)}))

if __name__ == '__main__': main()
