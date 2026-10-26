"""Bound C40-to-C41 Grafana comparison and isolated actual-published HTTP probe."""
import argparse
import gzip
import hashlib
import json
import os
import pathlib
import re
import subprocess
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[3]
PREFIX = ROOT / 'infrastructure/runtime-security'
EXPECTED_COMMIT = '632ea46485e75174dbdac0d3b88e100231705212'
EXPECTED_RUN = '40278086723'
PARENT = 'ghcr.io/claidd/otziv-security@sha256:db6100e472c78215c923e1472fbfe0b4109a1303505aa959b190a3cae2bccc37'
BINARY = '8b8981ecfb6dd1123831116f6428c531d33f43d7cda839677a6335f73895b32e'
LABEL = 'com.otziv.c41-grafana-proof.owner'
PATHS = '/usr/share/grafana /etc/grafana /usr/share/otziv-build /run.sh /.grafana-version'


def sha(value):
    return hashlib.sha256(value).hexdigest()


def docker(*args, timeout=90):
    return subprocess.run(['docker', *args], check=True, capture_output=True, timeout=timeout).stdout


def bind_image(publication_path, publication, inspected):
    directory = publication_path.parent
    index_bytes = (directory / 'registry-index.json').read_bytes()
    manifest_bytes = (directory / 'registry-amd64-manifest.json').read_bytes()
    config_bytes = (directory / 'registry-amd64-config.json').read_bytes()
    index, manifest, config = map(json.loads, (index_bytes, manifest_bytes, config_bytes))
    index_id, manifest_id, config_id = ['sha256:' + sha(b) for b in (index_bytes, manifest_bytes, config_bytes)]
    assert publication['reference'] == 'ghcr.io/claidd/otziv-security@' + index_id
    platform = [item for item in index['manifests'] if item.get('platform') == {'architecture': 'amd64', 'os': 'linux'}]
    assert len(platform) == 1 and platform[0]['digest'] == manifest_id and platform[0]['size'] == len(manifest_bytes)
    assert manifest['config']['digest'] == config_id == publication['imageId']
    assert manifest['config']['size'] == len(config_bytes)
    assert inspected['Id'] in (manifest_id, config_id), 'unexpected_platform_image_identity'
    if inspected['Id'] == manifest_id:
        assert inspected['Descriptor']['digest'] == manifest_id
        assert inspected['Descriptor']['platform'] == {'architecture': 'amd64', 'os': 'linux'}
    assert inspected['Config'] == config['config'], 'published_config_mismatch'
    assert inspected['RootFS']['Layers'] == config['rootfs']['diff_ids'], 'published_rootfs_mismatch'
    assert inspected['Os'] == config['os'] == 'linux'
    assert inspected['Architecture'] == config['architecture'] == 'amd64'
    assert publication['reference'] in inspected['RepoDigests'], 'published_digest_not_loaded'
    return {'indexDigest': index_id, 'manifestDigest': manifest_id, 'configDigest': config_id,
            'inspectedPlatformImageId': inspected['Id'], 'configAndRootfsMatch': True}


def snapshot(image, command, owner):
    return docker('run', '--rm', '--pull=never', '--platform', 'linux/amd64', '--network', 'none',
                  '--label', LABEL + '=' + owner,
                  '--read-only', '--user', '0:0', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
                  '--pids-limit', '128', '--memory', '256m', '--entrypoint', 'sh', image,
                  '-c', 'set -eu; set -o pipefail; ' + command, timeout=300)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--publication', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists(), 'output_exists'
    publication_path = args.publication.resolve()
    assert publication_path == (PREFIX / 'proofs/c41-grafana/publication/publication.json').resolve()
    endpoint = docker('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}').decode().strip()
    assert re.fullmatch(r'(unix|npipe)://[^\r\n]+', endpoint), 'remote_docker_forbidden'
    assert not os.environ.get('DOCKER_HOST') or re.fullmatch(r'(unix|npipe)://[^\r\n]+', os.environ['DOCKER_HOST'])
    raw = publication_path.read_bytes()
    publication = json.loads(raw)
    identity = {'commit': EXPECTED_COMMIT, 'run': EXPECTED_RUN, 'attempt': '1'}
    assert all(publication[k] == v for k, v in identity.items()), 'publication_identity_changed'
    assert publication['component'] == 'grafana' and publication['manifestSet'] == 'c41-grafana'
    assert publication['result'] == publication['security']['result'] == 'PASS'
    assert publication['security']['effectiveBlockingFixedHighOrCritical'] == 0
    assert publication['security']['unresolvedRiskReview'] == 'NONE'
    manifest_bytes = (PREFIX / 'reviewed-images-c41-grafana.json').read_bytes()
    recipe = json.loads(manifest_bytes)['images'][0]
    assert sha(manifest_bytes) == publication['manifestSha256']
    assert recipe['candidateBaseRef'] == PARENT
    recipe_bytes = (ROOT / recipe['dockerfile']).read_text().replace('\r\n', '\n').encode()
    assert sha(recipe_bytes) == recipe['dockerfileSha256'] == publication['dockerfileSha256']
    anonymous_bytes = (PREFIX / 'proofs/c41-grafana/anonymous/anonymous-download.json').read_bytes()
    anonymous = json.loads(anonymous_bytes)
    assert anonymous['result'] == 'PASS' and anonymous['sourcePublicationSha256'] == sha(raw)
    assert anonymous['publicDownloadReadiness'] == 'VERIFIED_ANONYMOUS_DIGEST_PULL'
    assert all(anonymous[k] == v for k, v in identity.items())
    assert all(anonymous[k] == publication[k] for k in ('component', 'reference', 'imageId', 'manifestSet'))
    reference = publication['reference']
    assert re.fullmatch(r'ghcr.io/claidd/otziv-security@sha256:[a-f0-9]{64}', reference)
    args.output.mkdir(parents=True)
    script_bytes = pathlib.Path(__file__).read_bytes()
    (args.output / 'executed-runtime-proof.py').write_bytes(script_bytes)
    report = {'schema': 'otziv-c41-grafana-published-runtime-proof-v1', 'result': 'IN_PROGRESS',
              'production': False, 'network': 'none', 'portsPublished': False, 'hostVolumes': False,
              'checks': [], **identity, 'reference': reference, 'parentReference': PARENT,
              'publicationSha256': sha(raw), 'anonymousSha256': sha(anonymous_bytes),
              'manifestSha256': sha(manifest_bytes), 'executedScriptSha256': sha(script_bytes)}
    owner = uuid.uuid4().hex
    name = 'otziv-c41-grafana-published-' + owner
    created = False

    def check(name, passed):
        assert passed, name
        report['checks'].append(name)

    def retain(category, suffix, value):
        filename = category + '-' + suffix + '.txt.gz'
        compressed = gzip.compress(value, mtime=0)
        (args.output / filename).write_bytes(compressed)
        return {'file': filename, 'sha256': sha(compressed), 'bytes': len(compressed),
                'uncompressedSha256': sha(value), 'uncompressedBytes': len(value)}

    try:
        for role, ref in [('candidate', reference), ('parent', PARENT)]:
            (args.output / (role + '-pull.log')).write_bytes(docker('pull', '--platform', 'linux/amd64', ref, timeout=300))
        image = json.loads(docker('image', 'inspect', '--platform', 'linux/amd64', reference))[0]
        parent = json.loads(docker('image', 'inspect', '--platform', 'linux/amd64', PARENT))[0]
        for role, value in [('candidate', image), ('parent', parent)]:
            (args.output / (role + '-image-inspect.json')).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8', newline='\n')
        report['candidateBinding'] = bind_image(publication_path, publication, image)
        parent_path = PREFIX / 'proofs/c40-grafana/publication/publication.json'
        report['parentBinding'] = bind_image(parent_path, json.loads(parent_path.read_bytes()), parent)
        check('both_exact_registry_images_bound_to_config_and_rootfs', True)
        labels = image['Config']['Labels']
        assert labels['com.otziv.publication.source'] == 'https://github.com/Claidd/otziv_o'
        assert labels['com.otziv.publication.revision'] == EXPECTED_COMMIT
        assert labels['com.otziv.reviewed-component'] == 'grafana'
        config_keys = ['User', 'Entrypoint', 'Cmd', 'WorkingDir', 'ExposedPorts', 'Volumes', 'Healthcheck', 'StopSignal', 'Env']
        for key in config_keys:
            assert parent['Config'].get(key) == image['Config'].get(key), 'config_changed:' + key
        permitted = {'com.otziv.publication.source', 'com.otziv.publication.revision', 'com.otziv.reviewed-component', 'com.otziv.security.curl-refresh'}
        assert {k: v for k, v in parent['Config']['Labels'].items() if k not in permitted} == {k: v for k, v in labels.items() if k not in permitted}
        report['preservedConfigurationFields'] = config_keys
        check('runtime_configuration_and_unrelated_labels_preserved', True)
        payload = (f"find {PATHS} -exec stat -c '%a|%u|%g|%F|%N' '{{}}' ';' | LC_ALL=C sort; "
                   f"find {PATHS} -type f -exec sha256sum '{{}}' ';' | LC_ALL=C sort")
        report['snapshots'] = {}
        for category, command in [('payload', payload), ('other-packages', "apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(curl|libcurl)-[0-9]'")]:
            before, after = snapshot(PARENT, command, owner), snapshot(reference, command, owner)
            report['snapshots'][category] = {'before': retain(category, 'before', before), 'after': retain(category, 'after', after)}
            check(category + '_unchanged', before == after)
        report['updatedPackages'] = {}
        for package in ('curl', 'libcurl'):
            before = snapshot(PARENT, 'apk --no-network info -v -e ' + package, owner).decode().strip()
            after = snapshot(reference, 'apk --no-network info -v -e ' + package, owner).decode().strip()
            check(package + '_exact_security_transition', before == package + '-8.21.0-r0' and after == package + '-8.23.0-r0')
            report['updatedPackages'][package] = {'before': before, 'after': after}
        check('image_user_472', image['Config']['User'] == '472')
        docker('create', '--pull=never', '--platform', 'linux/amd64', '--name', name, '--label', LABEL + '=' + owner,
               '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
               '--pids-limit', '256', '--memory', '768m',
               '--tmpfs', '/var/lib/grafana:rw,nosuid,size=96m,uid=472,gid=0',
               '--tmpfs', '/var/log/grafana:rw,nosuid,size=16m,uid=472,gid=0',
               '--tmpfs', '/tmp:rw,nosuid,size=32m,uid=472,gid=0',
               '-e', 'GF_ANALYTICS_REPORTING_ENABLED=false', '-e', 'GF_ANALYTICS_CHECK_FOR_UPDATES=false',
               '-e', 'GF_ANALYTICS_CHECK_FOR_PLUGIN_UPDATES=false', '-e', 'GF_PLUGINS_PREINSTALL_DISABLED=true',
               '-e', 'GF_LOG_MODE=console', reference)
        created = True
        docker('start', name)
        deadline = time.monotonic() + 75
        health = None
        while time.monotonic() < deadline:
            try:
                health = json.loads(docker('exec', name, 'curl', '--fail', '--silent', '--show-error', '--max-time', '3', 'http://127.0.0.1:3000/api/health', timeout=10))
                if health.get('database') == 'ok':
                    break
            except (subprocess.SubprocessError, ValueError):
                pass
            time.sleep(1)
        report['health'] = health
        check('actual_grafana_health_via_updated_curl', health is not None and health.get('database') == 'ok')
        check('grafana_version_unchanged', health['version'] == '12.4.10+otziv.4')
        report['curlVersion'] = docker('exec', name, 'curl', '--version').decode().splitlines()[0]
        check('curl_and_libcurl_version_8_23', report['curlVersion'].startswith('curl 8.23.0 ') and 'libcurl/8.23.0 ' in report['curlVersion'])
        report['binarySha256'] = docker('exec', name, 'sha256sum', '/usr/share/grafana/bin/grafana').decode().split()[0]
        check('exact_c36_binary', report['binarySha256'] == BINARY)
        docker('stop', '--time', '30', name)
        state = json.loads(docker('inspect', '--format', '{{json .State}}', name))
        report['shutdown'] = {'exitCode': state['ExitCode'], 'oomKilled': state['OOMKilled']}
        check('graceful_shutdown', state['ExitCode'] in (0, 143) and not state['OOMKilled'])
        report['result'] = 'PASS'
    except Exception as error:
        report['result'] = 'FAIL'
        report['error'] = str(error)
    finally:
        if created:
            logs = subprocess.run(['docker', 'logs', name], capture_output=True)
            (args.output / 'runtime.log').write_bytes(logs.stdout + logs.stderr)
            observed = json.loads(docker('inspect', name))[0]
            assert observed['Config']['Labels'][LABEL] == owner
            assert observed['HostConfig']['NetworkMode'] == 'none' and not observed['HostConfig'].get('PortBindings')
            docker('rm', '-f', '-v', name)
        # Also clean a snapshot container if a timed-out client disconnected.
        for leftover in docker('ps', '-aq', '--filter', 'label=' + LABEL + '=' + owner).decode().split():
            observed = json.loads(docker('inspect', leftover))[0]
            assert observed['Config']['Labels'][LABEL] == owner
            docker('rm', '-f', '-v', leftover)
        assert not docker('ps', '-aq', '--filter', 'label=' + LABEL + '=' + owner).strip()
        report['cleanup'] = 'PASS'
        (args.output / 'proof.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
        print(json.dumps({'result': report['result'], 'checks': len(report['checks']), 'cleanup': report['cleanup'], 'reference': reference, 'error': report.get('error')}))
    raise SystemExit(0 if report['result'] == 'PASS' else 1)


if __name__ == '__main__':
    main()
