"""Local-only C41 HTTP fixtures; never mount production inputs or publish host ports."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import tempfile
import time
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]

LABEL = 'com.otziv.c41-http.owner'
NGINX = '''pid /tmp/nginx.pid;
error_log stderr notice;
events { worker_connections 32; }
http {
  access_log off;
  client_body_temp_path /tmp/client_body;
  proxy_temp_path /tmp/proxy;
  fastcgi_temp_path /tmp/fastcgi;
  uwsgi_temp_path /tmp/uwsgi;
  scgi_temp_path /tmp/scgi;
  default_type text/plain;
  server {
    listen 8080;
    location ~ ^/pcre2/(?<fixture>[a-z]+)$ { return 200 "c41:$fixture"; }
    location / { return 404 "unmatched"; }
  }
}
'''
PHP = r'''
$curl = curl_init('http://127.0.0.1/');
curl_setopt_array($curl, [CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 5]);
$body = curl_exec($curl); $status = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
if (!is_string($body) || $status !== 200 || !str_contains($body, 'phpMyAdmin') || !str_contains($body, 'pma_username')) exit(21);
$pageHash = hash('sha256', $body);
curl_setopt($curl, CURLOPT_URL, 'http://127.0.0.1/js/vendor/jquery/jquery.min.js');
$asset = curl_exec($curl); $assetStatus = curl_getinfo($curl, CURLINFO_RESPONSE_CODE);
if (!is_string($asset) || $assetStatus !== 200 || !str_contains($asset, 'jQuery')) exit(22);
if (PHP_VERSION !== '8.3.36') exit(24);
if (curl_version()['version'] !== '8.23.0') exit(25);
if (!str_starts_with(PCRE_VERSION, '10.49 ') || preg_match('/^c41-(?<name>[a-z]+)$/', 'c41-fixture', $match) !== 1 || $match['name'] !== 'fixture') exit(23);
echo json_encode(['status' => $status, 'loginForm' => true, 'bodySha256' => $pageHash,
 'assetStatus' => $assetStatus, 'assetSha256' => hash('sha256', $asset), 'phpVersion' => PHP_VERSION,
 'pcreVersion' => PCRE_VERSION, 'curlVersion' => curl_version()['version'], 'namedCapture' => true], JSON_THROW_ON_ERROR);
'''


def docker(*args, timeout=60):
    return subprocess.run(['docker', *args], check=True, capture_output=True, timeout=timeout)


PATHS = {
    'nginx': '/etc/nginx /usr/share/nginx /usr/sbin/nginx /usr/lib/nginx /docker-entrypoint.sh /docker-entrypoint.d',
    'phpmyadmin': '/var/www/html /etc/phpmyadmin /etc/php83 /etc/apache2 /usr/local/etc /usr/lib/php83 /usr/bin/php83 /usr/local/share/otziv /docker-entrypoint.sh /usr/local/bin/apache2-foreground /usr/local/bin/php /usr/local/bin/apachectl /usr/local/bin/apache2ctl',
}


def compare_payload(component, recipe, reference, inspected, output, publication, publication_path):
    parent_ref = recipe['candidateBaseRef']
    parent = json.loads(docker('image', 'inspect', '--platform', 'linux/amd64', parent_ref).stdout)[0]
    assert parent_ref in parent['RepoDigests'], 'parent_digest_not_loaded'
    assert parent['Os'] == inspected['Os'] == 'linux'
    assert parent['Architecture'] == inspected['Architecture'] == 'amd64'
    # Docker Desktop can report a platform manifest ID instead of a config ID.
    # Bind published evidence to both OCI blobs and the complete local config.
    if publication:
        config_bytes = publication_path.with_name('registry-amd64-config.json').read_bytes()
        image_bytes = publication_path.with_name('registry-amd64-manifest.json').read_bytes()
        config, image_manifest = json.loads(config_bytes), json.loads(image_bytes)
        assert 'sha256:' + hashlib.sha256(config_bytes).hexdigest() == publication['imageId'] == image_manifest['config']['digest']
        manifest_id = 'sha256:' + hashlib.sha256(image_bytes).hexdigest()
        assert inspected['Id'] in (manifest_id, publication['imageId']), 'published_image_identity_mismatch'
        if inspected['Id'] == manifest_id:
            assert inspected['Descriptor']['digest'] == manifest_id
            assert inspected['Descriptor']['platform'] == {'architecture': 'amd64', 'os': 'linux'}
        assert inspected['Config'] == config['config'], 'published_config_mismatch'
        assert inspected['RootFS']['Layers'] == config['rootfs']['diff_ids'], 'published_layers_mismatch'
    keys = ['User', 'Entrypoint', 'Cmd', 'WorkingDir', 'ExposedPorts', 'Volumes', 'Healthcheck', 'StopSignal', 'Env']
    for key in keys:
        assert parent['Config'].get(key) == inspected['Config'].get(key), 'config_changed:' + key
    allowed = {'com.otziv.publication.source', 'com.otziv.publication.revision',
               'com.otziv.reviewed-component', 'com.otziv.security.curl-refresh'}
    assert {k: v for k, v in (parent['Config'].get('Labels') or {}).items() if k not in allowed} == {
        k: v for k, v in (inspected['Config'].get('Labels') or {}).items() if k not in allowed}, 'unrelated_labels_changed'

    def snapshot(image, command):
        return docker('run', '--rm', '--pull=never', '--network', 'none', '--read-only', '--user', '0:0',
                      '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true', '--memory', '256m',
                      '--pids-limit', '128', '--entrypoint', 'sh', image, '-c',
                      'set -eu; set -o pipefail; ' + command, timeout=300).stdout

    roots = PATHS[component]
    payload = (f"find {roots} -exec stat -c '%a|%u|%g|%F|%N' '{{}}' ';' | LC_ALL=C sort; "
               f"find {roots} -type f -exec sha256sum '{{}}' ';' | LC_ALL=C sort")
    commands = [('payload', payload), ('other-packages', "apk --no-network info -v | LC_ALL=C sort | grep -Ev '^(curl|libcurl)-[0-9]'")]
    if component == 'phpmyadmin':
        commands.append(('php-modules', 'php -m | LC_ALL=C sort'))
    result = {'parentReference': parent_ref, 'parentLocalId': parent['Id'], 'candidateLocalId': inspected['Id'],
              'configFieldsPreserved': keys, 'network': 'none', 'readOnly': True, 'productionAccess': False}
    for category, command in commands:
        before, after = snapshot(parent_ref, command), snapshot(reference, command)
        for suffix, value in [('before', before), ('after', after)]:
            with (output / (category + '-' + suffix + '.txt')).open('xb') as stream:
                stream.write(value)
        assert before == after, category + '_changed'
        result[category + 'Sha256'] = hashlib.sha256(after).hexdigest()
    for package in ('curl', 'libcurl'):
        before = snapshot(parent_ref, 'apk --no-network info -v -e ' + package).decode().strip()
        after = snapshot(reference, 'apk --no-network info -v -e ' + package).decode().strip()
        assert before == package + '-8.22.0-r0' and after == package + '-8.23.0-r0', 'unexpected_curl_transition'
        result[package] = {'before': before, 'after': after}
    result['result'] = 'PASS'
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--publication', type=pathlib.Path)
    parser.add_argument('--component', choices=['nginx', 'phpmyadmin'], required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--local-candidate', action='store_true', help='Preparation only; receipt explicitly marks local validation.')
    args = parser.parse_args()
    manifest_bytes = (ROOT / f'infrastructure/runtime-security/reviewed-images-c41-{args.component}.json').read_bytes()
    manifest = json.loads(manifest_bytes)
    recipe = manifest['images'][0]
    recipe_bytes = (ROOT / recipe['dockerfile']).read_text().replace('\r\n', '\n').encode()
    assert hashlib.sha256(recipe_bytes).hexdigest() == recipe['dockerfileSha256'], 'recipe_hash_mismatch'
    assert not args.output.exists(), 'output_already_exists'
    endpoint = docker('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}').stdout.decode().strip()
    assert re.fullmatch(r'(?:unix|npipe)://[^\r\n]+', endpoint), 'remote_docker_forbidden'
    assert not os.environ.get('DOCKER_HOST') or re.fullmatch(r'(?:unix|npipe)://[^\r\n]+', os.environ['DOCKER_HOST']), 'remote_docker_forbidden'
    if args.local_candidate:
        assert args.publication is None
        reference = 'otziv-c41-' + args.component + '-candidate'
        publication = None
    else:
        assert args.publication is not None
        raw = args.publication.read_bytes()
        publication = json.loads(raw)
        assert publication['result'] == 'PASS' and publication['security']['result'] == 'PASS'
        assert publication['component'] == args.component and publication['manifestSet'] == 'c41-' + args.component
        assert publication['manifestSha256'] == hashlib.sha256(manifest_bytes).hexdigest(), 'publication_manifest_mismatch'
        assert publication['dockerfileSha256'] == recipe['dockerfileSha256'], 'publication_recipe_mismatch'
        reference = publication['reference']
        assert re.fullmatch(r'ghcr.io/claidd/otziv-security@sha256:[a-f0-9]{64}', reference)
    inspected = json.loads(docker('image', 'inspect', '--platform', 'linux/amd64', reference).stdout)[0]
    assert inspected['Os'] == 'linux' and inspected['Architecture'] == 'amd64'
    if publication:
        assert reference in inspected['RepoDigests']
        assert inspected['Config']['Labels']['com.otziv.publication.revision'] == publication['commit']
        assert inspected['Config']['Labels']['com.otziv.reviewed-component'] == args.component
    owner = uuid.uuid4().hex
    name = 'otziv-c41-http-' + args.component + '-' + owner
    args.output.parent.mkdir(parents=True, exist_ok=True)
    result = {'schema': 'otziv-c41-http-smoke-v1', 'result': 'IN_PROGRESS', 'component': args.component,
              'reference': reference, 'inspectedImageId': inspected['Id'], 'productionAccess': False,
              'localCandidateOnly': args.local_candidate, 'network': 'none', 'publishedPorts': False,
              'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
    if publication:
        result.update(publicationSha256=hashlib.sha256(raw).hexdigest(), imageConfigId=publication['imageId'],
                      commit=publication['commit'], run=publication['run'], attempt=publication['attempt'])
    allocated = False
    try:
        result['payload'] = compare_payload(args.component, recipe, reference, inspected, args.output.parent, publication, args.publication)
        # The system temporary directory is Docker Desktop-shareable even when
        # the repository is on a Windows volume without an active Docker mount.
        with tempfile.TemporaryDirectory(prefix='c41-http-input-') as temporary:
            common = ['create', '--pull=never', '--name', name, '--label', LABEL + '=' + owner,
                      '--network', 'none', '--memory', '384m', '--cpus', '1', '--pids-limit', '128',
                      '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true']
            if args.component == 'nginx':
                config = pathlib.Path(temporary) / 'nginx.conf'
                config.write_text(NGINX, encoding='utf-8', newline='\n')
                docker(*common, '--user', '101:101', '--read-only', '--tmpfs', '/tmp:rw,nosuid,size=32m',
                       '--mount', 'type=bind,source=' + str(config.resolve()) + ',target=/fixture/nginx.conf,readonly',
                       '--entrypoint', 'nginx', reference, '-c', '/fixture/nginx.conf', '-g', 'daemon off;')
            else:
                docker(*common, '--cap-add', 'CHOWN', '--cap-add', 'SETUID', '--cap-add', 'SETGID',
                       '--cap-add', 'NET_BIND_SERVICE', '--env', 'PMA_HOST=fixture-database.invalid', reference)
            allocated = True
            docker('start', name)
            deadline = time.monotonic() + 45
            while True:
                try:
                    if args.component == 'nginx':
                        response = docker('exec', name, 'curl', '--fail', '--silent', '--show-error', '--max-time', '5',
                                         '--write-out', '\n%{http_code}', 'http://127.0.0.1:8080/pcre2/candidate', timeout=7)
                        assert response.stdout == b'c41:candidate\n200'
                        result['http'] = {'status': 200, 'regexNamedCapture': True, 'curlClient': True,
                                          'bodySha256': hashlib.sha256(response.stdout.rsplit(b'\n', 1)[0]).hexdigest()}
                    else:
                        result['http'] = json.loads(docker('exec', name, 'php', '-r', PHP, timeout=7).stdout)
                    break
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired, AssertionError, json.JSONDecodeError):
                    if not json.loads(docker('inspect', name).stdout)[0]['State']['Running']:
                        result['fixtureLog'] = docker('logs', '--tail', '12', name).stderr.decode(errors='replace')[-4096:]
                        raise RuntimeError('http_fixture_stopped')
                    if time.monotonic() >= deadline:
                        raise RuntimeError('http_fixture_timeout')
                    time.sleep(0.5)
            package = docker('exec', name, 'apk', '--no-network', 'info', '-v', '-e', 'pcre2').stdout.decode().strip()
            assert package == 'pcre2-10.49-r0'
            result['pcre2Package'] = package
            brotli = docker('exec', name, 'apk', '--no-network', 'info', '-v', '-e', 'brotli-libs').stdout.decode().strip()
            assert brotli == 'brotli-libs-1.2.1-r0'
            result['brotliPackage'] = brotli
            for package in ('curl', 'libcurl'):
                version = docker('exec', name, 'apk', '--no-network', 'info', '-v', '-e', package).stdout.decode().strip()
                assert version == package + '-8.23.0-r0', 'curl_security_version_mismatch'
                result[package + 'Package'] = version
            result['result'] = 'PASS'
    except BaseException as error:
        result.update(result='FAIL', failure=type(error).__name__)
        raise
    finally:
        if allocated:
            value = json.loads(docker('inspect', name).stdout)[0]
            assert value['Config']['Labels'][LABEL] == owner, 'cleanup_owner_mismatch'
            assert value['HostConfig']['NetworkMode'] == 'none' and not value['HostConfig'].get('PortBindings')
            docker('rm', '--force', '--volumes', name)
        remaining = docker('ps', '-aq', '--filter', 'label=' + LABEL + '=' + owner).stdout.strip()
        assert not remaining, 'owned_container_remaining'
        result['ownedContainersRemaining'] = 0
        args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'result': result['result'], 'component': args.component, 'reference': reference, 'localCandidateOnly': args.local_candidate}))


if __name__ == '__main__':
    main()
