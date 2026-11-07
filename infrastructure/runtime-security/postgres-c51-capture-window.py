"""Open an isolated, internal-only capture window for the C51 PostgreSQL rehearsal.

The unchanged C36 capture guard requires the local prod-like issuer and its
database to share exactly one Internal Docker network. `otziv-prod-local_internal_net`
is a plain bridge since 05.11, so, as for C48, this tool moves only the running
local C36 PostgreSQL and C48 Keycloak containers to a labelled temporary
--internal network with their original aliases, runs `postgres-c51-rehearsal.py
capture`, then always reconnects both to their original Compose network with the
original aliases and addresses, removes the temporary network and verifies the
stand serves Keycloak again. Nothing is restarted, no data is written, and only
the local Docker engine is used.
"""
import argparse, datetime, hashlib, json, pathlib, subprocess, sys, time, urllib.request, uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
PROJECT = 'otziv-prod-local'
NETWORK = 'otziv-prod-local_internal_net'
# Active C48 issuer and accepted C36 database (reviewed-image-activations.json).
SERVICES = {'keycloak': 'ghcr.io/claidd/otziv-security@sha256:2e935efdb594ee4a3f0bc8343b1e6a76def33d70a4981cbd730ce5942ff47009',
            'keycloak-postgres': 'ghcr.io/claidd/otziv-security@sha256:26663e414ba75586834873d6a43d8c1a105c12f58804e7fb961581d61a6dbdd5'}
NGINX = 'nginx'
CHECK_URL = 'http://localhost:8088/keycloak/realms/otziv/.well-known/openid-configuration'
LABEL = 'com.otziv.postgres-c51-capture.owner'
now = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()
commands = []


def docker(*args, allow=False, timeout=180, record=True):
    if record: commands.append(['docker', *args])
    result = subprocess.run(['docker', *args], capture_output=True, timeout=timeout)
    if result.returncode and not allow: raise RuntimeError('docker_failed:' + ' '.join(args[:3]))
    return result


def inspect(name, kind='container'):
    return json.loads(docker(kind, 'inspect', name, record=False).stdout)[0]


def find(service):
    ids = docker('ps', '-aq', '--filter', 'label=com.docker.compose.project=' + PROJECT,
                 '--filter', 'label=com.docker.compose.service=' + service, record=False).stdout.decode().split()
    if len(ids) != 1: raise RuntimeError('service_missing_or_duplicate:' + service)
    return inspect(ids[0])


def attachment(container, network):
    value = container['NetworkSettings']['Networks'].get(network)
    return None if value is None else {'aliases': sorted(value.get('Aliases') or []), 'ipAddress': value.get('IPAddress')}


def identity(container):
    return {'id': container['Id'], 'name': container['Name'].lstrip('/'), 'imageId': container['Image'],
            'startedAt': container['State']['StartedAt'], 'restarts': container['RestartCount'],
            'running': container['State']['Running'], 'networks': sorted(container['NetworkSettings']['Networks']),
            'portBindings': container['HostConfig'].get('PortBindings') or {}}


def stack_status(deadline_seconds=240):
    deadline, status = time.time() + deadline_seconds, None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(CHECK_URL, timeout=5) as response: status = response.status
        except Exception as error: status = getattr(error, 'code', None)
        if status == 200: return status
        time.sleep(3)
    return status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--private-directory', required=True, type=pathlib.Path)
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--record', required=True, type=pathlib.Path)
    args = parser.parse_args()
    private, output = args.private_directory.resolve(), args.output.resolve()
    # Fail before the window opens when the unchanged capture would refuse its directory.
    if private == ROOT or ROOT in private.parents: raise SystemExit('private_directory_must_be_outside_repository')
    if private.exists(): raise SystemExit('capture_directory_already_exists')
    shown = output.relative_to(ROOT).as_posix() if ROOT in output.parents else '<output>'
    owner = uuid.uuid4().hex[:12]
    temporary = 'otziv-postgres-c51-capture-' + owner
    record = {'schema': 'otziv-postgres-c51-capture-window-v1', 'result': 'FAIL', 'productionAccess': False,
              'dataWrites': False, 'containersRestarted': False, 'originalNetwork': NETWORK,
              'temporaryNetwork': {'name': temporary, 'label': LABEL + '=' + owner},
              'checkUrl': CHECK_URL, 'executedScriptSha256': hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()}
    endpoint = subprocess.run(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'],
                              capture_output=True, timeout=30).stdout.decode().strip()
    if not endpoint.startswith(('npipe://', 'unix://')): raise SystemExit('local_docker_only')
    record['dockerEndpoint'] = endpoint.split(':', 1)[0]
    containers = {service: find(service) for service in SERVICES}
    original = {}
    for service, container in containers.items():
        if container['Image'] != SERVICES[service].split('@')[1]:
            raise SystemExit('unexpected_image:' + service)
        if not container['State']['Running'] or container['HostConfig'].get('PortBindings'):
            raise SystemExit('unexpected_runtime:' + service)
        if sorted(container['NetworkSettings']['Networks']) != [NETWORK]:
            raise SystemExit('unexpected_networks:' + service)
        original[service] = attachment(container, NETWORK)
        if not original[service]['aliases'] or not original[service]['ipAddress']: raise SystemExit('missing_alias:' + service)
    record['before'] = {service: {**identity(container), 'attachment': original[service]} for service, container in containers.items()}
    record['stackStatusBefore'] = stack_status(30)
    if record['stackStatusBefore'] != 200: raise SystemExit('stand_not_serving_keycloak')
    created, moved = False, []
    try:
        record['windowOpenedAt'] = now()
        docker('network', 'create', '--internal', '--label', LABEL + '=' + owner, temporary)
        created = True
        net = inspect(temporary, 'network')
        if not net['Internal'] or net['Labels'].get(LABEL) != owner: raise RuntimeError('temporary_network_not_internal')
        record['temporaryNetwork']['internal'] = True
        for service, container in containers.items():
            aliases = [value for alias in original[service]['aliases'] for value in ('--alias', alias)]
            docker('network', 'connect', *aliases, temporary, container['Id'])
            moved.append(service)
            docker('network', 'disconnect', NETWORK, container['Id'])
        isolated = {service: inspect(container['Id']) for service, container in containers.items()}
        for service, container in isolated.items():
            if sorted(container['NetworkSettings']['Networks']) != [temporary]: raise RuntimeError('window_not_isolated:' + service)
            if attachment(container, temporary)['aliases'] != original[service]['aliases']: raise RuntimeError('window_aliases:' + service)
        record['isolated'] = {service: {'networks': sorted(container['NetworkSettings']['Networks']),
                                        'aliases': attachment(container, temporary)['aliases']} for service, container in isolated.items()}
        record['captureStartedAt'] = now()
        command = [sys.executable, '-B', str(ROOT / 'infrastructure/runtime-security/postgres-c51-rehearsal.py'), 'capture',
                   '--private-directory', str(private), '--output', str(output)]
        commands.append(['python', '-B', 'infrastructure/runtime-security/postgres-c51-rehearsal.py', 'capture',
                         '--private-directory', '<private>', '--output', shown])
        capture = subprocess.run(command, capture_output=True, timeout=900)
        record['captureCompletedAt'] = now()
        record['captureExitCode'] = capture.returncode
        capture_path = output / 'capture.json'
        if capture_path.exists():
            record['captureSha256'] = hashlib.sha256(capture_path.read_bytes()).hexdigest()
            record['captureResult'] = json.loads(capture_path.read_bytes())['result']
    finally:
        restore_errors = []
        for service in moved:
            container = containers[service]
            current = inspect(container['Id'])
            if NETWORK not in current['NetworkSettings']['Networks']:
                aliases = [value for alias in original[service]['aliases'] for value in ('--alias', alias)]
                if docker('network', 'connect', *aliases, '--ip', original[service]['ipAddress'], NETWORK, container['Id'], allow=True).returncode:
                    restore_errors.append('fixed_address_unavailable:' + service)
                    docker('network', 'connect', *aliases, NETWORK, container['Id'])
            if temporary in current['NetworkSettings']['Networks']:
                docker('network', 'disconnect', temporary, container['Id'])
        if created:
            if inspect(temporary, 'network')['Labels'].get(LABEL) != owner: raise RuntimeError('temporary_network_ownership_changed')
            docker('network', 'rm', temporary)
        record['windowClosedAt'] = now()
        record['restoreNotes'] = restore_errors
    after = {service: inspect(container['Id']) for service, container in containers.items()}
    record['after'] = {service: {**identity(container), 'attachment': attachment(container, NETWORK)} for service, container in after.items()}
    if record['after']['keycloak']['attachment']['ipAddress'] != original['keycloak']['ipAddress']:
        # nginx resolves its Keycloak upstream at load time; reload it only if the address changed.
        nginx = find(NGINX)
        docker('kill', '--signal', 'HUP', nginx['Id'])
        record['nginxReloaded'] = True
    else:
        record['nginxReloaded'] = False
    record['stackStatus'] = stack_status()
    record['temporaryNetworkRemaining'] = len(docker('network', 'ls', '-q', '--filter', 'label=' + LABEL + '=' + owner, record=False).stdout.split())
    record['commands'] = commands
    keys = ('id', 'imageId', 'startedAt', 'restarts', 'running', 'networks', 'portBindings')
    unchanged = all({key: record['before'][s][key] for key in keys} == {key: record['after'][s][key] for key in keys}
                    and record['before'][s]['attachment']['aliases'] == record['after'][s]['attachment']['aliases'] for s in SERVICES)
    record['restoredExactly'] = unchanged
    record['addressesPreserved'] = all(record['before'][s]['attachment']['ipAddress'] == record['after'][s]['attachment']['ipAddress']
                                       for s in SERVICES)
    ok = (record.get('captureResult') == 'PASS' and record.get('captureExitCode') == 0 and unchanged
          and record['stackStatus'] == 200 and record['temporaryNetworkRemaining'] == 0)
    record['result'] = 'PASS' if ok else 'FAIL'
    args.record.parent.mkdir(parents=True, exist_ok=True)
    args.record.write_bytes((json.dumps(record, indent=2) + '\n').encode())
    print(json.dumps({key: record.get(key) for key in ('result', 'captureResult', 'restoredExactly', 'addressesPreserved', 'stackStatus',
                                                         'windowOpenedAt', 'windowClosedAt', 'restoreNotes')}))
    if not ok: sys.exit(1)


if __name__ == '__main__':
    main()
