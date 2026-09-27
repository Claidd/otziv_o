"""Preserve unchanged bind-file inodes and skip only proven unchanged containers."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile


def file_hash(path):
    digest=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''): digest.update(chunk)
    return digest.hexdigest()


def sync_bundle(archive_path, root):
    root=Path(root).resolve(); changed=[]; seen=set()
    with tarfile.open(archive_path,'r:gz') as archive:
        members=archive.getmembers()
        if sum(m.size for m in members)>2*1024**3: raise ValueError('Bundle exceeds limit')
        # Validate every path before touching existing configuration.
        for member in members:
            path=PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or '\\' in member.name or not (member.isdir() or member.isfile()):
                raise ValueError('Unsafe bundle member')
            name=path.as_posix()
            if name in seen: raise ValueError('Duplicate bundle member')
            seen.add(name)
            target=root.joinpath(*path.parts)
            for current in [target,*target.parents]:
                if current==root: break
                if current.is_symlink(): raise ValueError('Bundle target is symlinked')
            if target.exists() and ((member.isdir() and not target.is_dir()) or (member.isfile() and not target.is_file())):
                raise ValueError('Bundle changes path type')
        for member in members:
            path=PurePosixPath(member.name); target=root.joinpath(*path.parts)
            if member.isdir(): target.mkdir(parents=True,exist_ok=True,mode=0o700);continue
            target.parent.mkdir(parents=True,exist_ok=True,mode=0o700)
            descriptor,temporary=tempfile.mkstemp(prefix='.release-file-',dir=target.parent)
            try:
                digest=hashlib.sha256(); size=0
                with os.fdopen(descriptor,'wb') as destination, archive.extractfile(member) as source:
                    for chunk in iter(lambda:source.read(1024*1024),b''):
                        size+=len(chunk);digest.update(chunk);destination.write(chunk)
                if size!=member.size: raise ValueError('Incomplete bundle member')
                if target.is_file() and target.stat().st_size==size and file_hash(target)==digest.hexdigest():
                    continue
                # Preserve safe existing mode; new secrets stay private under umask077.
                mode=(target.stat().st_mode & 0o777) if target.exists() else (member.mode & 0o700)
                os.chmod(temporary,mode or 0o600)
                os.replace(temporary,target);changed.append(path.as_posix())
            finally:
                if os.path.exists(temporary):os.unlink(temporary)
    return changed


def unchanged(service, desired_hash, containers, changed, root, desired=None):
    if not desired_hash or len(containers)!=1: return False
    container=containers[0]; state=container.get('State',{}); config=container.get('Config',{})
    if state.get('Status')!='running' or state.get('Health',{}).get('Status','healthy')!='healthy':return False
    if (config.get('Labels') or {}).get('com.docker.compose.config-hash')!=desired_hash:return False
    if desired and desired.get('image') and config.get('Image')!=desired['image']:return False
    root=Path(root).resolve(); paths=[root/name for name in changed]
    for mount in container.get('Mounts',[]):
        if mount.get('Type')!='bind':continue
        source=Path(mount['Source'])
        if any(path==source or source in path.parents for path in paths):return False
    for option in (desired or {}).get('security_opt',[]):
        match=re.match(r'^seccomp[=:](.+)$',option)
        if match and match[1]!='unconfined':
            policy=Path(match[1]); policy=policy if policy.is_absolute() else root/policy
            if policy in paths:return False
    return True


def command(args):
    value=subprocess.run(args,capture_output=True,timeout=90)
    if value.returncode:raise RuntimeError('Cannot establish current Compose state')
    return value.stdout.decode()


def plan(root, env, changed, full=False):
    root=Path(root).resolve()
    prefix=['docker','compose','--project-directory',str(root),'-f',str(root/'docker-compose.yaml'),'--env-file',str(root/env),'--profile','*']
    desired=json.loads(command(prefix+['config','--format','json']))['services']
    hashes=dict(line.split() for line in command(prefix+['config','--hash','*']).splitlines())
    ids=command(prefix+['ps','--all','--quiet']).split()
    actual=json.loads(command(['docker','inspect',*ids])) if ids else []
    force=full or any(name.startswith('infrastructure/scripts/prod/') for name in changed)
    result={}
    for service in desired:
        rows=[c for c in actual if (c['Config'].get('Labels') or {}).get('com.docker.compose.service')==service
              and (c['Config'].get('Labels') or {}).get('com.docker.compose.oneoff','False').lower()!='true']
        result[service]='retain' if not force and unchanged(service,hashes.get(service),rows,changed,root,desired[service]) else 'recreate'
    return {'schema':'otziv-selective-rollout-v1','services':result,'full':force}


def settings_hash(root, env):
    root=Path(root)
    keys=re.compile(r'^(?:KEYCLOAK_|KC_|OTZIV_MOBILE_|OTZIV_APP_BASE_URL=)')
    lines=[line for line in (root/env).read_text().splitlines() if keys.match(line)]
    payload=(root/'infrastructure/scripts/prod/apply-keycloak-prod-settings.sh').read_bytes()+b'\0'+'\n'.join(sorted(lines)).encode()
    return hashlib.sha256(payload).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['sync','plan','unchanged','settings-hash'])
    p.add_argument('--root',type=Path,default=Path.cwd());p.add_argument('--env',default='.env')
    p.add_argument('--archive',type=Path);p.add_argument('--changes',type=Path);p.add_argument('--output',type=Path)
    p.add_argument('--service');p.add_argument('--full',action='store_true');a=p.parse_args()
    if a.action=='sync':value=sync_bundle(a.archive,a.root)
    elif a.action=='plan':value=plan(a.root,a.env,json.loads(a.changes.read_text()),a.full)
    elif a.action=='unchanged':
        value=json.loads(a.output.read_text())
        return 0 if value.get('schema')=='otziv-selective-rollout-v1' and value['services'].get(a.service)=='retain' else 1
    else: print(settings_hash(a.root,a.env));return 0
    a.output.write_text(json.dumps(value,indent=2)+'\n');a.output.chmod(0o600)
    if a.action=='plan': print('Selective rollout: '+', '.join(k+'='+v for k,v in value['services'].items()))
    return 0


if __name__=='__main__':
    try: raise SystemExit(main())
    except Exception as error:
        raise SystemExit('Selective rollout could not be proved: '+type(error).__name__)
