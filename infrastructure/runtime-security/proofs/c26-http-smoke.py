"""Local-only C26 HTTP fixtures; never mount production inputs or publish host ports."""
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

LABEL = 'com.otziv.c26-http.owner'
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
    location ~ ^/pcre2/(?<fixture>[a-z]+)$ { return 200 "c26:$fixture"; }
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
if (!str_starts_with(PCRE_VERSION, '10.49 ') || preg_match('/^c26-(?<name>[a-z]+)$/', 'c26-fixture', $match) !== 1 || $match['name'] !== 'fixture') exit(23);
echo json_encode(['status' => $status, 'loginForm' => true, 'bodySha256' => $pageHash,
 'assetStatus' => $assetStatus, 'assetSha256' => hash('sha256', $asset), 'phpVersion' => PHP_VERSION,
 'pcreVersion' => PCRE_VERSION, 'namedCapture' => true], JSON_THROW_ON_ERROR);
'''


def docker(*args, timeout=60):
    return subprocess.run(['docker', *args], check=True, capture_output=True, timeout=timeout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--publication', type=pathlib.Path)
    parser.add_argument('--component', choices=['nginx', 'phpmyadmin'], required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--local-candidate', action='store_true', help='Preparation only; receipt explicitly marks local validation.')
    args = parser.parse_args()
    assert not args.output.exists(), 'output_already_exists'
    endpoint = docker('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}').stdout.decode().strip()
    assert re.fullmatch(r'(?:unix|npipe)://[^\r\n]+', endpoint), 'remote_docker_forbidden'
    assert not os.environ.get('DOCKER_HOST') or re.fullmatch(r'(?:unix|npipe)://[^\r\n]+', os.environ['DOCKER_HOST']), 'remote_docker_forbidden'
    if args.local_candidate:
        assert args.publication is None
        reference = 'otziv-c26-' + args.component + '-candidate'
        publication = None
    else:
        assert args.publication is not None
        raw = args.publication.read_bytes()
        publication = json.loads(raw)
        assert publication['result'] == 'PASS' and publication['security']['result'] == 'PASS'
        assert publication['component'] == args.component and publication['manifestSet'] == 'c26-' + args.component
        assert publication['commit'] == 'c81a29c1eda2ba157702560338467521cd3e484e'
        reference = publication['reference']
        assert re.fullmatch(r'ghcr.io/claidd/otziv-security@sha256:[a-f0-9]{64}', reference)
    inspected = json.loads(docker('image', 'inspect', reference).stdout)[0]
    assert inspected['Os'] == 'linux' and inspected['Architecture'] == 'amd64'
    if publication:
        assert reference in inspected['RepoDigests']
        assert inspected['Config']['Labels']['com.otziv.publication.revision'] == publication['commit']
        assert inspected['Config']['Labels']['com.otziv.reviewed-component'] == args.component
    owner = uuid.uuid4().hex
    name = 'otziv-c26-http-' + args.component + '-' + owner
    args.output.parent.mkdir(parents=True, exist_ok=True)
    result = {'schema': 'otziv-c26-http-smoke-v1', 'result': 'IN_PROGRESS', 'component': args.component,
              'reference': reference, 'inspectedImageId': inspected['Id'], 'productionAccess': False,
              'localCandidateOnly': args.local_candidate, 'network': 'none', 'publishedPorts': False,
              'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
    if publication:
        result.update(publicationSha256=hashlib.sha256(raw).hexdigest(), imageConfigId=publication['imageId'],
                      commit=publication['commit'], run=publication['run'], attempt=publication['attempt'])
    allocated = False
    try:
        with tempfile.TemporaryDirectory(prefix='c26-http-input-', dir=args.output.parent) as temporary:
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
                        response = docker('exec', name, 'wget', '-S', '-O-', 'http://127.0.0.1:8080/pcre2/candidate', timeout=7)
                        assert response.stdout == b'c26:candidate'
                        assert re.search(rb'HTTP/1\.[01] 200', response.stderr)
                        result['http'] = {'status': 200, 'regexNamedCapture': True, 'bodySha256': hashlib.sha256(response.stdout).hexdigest()}
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
