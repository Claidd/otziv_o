"""Deliver exact CI artifacts directly from GitHub storage to the VPS.

Only short-lived download URLs cross SSH stdin; the GitHub account token stays
on the operator's computer. The owned registry binds to VPS loopback, is sealed
before rollout, and uses a stable address so unchanged images keep their names.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import urllib.request
import uuid

from ci_artifacts import Client, NoFollow, storage_url, validate_artifact
from ci_release import capacity_plan, import_release, read, validate_release, write
from release_ci import require
import release_registry

PORT = 51738
MODULES = ('release_ci', 'image_layer_capacity', 'deployment_capacity', 'ci_image_bundle',
           'ci_artifacts', 'release_registry', 'ci_release', 'remote_release_transport')
BOOTSTRAP = r'''
import json,os,pathlib,re,sys
p=json.load(sys.stdin)
try:
    root=pathlib.Path(p['path']); owner=p['owner']
    assert re.fullmatch(r'/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+',str(root)) and '..' not in root.parts
    assert re.fullmatch('[a-f0-9]{32}',owner)
    parent=root/'.release-staging'
    if parent.exists(): assert not parent.is_symlink() and parent.stat().st_uid==os.getuid()
    else: parent.mkdir(mode=0o700)
    parent.chmod(0o700)
    directory=parent/owner
    if p['action']=='stop' and not directory.exists():
        print(json.dumps({'stopped':True,'notCreated':True})); sys.exit(0)
    if p['action']=='prepare':
        assert not (root/'.deploy.lock.d').exists()
        directory.mkdir(mode=0o700)
        (directory/'owner').write_text(owner)
        helpers=directory/'helpers'; helpers.mkdir(mode=0o700)
        for name,source in p['modules'].items():
            assert re.fullmatch('[a-z_]+',name)
            target=helpers/(name+'.py'); target.write_text(source); target.chmod(0o600)
    assert directory.is_dir() and not directory.is_symlink() and directory.stat().st_uid==os.getuid()
    assert (directory/'owner').read_text()==owner
    sys.path.insert(0,str(directory/'helpers'))
    from remote_release_transport import remote
    print(json.dumps(remote(p,directory)))
except Exception as error:
    # URLs and signed query strings must never enter logs or error output.
    print('Remote image transport failed: '+type(error).__name__,file=sys.stderr)
    sys.exit(1)
'''


class SignedArtifacts:
    def __init__(self, links):
        self.links = {str(item['metadata']['id']): item for item in links}
        require(len(self.links) == len(links), 'Duplicate artifact links')

    def get(self, path):
        match = re.fullmatch(r'/actions/artifacts/([0-9]+)', path)
        require(match and match[1] in self.links, 'Unexpected remote artifact request')
        return self.links[match[1]]['metadata']

    def download(self, item, output, maximum):
        require(0 < item['size_in_bytes'] <= maximum, 'Artifact exceeds remote limit')
        output = Path(output)
        require(not output.exists(), 'Remote artifact already exists')
        url = storage_url(self.links[str(item['id'])]['url'])
        opener = urllib.request.build_opener(NoFollow())
        digest = hashlib.sha256(); size = 0
        with opener.open(urllib.request.Request(url), timeout=180) as response, output.open('xb') as destination:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                require(size <= maximum, 'Remote artifact exceeds limit')
                digest.update(chunk); destination.write(chunk)
        require(size == item['size_in_bytes'] and 'sha256:' + digest.hexdigest() == item['digest'], 'Remote artifact checksum mismatch')
        return output


def remote(packet, directory):
    record_path = directory/'registry.json'
    action = packet['action']
    if action == 'prepare':
        release = validate_release(packet['release'], packet['release']['revision'])
        # Stage contains ZIP + OCI + registry blobs. Keep another GiB reserve;
        # the normal capacity check separately budgets unpacking and DB backups.
        needed = sum(row['archiveBytes'] for row in release['images'] if row['service']
                     and (row['service'] != 'external-review-worker' or packet.get('worker'))) * 3 + 1024**3
        require(shutil.disk_usage(directory).free > needed + packet['releaseReserveBytes'], 'Insufficient artifact staging capacity')
        registry = release_registry.create(record_path, PORT, packet['owner'])
        plan = import_release(SignedArtifacts(packet['links']), release, registry, directory/'images', packet.get('worker', False))
        registry = release_registry.seal(record_path)
        write(directory/'capacity.json', plan)
        write(directory/'release.json', release)
        return {'registry': registry, 'capacity': plan}
    if action == 'stop' and not record_path.exists():
        return {'stopped': True, 'notCreated': True}
    require(record_path.is_file(), 'Owned remote registry is missing')
    registry = read(record_path)
    require(registry['owner'] == packet['owner'], 'Remote registry owner changed')
    if action == 'seal':
        return release_registry.seal(record_path)
    if action == 'status':
        release_registry.owned(registry)
        require(registry['state'] == 'readonly', 'Remote registry is writable')
        return {'registry': registry, 'capacity': read(directory/'capacity.json')}
    if action == 'stop':
        # A failed pull, start or seal may leave only part of the registry.
        # Stop only the exact labeled container, retaining failed release data.
        release_registry.validate(registry)
        names = release_registry.run('ps', '--all', '--format', '{{.Names}}').decode().splitlines()
        present = registry['container'] in names
        if present:
            current = json.loads(release_registry.run('inspect', registry['container']))[0]
            require((current['Config'].get('Labels') or {}).get(release_registry.LABEL) == packet['owner'], 'Registry container ownership changed')
            if packet.get('completed'):
                release_registry.owned(registry)
            release_registry.run('stop', '--time', '20', registry['container'])
        require(present or not packet.get('completed'), 'Completed registry disappeared')
        registry['state'] = 'stopped'
        write(record_path, registry)
        if packet.get('completed'):
            # Only this release's owned temporary resources, never shared images.
            release_registry.owned(registry)
            release_registry.run('rm', registry['container'])
            release_registry.run('volume', 'rm', registry['volume'])
            require(directory.resolve().parent == Path(packet['path']).resolve()/'.release-staging'
                    and directory.name == packet['owner'] and (directory/'owner').read_text() == packet['owner'], 'Unsafe transport cleanup path')
            shutil.rmtree(directory)
        return {'stopped': True, 'temporaryDataRemoved': bool(packet.get('completed'))}
    raise ValueError('Unknown remote transport action')


def invoke(record, action, **extra):
    for field in ['host', 'user', 'path', 'key', 'known_hosts', 'port']:
        require(field in record, 'Remote transport target missing')
    require(re.fullmatch('[A-Za-z0-9][A-Za-z0-9.-]*', record['host'])
            and re.fullmatch('[a-z_][a-z0-9_-]*', record['user']) and 1 <= record['port'] <= 65535, 'Invalid remote target')
    packet = {'path': record['path'], 'owner': record['owner'], 'action': action, **extra}
    if action == 'prepare':
        packet['modules'] = {name: (Path(__file__).parent/(name+'.py')).read_text(encoding='utf-8-sig') for name in MODULES}
    command = ['ssh', '-T', '-p', str(record['port']), '-i', record['key'], '-o', 'BatchMode=yes',
               '-o', 'IdentitiesOnly=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=15',
               '-o', 'UserKnownHostsFile='+record['known_hosts'].replace('\\','/'), record['user']+'@'+record['host'],
               'python3 -B -c '+shlex.quote(BOOTSTRAP)]
    result = subprocess.run(command, input=json.dumps(packet).encode(), capture_output=True, timeout=1800 if action=='prepare' else 180)
    require(result.returncode == 0, 'Direct VPS image transport failed; owned staging retained for inspection')
    return json.loads(result.stdout)


def validate_remote_plan(record, release, plan, worker=False):
    status = invoke(record, 'status')
    registry = status['registry']
    require(registry['owner'] == record['owner'] and registry['port'] == PORT and registry['state'] == 'readonly', 'Remote transport identity changed')
    refs = {row['component']: f"127.0.0.1:{PORT}/otziv-prepared/ci-{row['component']}@{row['manifestDigest']}" for row in release['images']}
    expected = capacity_plan(release, refs, worker)
    require(plan == expected and status['capacity'] == expected, 'Remote images differ from verified CI')
    return plan


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare','seal','stop'])
    parser.add_argument('--record', type=Path, required=True)
    parser.add_argument('--manifest', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--completed', action='store_true')
    parser.add_argument('--worker', action='store_true')
    # Target is required only when creating the private ownership record.
    for field in ['host','user','key','known-hosts','path']:
        parser.add_argument('--'+field)
    parser.add_argument('--port', type=int)
    args = parser.parse_args()
    if args.action == 'prepare':
        require(not args.record.exists(), 'Use a fresh direct transport record')
        value = validate_release(read(args.manifest), read(args.manifest)['revision'])
        early = read(args.preflight)
        require(early.get('ready') is True and early['capacity']['result']=='PASS', 'Server preflight required')
        record = {field: getattr(args,field) for field in ['host','user','port','key','known_hosts','path']}
        record.update(owner=uuid.uuid4().hex, transport='vps-artifact', state='creating', revision=value['revision'])
        write(args.record, record)
        client = Client(Path.cwd()); links=[]
        for row in value['images']:
            if not row['service'] or (row['service']=='external-review-worker' and not args.worker): continue
            item = client.get(f"/actions/artifacts/{row['artifact']['id']}")
            validate_artifact(item,value['runId'],value['revision'],row['artifact']['name'])
            require(item['digest']==row['artifact']['digest'],'Artifact was substituted')
            links.append({'metadata':item,'url':client.download_url(item)})
        reserve = sum(fs['requiredBytes'] for fs in early['capacity']['filesystems'])
        result = invoke(record,'prepare',release=value,links=links,worker=args.worker,releaseReserveBytes=reserve)
        record.update(state='readonly',namespace=result['registry']['namespace'])
        write(args.record,record);write(args.output,result['capacity'])
        print('Verified CI images downloaded directly on VPS; transport sealed read-only.')
    else:
        record=read(args.record)
        result=invoke(record,args.action,completed=args.completed)
        if args.action=='stop': record['state']='stopped';write(args.record,record)
        print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print signed artifact URLs even when urllib includes one in an error.
        sys.exit('Direct image transport failed: '+type(error).__name__)
