"""Stopped-image C26 proof: only the official core JAR and its shaded CLI payload change."""
import argparse
import copy
import gzip
import hashlib
import importlib.util
import json
import pathlib
import re
import tempfile
import uuid
import zipfile

SOURCE = pathlib.Path(__file__).with_name('ssl-refresh-inspection.py')
PATCH = pathlib.Path(__file__).parent / 'builds/c26-keycloak/patch_cli.py'


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


base = module(SOURCE, 'retained_ssl_inspector')
patch = module(PATCH, 'c26_core_patch')
SERVER = 'opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.jackson-core-2.21.5.jar'
CLI = 'opt/keycloak/bin/client/keycloak-admin-cli-26.7.3.jar'
OLD, NEW, selected = patch.OLD, patch.NEW, patch.selected


def inspect(reference, directory):
    record = base.inspect(reference, directory)
    owner = 'otziv-core-inspection-' + uuid.uuid4().hex
    base.docker('create', '--name', owner, '--label', 'otziv.core.inspection.owner=' + owner,
                '--network', 'none', '--entrypoint', '/not-executed', reference)
    try:
        jars = {}
        for i, name in enumerate([SERVER, CLI]):
            path = directory / (owner + '-' + str(i) + '.jar')
            base.docker('cp', owner + ':/' + name, str(path))
            raw = path.read_bytes()
            assert hashlib.sha256(raw).hexdigest() == record['inventory'][name]['sha256']
            with zipfile.ZipFile(path) as jar:
                names = jar.namelist()
                assert len(names) == len(set(names)), 'core_duplicate_jar_entry'
                entries = {}
                for item in jar.infolist():
                    entries[item.filename] = {
                        'sha256': hashlib.sha256(jar.read(item.filename)).hexdigest(),
                        'size': item.file_size, 'mode': item.external_attr, 'compression': item.compress_type,
                        'dateTime': list(item.date_time), 'comment': item.comment.hex(), 'extra': item.extra.hex(),
                        'internalAttributes': item.internal_attr, 'createSystem': item.create_system,
                        'createVersion': item.create_version, 'extractVersion': item.extract_version,
                        'flags': item.flag_bits,
                    }
                jars[name] = {'sha256': hashlib.sha256(raw).hexdigest(), 'entries': entries}
        record['jars'] = jars
        return record
    finally:
        container = json.loads(base.docker('inspect', owner))[0]
        assert container['Config']['Labels']['otziv.core.inspection.owner'] == owner and not container['State']['Running']
        base.docker('rm', '-v', owner)


def verify(before, after, commit=None):
    expected = copy.deepcopy(before['configuration'])
    if commit is not None:
        assert re.fullmatch('[a-f0-9]{40}', commit), 'core_publication_commit_invalid'
        expected['Labels']['com.otziv.publication.revision'] = commit
    assert after['configuration'] == expected, 'core_launch_changed'
    assert after['rootfs'][:len(before['rootfs'])] == before['rootfs'] and len(after['rootfs']) > len(before['rootfs']), 'core_parent_layers_changed'
    changed = sorted(path for path in before['inventory'].keys() | after['inventory'].keys()
                     if before['inventory'].get(path) != after['inventory'].get(path))
    assert changed == sorted([SERVER, CLI]), 'core_unrelated_file_changed'
    assert before['inventory'][SERVER]['sha256'] == OLD and after['inventory'][SERVER]['sha256'] == NEW, 'core_official_bytes_mismatch'
    for path in [SERVER, CLI]:
        for key in ['mode', 'uid', 'gid', 'kind']:
            assert before['inventory'][path][key] == after['inventory'][path][key], 'core_file_metadata_changed'
    for image in [before, after]:
        for path in [SERVER, CLI]:
            assert image['jars'][path]['sha256'] == image['inventory'][path]['sha256'], 'core_jar_inventory_mismatch'
        payload = lambda values: {path: {key: value[key] for key in ['sha256', 'size']}
                                  for path, value in values.items() if selected(path)}
        cli, server = image['jars'][CLI]['entries'], image['jars'][SERVER]['entries']
        assert payload(cli) and payload(cli) == payload(server), 'core_cli_payload_mismatch'
    assert {p: v for p, v in before['jars'][CLI]['entries'].items() if not selected(p)} == {
        p: v for p, v in after['jars'][CLI]['entries'].items() if not selected(p)}, 'core_cli_unrelated_entry_changed'
    return changed


def main():
    parser = argparse.ArgumentParser()
    for key in ['parent', 'candidate', 'output', 'temporary-directory']:
        parser.add_argument('--' + key, required=True)
    parser.add_argument('--publication-commit')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='core-proof-', dir=args.temporary_directory) as temporary:
        images = [inspect(ref, pathlib.Path(temporary)) for ref in [args.parent, args.candidate]]
    changed = verify(*images, args.publication_commit)
    record = {'schema': 'otziv-jackson-core-refresh-inspection-v1', 'result': 'PASS',
              'productionAccess': False, 'ownedContainersRemaining': 0, 'images': images,
              'changedFiles': changed, 'publicationCommit': args.publication_commit,
              'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
              'underlyingInspectorSha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
              'patchScriptSha256': hashlib.sha256(PATCH.read_bytes()).hexdigest()}
    pathlib.Path(args.output).write_bytes(gzip.compress((json.dumps(record, separators=(',', ':')) + '\n').encode(), mtime=0))
    print(json.dumps({'result': 'PASS', 'changedFiles': len(changed), 'publicationCommit': args.publication_commit}))


if __name__ == '__main__':
    main()
