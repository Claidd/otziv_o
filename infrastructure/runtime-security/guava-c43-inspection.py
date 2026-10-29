"""Stopped-image proof of the C35 -> C43 Guava-only Keycloak runtime update."""
import argparse
import copy
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import tempfile
import uuid
import zipfile

PARENT = 'ghcr.io/claidd/otziv-security@sha256:c28364544835f5fbef6d4e0b0b3e63154127f7fb440b05432760f11235b5f4a9'
PARENT_CONFIG = 'sha256:60a8dd92cf43b8cf909a9e13f231da1f59103f9259cf482185c54856dcfd6a1b'
GUAVA = 'opt/keycloak/lib/lib/main/com.google.guava.guava-33.5.0-jre.jar'
GUAVA_SHA256 = 'b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c'
GENERATED = {'opt/keycloak/lib/quarkus/' + name for name in
             ('generated-bytecode.jar', 'quarkus-application.dat', 'transformed-bytecode.jar')}
spec = importlib.util.spec_from_file_location('c43_image_inventory', Path(__file__).with_name('ssl-refresh-inspection.py'))
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
hash_bytes = lambda value: hashlib.sha256(value).hexdigest()


def verify(before, after, commit):
    assert before['reference'] == PARENT and before['imageId'] in (PARENT_CONFIG, PARENT.split('@')[1]), 'guava_c43_parent'
    assert re.fullmatch('[a-f0-9]{40}', commit), 'guava_c43_commit'
    assert after['rootfs'][:len(before['rootfs'])] == before['rootfs'], 'guava_c43_parent_layers'
    assert len(after['rootfs']) > len(before['rootfs']), 'guava_c43_overlay_missing'
    expected = copy.deepcopy(before['configuration'])
    expected['Labels'].update({'otziv.guava': '33.7.2-jre', 'otziv.guava-advisory': 'CVE-2026-102554',
                               'com.otziv.publication.revision': commit})
    assert expected == after['configuration'], 'guava_c43_unrelated_configuration'
    changed = sorted(path for path in before['inventory'].keys() | after['inventory'].keys()
                     if before['inventory'].get(path) != after['inventory'].get(path))
    assert GUAVA in changed and set(changed) <= {GUAVA, *GENERATED}, 'guava_c43_unrelated_payload'
    old, new = before['inventory'][GUAVA], after['inventory'][GUAVA]
    assert old['sha256'] != new['sha256'] == GUAVA_SHA256, 'guava_c43_official_bytes'
    for key in ('mode', 'uid', 'gid', 'kind'):
        assert old[key] == new[key], 'guava_c43_file_metadata:' + key
    provider = 'opt/keycloak/providers/otziv-security-generation.jar'
    assert before['inventory'][provider] == after['inventory'][provider], 'guava_c43_provider_changed'
    return changed


def identify_jar(reference, directory):
    owner = 'otziv-guava-inspection-' + uuid.uuid4().hex
    base.docker('create', '--name', owner, '--label', 'com.otziv.guava-inspection.owner=' + owner,
                '--network', 'none', '--entrypoint', '/not-executed', reference)
    try:
        target = directory / 'guava.jar'
        base.docker('cp', owner + ':/' + GUAVA, str(target))
        data = target.read_bytes()
        assert hash_bytes(data) == GUAVA_SHA256, 'guava_c43_jar_digest'
        with zipfile.ZipFile(io.BytesIO(data)) as jar:
            fields = dict(line.split('=', 1) for line in
                          jar.read('META-INF/maven/com.google.guava/guava/pom.properties').decode().splitlines()
                          if '=' in line and not line.startswith('#'))
        assert fields == {'groupId': 'com.google.guava', 'artifactId': 'guava', 'version': '33.7.2-jre'}, 'guava_c43_jar_identity'
        return {'path': GUAVA, 'sha256': GUAVA_SHA256, 'pom': fields}
    finally:
        container = json.loads(base.docker('inspect', owner))[0]
        assert container['Config']['Labels']['com.otziv.guava-inspection.owner'] == owner
        assert not container['State']['Running']
        base.docker('rm', '-v', owner)


def main():
    parser = argparse.ArgumentParser()
    for name in ('candidate', 'publication-commit', 'output', 'temporary-directory'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    assert re.fullmatch(r'ghcr\.io/claidd/otziv-security@sha256:[a-f0-9]{64}', args.candidate)
    parent = Path(args.temporary_directory).resolve(strict=True)
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='guava-c43-owned-', dir=parent) as directory:
        temporary = Path(directory).resolve()
        assert temporary.is_relative_to(parent) and temporary != parent
        before = base.inspect(PARENT, temporary)
        after = base.inspect(args.candidate, temporary)
        jar = identify_jar(args.candidate, temporary)
        changed = verify(before, after, args.publication_commit)
        proof = {'schema': 'otziv-keycloak-guava-c43-inventory-v1', 'parent': before, 'candidate': after}
        payload = (json.dumps(proof, sort_keys=True, separators=(',', ':')) + '\n').encode()
        (output / 'runtime.json.gz').write_bytes(gzip.compress(payload, mtime=0))
        receipt = {'schema': 'otziv-keycloak-guava-c43-inspection-v1', 'result': 'PASS',
                   'parent': PARENT, 'parentImageId': before['imageId'],
                   'candidate': args.candidate, 'candidateImageId': after['imageId'],
                   'runtimeSha256': hash_bytes(payload), 'changedFiles': changed, 'jar': jar,
                   'inspectedContainersExecuted': False,
                   'executedScriptSha256': hash_bytes(Path(__file__).read_bytes()),
                   'inventoryScriptSha256': hash_bytes(Path(__file__).with_name('ssl-refresh-inspection.py').read_bytes())}
        (output / 'inspection.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(receipt))


if __name__ == '__main__':
    main()
