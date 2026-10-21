"""Inspect stopped C26/C35 Keycloak images without executing their payload.

The old JAR filenames are part of Quarkus's generated classpath. Prove the
actual Maven artifact bytes and embedded version, and permit only the ten
Vert.x and eighteen Netty replacements plus Quarkus's augmentation outputs.
"""
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

HERE = pathlib.Path(__file__).parent
SOURCE = HERE / 'ssl-refresh-inspection.py'
spec = importlib.util.spec_from_file_location('c35_stopped_image_inventory', SOURCE)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

VERSION_BEFORE = '4.5.31'
VERSION_AFTER = '4.5.35'
ARTIFACTS = {
    'vertx-auth-common': ('5103b6ce5121aa783707278b4ec08a19'
                          'a0405d87b91dced1d9f6054d7ffb5dc1'),
    'vertx-bridge-common': 'a004fef3622ce6db8ba11c502f2c752dd0975eaf7c8e940e71c4c4ca0ccbf4cd',
    'vertx-codegen': 'b82fe8fe7324af7a37fd41940c228af99ea9c400e96ac55d0cfe8016b85b5337',
    'vertx-core': '1a274ba3739dc72506d5e801a8337ab734e07fbfd48ece7e77a518fbf40cea9a',
    'vertx-grpc': '70c91f9d4d9716f130057bc6d286687da9222a7fb96251dd91ac0a7bb55ed143',
    'vertx-grpc-client': '474b83d79944d1b4869452a9712b8cb51c9b6584e48d32ef113720c4de1072f7',
    'vertx-grpc-common': '908557303faea174e8755e6a1fb01771208db2e215dbeaff2549891855b50f8e',
    'vertx-grpc-server': '115d31258beffa4c2205ccd614981653df4a3ff48ad6dfb428fb9bd2a8a46962',
    'vertx-web': 'a8d9d06554e23286edb542d9adf15d057bda0399455a8f04ceb27e39bea1a0ac',
    'vertx-web-common': 'e6938a7c555ae87ccde10af71cfb264483ea7f952e7d7724e4196bef21cec64d',
}
NETTY_VERSION = '4.1.139.Final'
TCNATIVE_VERSION = '2.0.84.Final'
NETTY = {
    'netty-buffer': '48d805b74e56dc5f92b1f288858185abc86db16d293e8da183542df6243427f2',
    'netty-codec': 'ad43f07219dfb757a87b9c2b4765002be478c5401971379954e3d182f285fe6b',
    'netty-codec-dns': 'e044715cfd72e4717a06e41f4614dea0246ac20059d3aa72f36f16be20bc623c',
    'netty-codec-haproxy': '3109e099f34523799479c70b12b7d3731b9bb891acc8654a145eefd189a63472',
    'netty-codec-http': '627cfdac2cd5dbd8a33af85977fb81fa6b46f0c00389ab4c8677a415f0f85480',
    'netty-codec-http2': '4cb8bc95386112f3a9da935272781d577d9e70ca4c56cf0eef603a45e9526e02',
    'netty-codec-socks': '94d6800d5f717b40db5d0bf42a77b448d472cd9033735e69e7b18311bd902f23',
    'netty-common': '50372d86e27b4b1cf0810d75eb534795966a199049a1e85a0c41bbd2bb97f519',
    'netty-handler': 'ff85f00781b956d618b20f33da076c839a5e43e0084108b07bab0c19737168ec',
    'netty-handler-proxy': '9c92c2407b6fd3c120ab5efb900475a1fe40ce099703d2500c8679d3c75a90cb',
    'netty-resolver': '562a5388c88325b7d6ed05e8168736871ce0e64ab01e0ce52ea7710760b9b457',
    'netty-resolver-dns': '15feeffd0cbc2987ba35b798ca5b05fa1bc10cb880b8aa13e9bd81af3d483525',
    'netty-transport': 'be7bff8b6274e7fae12bbad3c565777539e9b069517e0b3acc06d9ac2cd6ec05',
    'netty-transport-classes-epoll': '99ea06d67e3e2792cc93521b686223c78da2561bfdf98533ebb29fed1729cce4',
    'netty-transport-native-epoll:linux-aarch_64': '039c9ce3bef089dfde34987cac3c928466cb71d54d3df79c0068fd17ee166e51',
    'netty-transport-native-epoll:linux-x86_64': '696fff4fb645d64fec6bc54fae27d9b643d64814e9be3bf42cbd94a608e2bcd0',
    'netty-transport-native-unix-common': '8e660ac8a485fc8d8b47d87d04ea33d19902dc6fc5a113e9353b762188b3f333',
    'netty-tcnative-classes': '7a4437abf98b0cec053af4443fe321177d2cfccfec7bcad4e7d8b75571bbec4d',
}
ROOT = 'opt/keycloak/lib/lib/main/'
JARS = {artifact: ROOT + 'io.vertx.' + artifact + '-' + VERSION_BEFORE + '.jar' for artifact in ARTIFACTS}
def netty_path(name):
    artifact, _, classifier = name.partition(':')
    old = '2.0.78.Final' if artifact == 'netty-tcnative-classes' else '4.1.136.Final'
    return ROOT + 'io.netty.' + artifact + '-' + old + ('-' + classifier if classifier else '') + '.jar'

NETTY_JARS = {name: netty_path(name) for name in NETTY}
GENERATED = {
    'opt/keycloak/lib/quarkus/generated-bytecode.jar',
    'opt/keycloak/lib/quarkus/quarkus-application.dat',
    'opt/keycloak/lib/quarkus/transformed-bytecode.jar',
}
PARENT = 'ghcr.io/claidd/otziv-security@sha256:76ad15fac3c0041798b6b2b87632bbf91ae7c171ebfa813aa1d1633b48f90ff9'
PARENT_CONFIG = 'sha256:f641317e4919c0959cf51693cf434024094922ec612f5f3bb5a08122f10ab511'


def inspect(reference, directory, child=False):
    record = base.inspect(reference, directory)
    if not child:
        return record
    owner = 'otziv-vertx-inspection-' + uuid.uuid4().hex
    base.docker('create', '--name', owner, '--label', 'otziv.vertx.inspection.owner=' + owner,
                '--network', 'none', '--entrypoint', '/not-executed', reference)
    try:
        def identify(group, artifact, path, version, key):
            file = directory / (owner + '-' + key.replace(':', '-') + '.jar')
            base.docker('cp', owner + ':/' + path, str(file))
            raw = file.read_bytes()
            digest = hashlib.sha256(raw).hexdigest()
            assert record['inventory'][path]['sha256'] == digest, 'vertx_export_and_copy_disagree'
            with zipfile.ZipFile(file) as archive:
                names = archive.namelist()
                assert len(names) == len(set(names)), 'vertx_duplicate_zip_entry'
                pom_path = 'META-INF/maven/' + group + '/' + artifact + '/pom.properties'
                assert pom_path in names, 'vertx_maven_identity_missing:' + artifact
                pom = archive.read(pom_path).decode('iso-8859-1')
                fields = dict(line.split('=', 1) for line in pom.splitlines()
                              if line and not line.startswith('#') and '=' in line)
                assert fields.get('groupId') == group, 'vertx_wrong_group:' + artifact
                assert fields.get('artifactId') == artifact, 'vertx_wrong_artifact:' + artifact
                assert fields.get('version') == version, 'vertx_wrong_embedded_version:' + artifact
            return {'path': path, 'sha256': digest, 'pom': fields}
        record['jars'] = {artifact: identify('io.vertx', artifact, path, VERSION_AFTER, artifact)
                          for artifact, path in JARS.items()}
        record['nettyJars'] = {name: identify('io.netty', name.partition(':')[0], path,
                                TCNATIVE_VERSION if name == 'netty-tcnative-classes' else NETTY_VERSION, name)
                               for name, path in NETTY_JARS.items()}
        return record
    finally:
        container = json.loads(base.docker('inspect', owner))[0]
        assert container['Config']['Labels']['otziv.vertx.inspection.owner'] == owner
        assert not container['State']['Running']
        base.docker('rm', '-v', owner)


def verify(before, after, candidate, publication_commit=None):
    assert before['reference'] == PARENT, 'vertx_wrong_parent_reference'
    assert before['imageId'] in (PARENT_CONFIG, PARENT.split('@')[1]), 'vertx_wrong_parent_config'
    assert after['reference'] == candidate, 'vertx_wrong_candidate_reference'
    assert not before['containerExecuted'] and not after['containerExecuted'], 'vertx_image_executed'
    assert after['rootfs'][:len(before['rootfs'])] == before['rootfs'], 'vertx_parent_layers_changed'
    assert len(after['rootfs']) > len(before['rootfs']), 'vertx_overlay_missing'
    expected = copy.deepcopy(before['configuration'])
    expected['Labels']['otziv.vertx'] = VERSION_AFTER
    expected['Labels']['otziv.vertx-advisory'] = 'CVE-2026-102477'
    expected['Labels']['otziv.netty'] = NETTY_VERSION
    expected['Labels']['otziv.netty-handler'] = NETTY_VERSION
    expected['Labels']['otziv.netty-tcnative'] = TCNATIVE_VERSION
    if publication_commit:
        assert re.fullmatch('[a-f0-9]{40}', publication_commit), 'vertx_publication_commit_invalid'
        expected['Labels']['com.otziv.publication.revision'] = publication_commit
    assert after['configuration'] == expected, 'vertx_launch_contract_changed'
    old_paths = {path for path in before['inventory'] if path.startswith(ROOT + 'io.vertx.') and path.endswith('.jar')}
    new_paths = {path for path in after['inventory'] if path.startswith(ROOT + 'io.vertx.') and path.endswith('.jar')}
    assert old_paths == new_paths == set(JARS.values()), 'vertx_runtime_set_changed'
    old_netty = {path for path in before['inventory'] if path.startswith(ROOT + 'io.netty.') and path.endswith('.jar')}
    new_netty = {path for path in after['inventory'] if path.startswith(ROOT + 'io.netty.') and path.endswith('.jar')}
    assert old_netty == new_netty == set(NETTY_JARS.values()), 'netty_runtime_set_changed'
    changed = sorted(path for path in before['inventory'].keys() | after['inventory'].keys()
                     if before['inventory'].get(path) != after['inventory'].get(path))
    allowed = set(JARS.values()) | set(NETTY_JARS.values()) | GENERATED
    assert set(changed) == allowed, 'vertx_unrelated_or_missing_file_change:' + repr(changed)
    for artifact, path in JARS.items():
        old, new = before['inventory'][path], after['inventory'][path]
        assert old['sha256'] != new['sha256'], 'vertx_old_artifact_retained:' + artifact
        assert new['sha256'] == ARTIFACTS[artifact], 'vertx_official_artifact_bytes:' + artifact
        for key in ['mode', 'uid', 'gid', 'kind']:
            assert old[key] == new[key], 'vertx_file_metadata_changed:' + artifact + ':' + key + ':' + repr((old[key], new[key]))
        jar = after['jars'][artifact]
        assert jar['path'] == path and jar['sha256'] == new['sha256'], 'vertx_jar_inventory_mismatch:' + artifact
        assert jar['pom'] == {'groupId': 'io.vertx', 'artifactId': artifact, 'version': VERSION_AFTER}, 'vertx_pom_mismatch:' + artifact
    for name, path in NETTY_JARS.items():
        old, new = before['inventory'][path], after['inventory'][path]
        assert old['sha256'] != new['sha256'], 'netty_old_artifact_retained:' + name
        assert new['sha256'] == NETTY[name], 'netty_official_artifact_bytes:' + name
        for key in ['mode', 'uid', 'gid', 'kind']:
            assert old[key] == new[key], 'netty_file_metadata_changed:' + name + ':' + key
        artifact = name.partition(':')[0]
        version = TCNATIVE_VERSION if name == 'netty-tcnative-classes' else NETTY_VERSION
        jar = after['nettyJars'][name]
        assert jar == {'path': path, 'sha256': NETTY[name],
                       'pom': {'groupId': 'io.netty', 'artifactId': artifact, 'version': version}}, 'netty_pom_mismatch:' + name
    return changed


def main():
    parser = argparse.ArgumentParser()
    for name in ['candidate', 'output', 'temporary-directory']:
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--parent', default=PARENT)
    parser.add_argument('--publication-commit')
    args = parser.parse_args()
    assert args.parent == PARENT, 'vertx_parent_argument_changed'
    with tempfile.TemporaryDirectory(prefix='vertx-c35-proof-', dir=args.temporary_directory) as folder:
        images = [inspect(args.parent, pathlib.Path(folder)), inspect(args.candidate, pathlib.Path(folder), child=True)]
    changed = verify(*images, args.candidate, args.publication_commit)
    report = {'schema': 'otziv-vertx-c35-inspection-v1', 'result': 'PASS', 'productionAccess': False,
              'ownedContainersRemaining': 0, 'images': images, 'changedFiles': changed,
              'publicationCommit': args.publication_commit,
              'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
              'underlyingInspectorSha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest()}
    pathlib.Path(args.output).write_bytes(gzip.compress((json.dumps(report, separators=(',', ':')) + '\n').encode(), mtime=0))
    print(json.dumps({'result': 'PASS', 'changedFiles': changed, 'publicationCommit': args.publication_commit}))


if __name__ == '__main__':
    main()
