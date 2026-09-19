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
import tarfile
from urllib.parse import urlsplit

from ci_image_bundle import OCI_MANIFEST, digest, verify_bundle

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


def upload_location(record, repository, location, blob_digest):
    parsed = urlsplit(location)
    if ((parsed.netloc and parsed.netloc != f"127.0.0.1:{record['port']}")
            or parsed.scheme not in ('', 'http') or parsed.fragment
            or not parsed.path.startswith('/v2/' + repository + '/blobs/uploads/')
            or '..' in parsed.path.split('/') or not re.fullmatch('sha256:[a-f0-9]{64}', blob_digest)):
        raise ValueError('Registry returned an unsafe upload location')
    return parsed.path + '?' + (parsed.query + '&' if parsed.query else '') + 'digest=' + blob_digest


def upload_ci_image(record, archive_path, receipt):
    """Copy exact CI blobs; do not load, retag, recompress or build the image."""
    owned(record)
    if record.get('state') != 'staging':
        raise ValueError('CI images require an owned writable staging registry')
    manifest = verify_bundle(archive_path, receipt)
    repository = 'otziv-prepared/ci-' + receipt['component']

    def exchange(method, path, data=None, headers=None, maximum=1024 * 1024):
        connection = http.client.HTTPConnection('127.0.0.1', record['port'], timeout=180)
        try:
            connection.request(method, path, body=data, headers=headers or {})
            response = connection.getresponse()
            body = response.read(maximum + 1)
            if len(body) > maximum:
                raise RuntimeError('Registry response is too large')
            return response.status, dict(response.getheaders()), body
        finally:
            connection.close()

    with tarfile.open(archive_path, 'r:') as archive:
        for descriptor in [manifest['config'], *manifest['layers']]:
            identity = descriptor['digest']
            status, _, _ = exchange('HEAD', f'/v2/{repository}/blobs/{identity}')
            if status == 200:
                continue
            if status != 404:
                raise RuntimeError('Cannot inspect registry blob')
            status, headers, _ = exchange('POST', f'/v2/{repository}/blobs/uploads/', b'')
            if status != 202:
                raise RuntimeError('Cannot start registry upload')
            location = next((value for key, value in headers.items() if key.lower() == 'location'), '')
            location = upload_location(record, repository, location, identity)
            with archive.extractfile('blobs/sha256/' + identity[7:]) as stream:
                status, _, _ = exchange('PUT', location, stream,
                    {'Content-Type': 'application/octet-stream', 'Content-Length': str(descriptor['size'])})
            if status != 201:
                raise RuntimeError('Registry rejected CI blob')
        raw = archive.extractfile('blobs/sha256/' + receipt['manifestDigest'][7:]).read()
        status, _, _ = exchange('PUT', f"/v2/{repository}/manifests/{receipt['manifestDigest']}", raw,
                               {'Content-Type': OCI_MANIFEST})
        if status != 201:
            raise RuntimeError('Registry rejected CI manifest')
        status, _, stored = exchange('GET', f"/v2/{repository}/manifests/{receipt['manifestDigest']}",
                                    headers={'Accept': OCI_MANIFEST})
        if status != 200 or digest(stored) != receipt['manifestDigest']:
            raise RuntimeError('Transport changed the CI image manifest')
    return f"127.0.0.1:{record['port']}/{repository}@{receipt['manifestDigest']}"

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['create', 'seal', 'stop'])
    parser.add_argument('record', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(globals()[args.action](args.record.resolve())))
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        parser.exit(1, 'Private registry failed: ' + str(error) + '\n')
