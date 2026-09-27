#!/usr/bin/env python3
"""Host-only, pressure-triggered housekeeping; no application files are deleted."""

import argparse
import contextlib
import datetime as dt
import json
import math
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import uuid


MYSQL_CLIENT = ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=socket '
                '-uroot --batch --skip-column-names')


def log(message):
    print(message, flush=True)


def command(args, *, sql=None, timeout=120):
    # SQL travels over stdin. Passwords never enter host arguments or logs.
    result = subprocess.run(args, input=sql, text=True, capture_output=True,
                            timeout=timeout, check=False)
    if result.returncode:
        # Do not echo stderr: database/container diagnostics can include secrets.
        raise RuntimeError(f'{args[0]} failed with exit code {result.returncode}')
    return result.stdout.strip()


def mysql(container, sql):
    return command(['docker', 'exec', '-i', container, 'sh', '-c', MYSQL_CLIENT], sql=sql)


def usage(path):
    value = shutil.disk_usage(path)
    if value.total <= 0:
        raise RuntimeError('Filesystem size is unavailable')
    # Include filesystem reserved blocks, like the application's disk alert.
    used = value.total - value.free
    return {'usedPercent': math.floor(100 * used / value.total + 0.5),
            'freeBytes': value.free, 'totalBytes': value.total}


def severity(percent):
    return 2 if percent >= 95 else 1 if percent >= 90 else 0


def due(before, state, now, trigger, cooldown):
    if before['usedPercent'] < trigger:
        return False
    last = state.get('lastAttemptAt', 0)
    return (now < last or now - last >= cooldown
            or severity(before['usedPercent']) > severity(state.get('lastUsedPercent', 0)))


def read_state(path):
    if not path.exists():
        return {}
    if path.is_symlink():
        raise RuntimeError('Maintenance state must not be a symlink')
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise RuntimeError('Invalid maintenance state')
    for key in ('lastAttemptAt', 'lastUsedPercent'):
        if key not in value or type(value[key]) not in (int, float) or not math.isfinite(value[key]):
            raise RuntimeError('Invalid maintenance state')
    return value


def write_state(path, value):
    temporary = path.with_name(path.name + '.tmp')
    if path.is_symlink() or temporary.is_symlink():
        raise RuntimeError('Maintenance state must not be a symlink')
    temporary.write_text(json.dumps(value) + '\n')
    temporary.replace(path)


@contextlib.contextmanager
def deploy_lock(root):
    """Share the existing deploy mutex; never remove somebody else's lock."""
    lock = root / '.deploy.lock.d'
    try:
        lock.mkdir(mode=0o700)
    except FileExistsError:
        yield False
        return
    owner = lock / 'owner'
    token = 'disk-maintenance-' + uuid.uuid4().hex
    try:
        owner.write_text(token + '\n')
        yield True
    finally:
        if not lock.is_symlink() and not owner.is_symlink() and owner.read_text().strip() == token:
            owner.unlink()
            lock.rmdir()


def purge_mysql(container, pressure_hours):
    row = mysql(container, 'SELECT @@global.binlog_expire_logs_seconds, '
                '@@global.binlog_expire_logs_auto_purge;').split()
    if len(row) != 2 or not all(item.isdigit() for item in row):
        raise RuntimeError('Unexpected MySQL retention settings')
    retention, automatic = map(int, row)
    if not automatic or retention == 0:
        log('MySQL: automatic expiration disabled; preserving binary logs')
        return
    # Preserve the configured retention unless shorter pressure retention was
    # explicitly enabled by the operator for this standalone server.
    keep = min(retention, pressure_hours * 3600) if pressure_hours else retention
    topology = mysql(container, "SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                     "WHERE COMMAND LIKE 'Binlog Dump%'; "
                     'SELECT COUNT(*) FROM performance_schema.replication_connection_configuration; '
                     'SELECT COUNT(*) FROM performance_schema.replication_group_members;').split()
    if topology != ['0', '0', '0']:
        log('MySQL: replication detected or unknown; preserving binary logs')
        return
    mysql(container, f'PURGE BINARY LOGS BEFORE DATE_SUB(NOW(), INTERVAL {keep} SECOND);')
    log(f'MySQL: expired closed binary logs purged; retained at least {keep} seconds')


def remove_unreferenced_images():
    """Keep all tagged, digest-addressable and container-owned rollback images."""
    ids = command(['docker', 'image', 'ls', '-a', '-q', '--no-trunc']).split()
    if not ids:
        return
    images = json.loads(command(['docker', 'image', 'inspect', *sorted(set(ids))]))
    container_ids = command(['docker', 'container', 'ls', '-a', '-q']).split()
    used = set()
    if container_ids:
        used = {item['Image'] for item in json.loads(command(['docker', 'container', 'inspect', *container_ids]))}
    cutoff = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=7)
    for item in images:
        if item.get('RepoTags') or item.get('RepoDigests') or item['Id'] in used:
            continue
        created = dt.datetime.fromisoformat(item['Created'].replace('Z', '+00:00'))
        if created < cutoff:
            # No force: Docker refuses if a container started using it meanwhile.
            command(['docker', 'image', 'rm', item['Id']])
            log(f'Docker: removed unreferenced image {item["Id"]}')


def prune_build_cache():
    # Docker Engine and newer buildx use different names for the same reserve.
    help_text = command(['docker', 'builder', 'prune', '--help'])
    if '--reserved-space' in help_text:
        reserve = '--reserved-space'
    elif '--keep-storage' in help_text:
        reserve = '--keep-storage'
    else:
        raise RuntimeError('Docker builder does not support a cache reserve')
    command(['docker', 'builder', 'prune', '--force', '--filter', 'until=168h', reserve, '1GB'])


def clean(args, before):
    actions = [
        ('package download cache', lambda: command(['apt-get', 'clean'])),
        ('archived system journal', lambda: command(['journalctl', '--vacuum-time=7d', '--vacuum-size=256M'])),
        ('old Docker build cache', prune_build_cache),
        ('unreferenced Docker images', remove_unreferenced_images),
        ('expired MySQL binary logs', lambda: purge_mysql(args.mysql_container, args.mysql_pressure_hours)),
    ]
    errors = []
    after = before
    for name, action in actions:
        if after['usedPercent'] <= args.target_percent:
            break
        try:
            action()
            log(f'Completed: {name}')
        except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
            errors.append(name)
            log(f'Failed: {name} ({type(error).__name__}); continuing other safe cleanup')
        after = usage(args.root)
    return after, errors


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Enable cleanup; default only reports usage and policy')
    parser.add_argument('--root', type=Path, default=Path('/docker'))
    parser.add_argument('--state-dir', type=Path, default=Path('/var/lib/otziv-disk-maintenance'))
    parser.add_argument('--trigger-percent', type=int, default=80)
    parser.add_argument('--target-percent', type=int, default=75)
    parser.add_argument('--cooldown-seconds', type=int, default=3600)
    parser.add_argument('--ignore-cooldown', action='store_true', help='Manual retry; still requires disk pressure')
    parser.add_argument('--mysql-container', default='my-mysql')
    parser.add_argument('--mysql-pressure-hours', type=int, default=0,
                        help='0 preserves MySQL retention; >=24 explicitly permits shorter retention under pressure')
    args = parser.parse_args(argv)
    if not 1 <= args.target_percent < args.trigger_percent <= 99:
        parser.error('Require 1 <= target < trigger <= 99')
    if args.cooldown_seconds < 300 or (args.mysql_pressure_hours != 0 and args.mysql_pressure_hours < 24):
        parser.error('Cooldown must be >=300s; pressure retention must be 0 or >=24h')
    if not args.root.is_absolute() or args.root.is_symlink() or not args.root.is_dir() or args.root.resolve() == Path(args.root.anchor):
        parser.error('Root must be an existing specific absolute deployment directory, not a symlink')
    args.root = args.root.resolve()
    return args


def main(argv=None):
    args = parse_args(argv)
    before = usage(args.root)
    if not args.apply:
        log(json.dumps({'mode': 'dry-run', 'usage': before, 'triggerPercent': args.trigger_percent,
                        'targetPercent': args.target_percent, 'mysqlPressureHours': args.mysql_pressure_hours,
                        'preserved': ['database tables', 'volumes', 'containers', 'tagged/digest images',
                                      'backups', 'user uploads', 'WhatsApp sessions']}))
        return 0
    if os.geteuid() != 0:
        raise RuntimeError('--apply requires root on the Linux Docker host')
    # A systemd stop/timeout must release the deployment mutex as well.
    def terminate(signum, _frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    if args.state_dir.is_symlink():
        raise RuntimeError('State directory must not be a symlink')
    args.state_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    # buildx needs a writable client directory; /root is hidden by the service.
    # Use an isolated local context, without registry credentials or remote hosts.
    docker_config = args.state_dir / 'docker-client'
    if docker_config.is_symlink():
        raise RuntimeError('Docker client directory must not be a symlink')
    docker_config.mkdir(mode=0o700, exist_ok=True)
    os.environ['DOCKER_CONFIG'] = str(docker_config)
    os.environ['DOCKER_HOST'] = 'unix:///var/run/docker.sock'
    for name in ('DOCKER_CONTEXT', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH', 'BUILDX_BUILDER'):
        os.environ.pop(name, None)
    # flock is host-wide and released even if the service is killed.
    import fcntl
    with (args.state_dir / 'lock').open('a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return 0
        state_path = args.state_dir / 'state.json'
        now = time.time()
        state_before = read_state(state_path)
        if before['usedPercent'] < args.trigger_percent or (not args.ignore_cooldown and not due(
                before, state_before, now, args.trigger_percent, args.cooldown_seconds)):
            return 0
        with deploy_lock(args.root) as acquired:
            if not acquired:
                log('Deployment/maintenance in progress; cleanup skipped')
                return 0
            # Persist before external actions so repeated errors also respect cooldown.
            state = {'lastAttemptAt': now, 'lastUsedPercent': before['usedPercent'], 'before': before}
            write_state(state_path, state)
            after, errors = clean(args, before)
            state.update(after=after, failedActions=errors,
                         freedBytes=max(0, after['freeBytes'] - before['freeBytes']))
            write_state(state_path, state)
            log(json.dumps(state))
            if after['usedPercent'] >= args.trigger_percent:
                log('Safe cleanup exhausted; disk alert remains active. Protected data will not be deleted.')
            return 1 if errors else 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        log(f'Maintenance aborted ({type(error).__name__}): {error}')
        raise SystemExit(1)
