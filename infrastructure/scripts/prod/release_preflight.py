"""Read-only VPS probe before downloading CI image archives or preparing transport."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess

from ci_release import capacity_plan, read, write
from release_ci import require

REMOTE = r'''
import json, os, subprocess, sys, types
packet = json.load(sys.stdin)
for name in ('image_layer_capacity', 'deployment_capacity'):
    module = types.ModuleType(name)
    sys.modules[name] = module
    exec(compile(packet['modules'][name], name + '.py', 'exec'), module.__dict__)
from deployment_capacity import check, run
root = packet['path']
if not os.path.isdir(root) or os.path.exists(root + '/.deploy.lock.d'):
    raise RuntimeError('Deployment directory unavailable or another deployment holds the lock')
timer = subprocess.run(['systemctl', 'is-active', '--quiet', 'otziv-prod-up.timer'])
if timer.returncode:
    raise RuntimeError('Production self-heal timer is not active; inspect the server first')
ids = run(['docker', 'ps', '--all', '--quiet']).split()
containers = json.loads(run(['docker', 'inspect', *ids])) if ids else []
images = []
for item in containers:
    labels = item.get('Config', {}).get('Labels') or {}
    if labels.get('com.docker.compose.project.working_dir', '').rstrip('/') != root.rstrip('/'):
        continue
    state = item['State']
    if state['Status'] != 'running' or state.get('Health', {}).get('Status', 'healthy') != 'healthy':
        raise RuntimeError('Production service is not healthy: ' + labels.get('com.docker.compose.service', 'unknown'))
    images.append({'service': labels['com.docker.compose.service'], 'configId': item['Image'],
                   'reference': item['Config']['Image']})
if not images or not any(i['service'] == 'mysql' for i in images):
    raise RuntimeError('Production Compose inventory is missing')
result = {'schema': 'otziv-server-preflight-v1', 'images': images, 'ready': True}
if packet.get('plan'):
    result['capacity'] = check(packet['plan'], packet['revision'], root, None, True,
                               bundle_bytes=packet['bundleBytes'])
print(json.dumps(result))
'''


def probe(host, user, port, key, known_hosts, path, plan=None, bundle_bytes=0):
    require(re.fullmatch('[A-Za-z0-9][A-Za-z0-9.-]*', host)
            and re.fullmatch('[a-z_][a-z0-9_-]*', user) and 1 <= port <= 65535, 'Invalid SSH target')
    require(re.fullmatch(r'/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+/?', path)
            and '..' not in path.split('/'), 'Invalid deployment directory')
    here = Path(__file__).parent
    packet = {'path': path, 'plan': plan, 'revision': plan['revision'] if plan else None,
              'bundleBytes': bundle_bytes, 'modules': {name: (here / (name + '.py')).read_text(encoding='utf-8')
                for name in ('image_layer_capacity', 'deployment_capacity')}}
    command = ['ssh', '-T', '-p', str(port), '-i', str(key), '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
               '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=15',
               '-o', 'UserKnownHostsFile=' + str(known_hosts).replace('\\', '/'), user + '@' + host,
               'sudo -n python3 -B -c ' + shlex.quote(REMOTE)]
    result = subprocess.run(command, input=json.dumps(packet).encode(), capture_output=True, timeout=240)
    require(result.returncode == 0, 'Read-only server preflight failed: ' + result.stderr.decode(errors='replace')[-1500:])
    value = json.loads(result.stdout)
    require(value.get('ready') is True, 'Server readiness not established')
    if plan:
        require(value['capacity']['result'] == 'PASS', 'Insufficient VPS space before image download: ' + json.dumps(value['capacity']['filesystems']))
    return value


def add_arguments(parser):
    parser.add_argument('--host', required=True)
    parser.add_argument('--user', required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--key', type=Path, required=True)
    parser.add_argument('--known-hosts', type=Path, required=True)
    parser.add_argument('--path', required=True)


def probe_args(args, **kwargs):
    return probe(args.host, args.user, args.port, args.key, args.known_hosts, args.path, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_arguments(parser)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    value = read(args.manifest)
    size = value['bundleBytes'] + (args.apk.stat().st_size if args.apk else 0)
    result = probe_args(args, plan=capacity_plan(value), bundle_bytes=size)
    write(args.output, result)
    print('Server readiness and early capacity preflight: PASS; no VPS files or services changed.')


if __name__ == '__main__':
    main()
