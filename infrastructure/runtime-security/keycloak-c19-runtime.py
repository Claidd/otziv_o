"""Compare stopped parent/candidate images without executing their entrypoints."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import uuid

FIELDS = ['User', 'WorkingDir', 'Entrypoint', 'Cmd', 'Env', 'Healthcheck', 'ExposedPorts', 'Volumes']


def docker(*arguments):
    return subprocess.check_output(['docker', *arguments])


def inspect_image(reference, registry_config, directory):
    expected = json.loads(Path(registry_config).read_bytes())
    config_id = 'sha256:' + hashlib.sha256(Path(registry_config).read_bytes()).hexdigest()
    installed = json.loads(docker('image', 'inspect', reference))[0]
    assert installed['RootFS']['Layers'] == expected['rootfs']['diff_ids']
    assert all(installed['Config'].get(field) == expected['config'].get(field) for field in FIELDS)
    owner = uuid.uuid4().hex
    name = 'otziv-kc-c19-inspect-' + owner
    label = 'otziv.c19.inspection.owner'
    docker('create', '--name', name, '--label', label + '=' + owner, '--network', 'none', '--entrypoint', 'sh', reference)
    try:
        exported = directory / (owner + '.tar')
        docker('export', '--output', str(exported), name)
        files = {}
        with tarfile.open(exported) as archive:
            for member in archive:
                if not member.name.startswith('opt/keycloak/') or member.isdir():
                    continue
                row = {'mode': member.mode, 'uid': member.uid, 'gid': member.gid}
                if member.isfile():
                    value = hashlib.sha256()
                    with archive.extractfile(member) as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                            value.update(chunk)
                    row.update(kind='file', sha256=value.hexdigest())
                elif member.issym() or member.islnk():
                    row.update(kind='symlink' if member.issym() else 'hardlink', target=member.linkname)
                else:
                    raise ValueError('Unexpected Keycloak filesystem object')
                files[member.name] = row
        return {'reference': reference, 'configId': config_id, 'inspectedId': installed['Id'],
                'registryRootfsAndConfigMatch': True, 'containerExecuted': False,
                'rootfs': installed['RootFS']['Layers'],
                'configuration': {field: installed['Config'].get(field) for field in FIELDS}, 'files': files}
    finally:
        container = json.loads(docker('inspect', name))[0]
        assert container['Config']['Labels'][label] == owner and not container['State']['Running']
        docker('rm', name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ['parent', 'candidate', 'parent-config', 'candidate-config', 'output', 'temporary-directory']:
        parser.add_argument('--' + key, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='c19-inspection-', dir=args.temporary_directory) as temporary:
        directory = Path(temporary)
        parent = inspect_image(args.parent, args.parent_config, directory)
        candidate = inspect_image(args.candidate, args.candidate_config, directory)
    assert parent['configuration'] == candidate['configuration']
    assert candidate['rootfs'][:len(parent['rootfs'])] == parent['rootfs']
    changed = sorted(path for path in parent['files'].keys() | candidate['files'].keys()
                     if parent['files'].get(path) != candidate['files'].get(path))
    result = {'schema': 'otziv-keycloak-c19-runtime-v1', 'result': 'PASS', 'productionAccess': False,
              'ownedContainersRemaining': 0, 'preservedConfigFields': FIELDS,
              'executedScriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'images': [parent, candidate], 'changedFiles': changed}
    Path(args.output).write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'result': 'PASS', 'changedFiles': changed}))


if __name__ == '__main__':
    main()
