"""One expiring, resumable release coordinator in the canonical main checkout.

This is an advisory source freeze for collaborators and a hard deploy mutex.
It never resets files, stops another process or removes Git worktrees.
"""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import time
import uuid

from release_ci import git, require


def process_identity(pid):
    if not pid:
        return None
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return None
        try:
            times = [wintypes.FILETIME() for _ in range(4)]
            if not kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
                return None
            return str((times[0].dwHighDateTime << 32) | times[0].dwLowDateTime)
        finally:
            kernel.CloseHandle(handle)
    try:
        # PID plus birth time prevents PID reuse from reviving a stale lease.
        return Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()[19]
    except (OSError, IndexError):
        return None


@contextmanager
def mutex(path):
    with Path(path).open('a+b') as stream:
        if stream.tell() == 0:
            stream.write(b'\0'); stream.flush()
        stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream, fcntl.LOCK_UN)


def active(value, now, process=process_identity):
    if not value or value.get('state') != 'active':
        return False
    if value.get('pid'):
        # A live deployment cannot be taken over because its clock lease elapsed.
        return process(value['pid']) == value.get('processIdentity')
    return value['expiresAt'] > now


def transition(value, action, revision, tree, owner, token=None, ttl=14400, pid=None, now=None, process=process_identity):
    now = time.time() if now is None else now
    require(60 <= ttl <= 86400, 'Release lease must be between one minute and one day')
    is_active = active(value, now, process)
    if action == 'status':
        return {**(value or {}), 'active': is_active}
    if action == 'begin' and not is_active:
        birth = process(pid) if pid else None
        require(not pid or birth, 'Cannot identify the release process')
        return {'schema': 'otziv-release-session-v1', 'state': 'active', 'token': uuid.uuid4().hex,
                'owner': owner, 'revision': revision, 'tree': tree, 'createdAt': now,
                'expiresAt': now + ttl, 'pid': pid, 'processIdentity': birth}
    require(value and token == value.get('token'), 'Another release owns the checkout; inspect release_session.py status')
    if action == 'finish':
        return {**value, 'state': 'finished', 'finishedAt': now}
    require(is_active, 'Release lease expired or its process stopped; begin a new session')
    require(action != 'begin' or not value.get('pid'),
            'A deployment process already owns this session; wait for it to finish before starting another')
    require(value['tree'] == tree, 'Frozen release content changed; finish the old session and review the new release')
    require(action in ('begin', 'renew', 'advance'), 'Unknown release action')
    require(action == 'advance' or value['revision'] == revision, 'Frozen revision changed; advance only after checking identical merged content')
    birth = process(pid) if pid else value.get('processIdentity')
    require(not pid or birth, 'Cannot identify the deploy process')
    return {**value, 'revision': revision, 'expiresAt': now + ttl,
            'pid': pid or value.get('pid'), 'processIdentity': birth}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['status','begin','renew','advance','finish'])
    p.add_argument('--repo',type=Path,default=Path.cwd());p.add_argument('--owner',default='deploy.ps1')
    p.add_argument('--token');p.add_argument('--ttl',type=int,default=14400);p.add_argument('--pid',type=int)
    a=p.parse_args();repo=a.repo.resolve()
    common=Path(git(repo,'rev-parse','--path-format=absolute','--git-common-dir')).resolve()
    require(common.parent==repo,'Release coordination requires the primary checkout')
    state=common/'otziv-release-session.json'
    with mutex(common/'otziv-release-session.lock'):
        value=json.loads(state.read_text()) if state.exists() else None
        if a.action not in ('status','finish'):
            require(git(repo,'branch','--show-current')=='main','Release coordination requires main')
            require(not git(repo,'status','--porcelain','--untracked-files=normal'),'Freeze only a clean committed release')
        result=transition(value,a.action,git(repo,'rev-parse','HEAD'),git(repo,'rev-parse','HEAD^{tree}'),
                          a.owner,a.token,a.ttl,a.pid)
        if a.action!='status':
            temporary=state.with_suffix('.tmp')
            temporary.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
            os.replace(temporary,state)
    print(json.dumps(result))


if __name__=='__main__':main()
