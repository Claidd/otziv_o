"""Private loopback registry used by deploy.ps1; does not modify the VPS."""
from pathlib import Path
import argparse
import http.client
import json
import re
import socket
import subprocess
import time
import uuid

IMAGE = 'docker.io/library/registry@sha256:1be55279f18a2fe1a74edf2664cac61c1bea305b7b4642dab412e7affdcb3e33'
LABEL = 'otziv.release.owner'

def run(*args):
    result = subprocess.run(['docker', *args], capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError('Private registry command failed: ' + args[0])
    return result.stdout

def validate(record):
    owner = record['owner']
    if not re.fullmatch('[0-9a-f]{32}', owner) or record['container'] != 'otziv-release-' + owner \
            or record['volume'] != record['container'] + '-data' or not 1024 <= record['port'] <= 65535:
        raise ValueError('Invalid private registry ownership record')
    return record

def owned(record):
    validate(record)
    value = json.loads(run('inspect', record['container']))[0]
    if value['Config']['Labels'].get(LABEL) != record['owner']:
        raise RuntimeError('Registry container ownership changed')
    volume = json.loads(run('volume', 'inspect', record['volume']))[0]
    if volume['Labels'].get(LABEL) != record['owner']:
        raise RuntimeError('Registry volume ownership changed')
    if value['HostConfig']['PortBindings'].get('5000/tcp') != [
            {'HostIp': '127.0.0.1', 'HostPort': str(record['port'])}]:
        raise RuntimeError('Registry is not confined to the expected loopback port')
    return value

def request(record, method='GET', path='/v2/'):
    connection = http.client.HTTPConnection('127.0.0.1', record['port'], timeout=5)
    try:
        connection.request(method, path, headers={'Accept': 'application/vnd.oci.image.index.v1+json, '
            'application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'})
        response = connection.getresponse()
        response.read()
        return response.status
    finally:
        connection.close()

def start(record, directory, readonly):
    config = directory / ('registry-readonly.yml' if readonly else 'registry-staging.yml')
    config.write_text('version: 0.1\nlog:\n  level: warn\nstorage:\n  filesystem:\n    rootdirectory: /var/lib/registry\n'
        '  maintenance:\n    uploadpurging:\n      enabled: false\n    readonly:\n      enabled: '
        + str(readonly).lower() + '\nhttp:\n  addr: :5000\n  relativeurls: true\n')
    run('run', '-d', '--name', record['container'], '--label', LABEL + '=' + record['owner'],
        '--publish', f"127.0.0.1:{record['port']}:5000", '--read-only', '--cap-drop=ALL',
        '--security-opt=no-new-privileges:true', '--memory', '384m', '--pids-limit', '128',
        '--tmpfs', '/tmp:rw,nosuid,size=16m', '--env', 'OTEL_TRACES_EXPORTER=none',
        '--mount', f'type=bind,source={config},target=/etc/distribution/config.yml,readonly',
        '--mount', 'type=volume,source=' + record['volume'] + ',target=/var/lib/registry' + (',readonly' if readonly else ''), IMAGE)
    for _ in range(40):
        try:
            if request(record) == 200:
                owned(record)
                return
        except OSError:
            pass
        time.sleep(.5)
    raise RuntimeError('Private registry did not become ready')

def create(path):
    if path.exists():
        raise ValueError('Use a new release directory')
    path.parent.mkdir(parents=True, exist_ok=True)
    owner = uuid.uuid4().hex
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    record = dict(owner=owner, port=port, container='otziv-release-' + owner,
        volume='otziv-release-' + owner + '-data', namespace=f'127.0.0.1:{port}/otziv-prepared', state='creating')
    path.write_text(json.dumps(record, indent=2) + '\n')
    run('pull', IMAGE)
    run('volume', 'create', '--label', LABEL + '=' + owner, record['volume'])
    start(record, path.parent, False)
    record['state'] = 'staging'
    path.write_text(json.dumps(record, indent=2) + '\n')
    return record

def seal(path):
    record = validate(json.loads(path.read_text()))
    owned(record)
    if record['state'] == 'staging':
        run('stop', '--time', '20', record['container'])
        run('rm', record['container'])
        start(record, path.parent, True)
    elif record['state'] != 'readonly':
        raise ValueError('Registry is not ready for sealing')
    current = owned(record)
    mount = next(m for m in current['Mounts'] if m['Destination'] == '/var/lib/registry')
    if mount['RW'] or request(record, 'POST', '/v2/readonly-probe/blobs/uploads/') != 405:
        raise RuntimeError('Registry did not reject writes')
    record['state'] = 'readonly'
    path.write_text(json.dumps(record, indent=2) + '\n')
    return record

def stop(path):
    record = validate(json.loads(path.read_text()))
    owned(record)
    run('stop', '--time', '20', record['container'])
    record['state'] = 'stopped'
    path.write_text(json.dumps(record, indent=2) + '\n')
    # Retain the labeled volume and image digests for recovery; never prune.
    return record

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['create', 'seal', 'stop'])
    parser.add_argument('record', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(globals()[args.action](args.record.resolve())))
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, 'Private registry failed: ' + str(error) + '\n')
