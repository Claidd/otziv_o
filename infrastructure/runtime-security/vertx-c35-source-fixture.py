"""Create or remove an isolated C26 Keycloak + C23 PostgreSQL rehearsal source.

The fixture contains random identities only. It has no published ports and uses
an internal Docker network. `down` checks ownership before removing anything.
"""
import argparse
import json
import pathlib
import secrets
import subprocess
import sys
import time
import uuid


ROOT = pathlib.Path(__file__).resolve().parents[2]
PROJECT = 'otziv-prod-local'
OWNER_LABEL = 'com.otziv.vertx-c35-source.owner'
KEYCLOAK = 'ghcr.io/claidd/otziv-security@sha256:76ad15fac3c0041798b6b2b87632bbf91ae7c171ebfa813aa1d1633b48f90ff9'
POSTGRES = 'ghcr.io/claidd/otziv-security@sha256:c974c12a1107e314b1ba8cf65bd1fc5b6964645ced420c2151d336ee8a9a6534'
NODE = 'node:22-bookworm-slim@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5'


def docker(*arguments, input=None, allow=False, timeout=120):
    process = subprocess.run(['docker', *arguments], input=input, capture_output=True, timeout=timeout)
    if process.returncode and not allow:
        raise RuntimeError('docker_' + arguments[0] + '_failed')
    return process


def inspect(kind, name):
    result = docker(kind, 'inspect', name, allow=True)
    if result.returncode:
        return None
    return json.loads(result.stdout)[0]


def owned(kind, name, owner):
    obj = inspect(kind, name)
    if obj is None:
        return False
    labels = obj.get('Config', {}).get('Labels', {}) if kind == 'container' else obj.get('Labels', {})
    if labels.get(OWNER_LABEL) != owner:
        raise RuntimeError('fixture_ownership_changed')
    return True


def cleanup(state):
    owner = state['owner']
    for name in reversed(state['containers']):
        if owned('container', name, owner):
            docker('rm', '-f', '-v', name)
    for name in state['volumes']:
        if owned('volume', name, owner):
            docker('volume', 'rm', name)
    if owned('network', state['network'], owner):
        docker('network', 'rm', state['network'])


def http(runner, url, body=None):
    source = ("let s='';for await(const c of process.stdin)s+=c;const q=JSON.parse(s);"
              "const r=await fetch(q.url,{...q.options,signal:AbortSignal.timeout(3000)});"
              "console.log(JSON.stringify({status:r.status,body:await r.text()}));")
    options = {'method': 'POST', 'headers': {'Content-Type': 'application/x-www-form-urlencoded'}, 'body': body} if body else {}
    result = docker('exec', '-i', runner, 'node', '--input-type=module', '-e', source,
                    input=json.dumps({'url': url, 'options': options}).encode(), allow=True, timeout=8)
    return json.loads(result.stdout) if result.returncode == 0 else None


def up(path):
    if path.exists():
        raise RuntimeError('state_file_exists')
    found = []
    for service in ('keycloak', 'keycloak-postgres'):
        ids = docker('ps', '-aq', '--filter', 'label=com.docker.compose.project=' + PROJECT,
                     '--filter', 'label=com.docker.compose.service=' + service).stdout.decode().splitlines()
        found.extend(ids)
    if found:
        raise RuntimeError('source_pair_label_collision')
    endpoint = docker('context', 'inspect', '--format', '{{.Endpoints.docker.Host}}').stdout.decode().strip()
    if not endpoint.startswith(('npipe://', 'unix://')):
        raise RuntimeError('nonlocal_docker_context')
    owner = 'otziv-c35-source-' + uuid.uuid4().hex[:12]
    state = {'owner': owner, 'network': owner + '-net', 'volumes': [], 'containers': [],
             'project': PROJECT, 'keycloakImage': KEYCLOAK, 'postgresImage': POSTGRES}
    pg = owner + '-postgres'
    kc = owner + '-keycloak'
    runner = owner + '-http'
    pg_password, admin_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    try:
        docker('network', 'create', '--internal', '--label', OWNER_LABEL + '=' + owner, state['network'])
        volume = owner + '-pgdata'
        docker('volume', 'create', '--label', OWNER_LABEL + '=' + owner, volume)
        state['volumes'].append(volume)
        docker('run', '-d', '--name', pg, '--label', OWNER_LABEL + '=' + owner,
               '--label', 'com.docker.compose.project=' + PROJECT,
               '--label', 'com.docker.compose.service=keycloak-postgres',
               '--network', state['network'], '--memory', '768m', '--pids-limit', '160',
               '--mount', 'type=volume,source=' + volume + ',target=/var/lib/postgresql/data',
               '-e', 'POSTGRES_DB=keycloak', '-e', 'POSTGRES_USER=keycloak',
               '-e', 'POSTGRES_PASSWORD=' + pg_password, POSTGRES)
        state['containers'].append(pg)
        for _ in range(90):
            if docker('exec', pg, 'pg_isready', '-U', 'keycloak', '-d', 'keycloak', allow=True).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('source_postgres_not_ready')
        docker('run', '-d', '--name', runner, '--label', OWNER_LABEL + '=' + owner,
               '--network', state['network'], '--memory', '128m', '--read-only',
               '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
               '--entrypoint', 'node', NODE, '-e', 'setInterval(()=>{},100000)')
        state['containers'].append(runner)
        docker('run', '-d', '--name', kc, '--label', OWNER_LABEL + '=' + owner,
               '--label', 'com.docker.compose.project=' + PROJECT,
               '--label', 'com.docker.compose.service=keycloak',
               '--network', state['network'], '--memory', '1536m', '--pids-limit', '320',
               '-e', 'KC_DB=postgres', '-e', 'KC_DB_URL=jdbc:postgresql://' + pg + ':5432/keycloak',
               '-e', 'KC_DB_USERNAME=keycloak', '-e', 'KC_DB_PASSWORD=' + pg_password,
               '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=fixtureadmin',
               '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=' + admin_password,
               '-e', 'KC_HEALTH_ENABLED=true', '-e', 'KC_HTTP_ENABLED=true',
               '-e', 'KC_HOSTNAME_STRICT=false', KEYCLOAK,
               'start', '--http-enabled=true', '--hostname-strict=false')
        state['containers'].append(kc)
        discovery = 'http://' + kc + ':8080/realms/master/.well-known/openid-configuration'
        for _ in range(120):
            if not inspect('container', kc)['State']['Running']:
                raise RuntimeError('source_keycloak_exited')
            response = http(runner, discovery)
            if response and response['status'] == 200:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('source_keycloak_not_ready')
        import urllib.parse
        body = urllib.parse.urlencode({'grant_type': 'password', 'client_id': 'admin-cli',
                                       'username': 'fixtureadmin', 'password': admin_password})
        response = http(runner, 'http://' + kc + ':8080/realms/master/protocol/openid-connect/token', body)
        if not response or response['status'] != 200:
            raise RuntimeError('source_admin_login_failed')
        tables = docker('exec', pg, 'psql', '-X', '-q', '-A', '-t', '-U', 'keycloak', '-d', 'keycloak',
                        '-c', "SELECT count(*) FROM pg_tables WHERE schemaname='public'").stdout.decode().strip()
        if tables != '102':
            raise RuntimeError('source_schema_not_102_tables:' + tables)
        state['tableCount'] = int(tables)
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('x', encoding='utf-8') as state_file:
            state_file.write(json.dumps(state, indent=2) + '\n')
        print(json.dumps({'result': 'PASS', 'state': str(path), 'owner': owner,
                          'sourceKeycloak': kc, 'sourcePostgres': pg, 'tableCount': int(tables),
                          'networkInternal': True, 'publishedPorts': 0}))
    except BaseException:
        cleanup(state)
        raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('up', 'down'))
    parser.add_argument('--state', required=True, type=pathlib.Path)
    args = parser.parse_args()
    path = args.state.resolve()
    if path == ROOT or ROOT in path.parents:
        raise RuntimeError('state_must_be_outside_repository')
    if args.action == 'up':
        up(path)
    else:
        state = json.loads(path.read_text(encoding='utf-8'))
        if state.get('project') != PROJECT or not state.get('owner', '').startswith('otziv-c35-source-'):
            raise RuntimeError('invalid_fixture_state')
        cleanup(state)
        print(json.dumps({'result': 'PASS', 'removed': state['owner']}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(json.dumps({'result': 'FAIL', 'error': str(error)}), file=sys.stderr)
        sys.exit(1)
