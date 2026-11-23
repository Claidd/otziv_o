#!/usr/bin/env python3
"""Host-only, pressure-triggered housekeeping; no application files are deleted."""

import argparse
import contextlib
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import time
import uuid


MYSQL_CLIENT = ('MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=socket '
                '-uroot --batch --skip-column-names')
# Images that every deploy delivers again: CI images from the private release
# registry (any loopback port) and the project's hardened infrastructure images.
RELEASE_REPOSITORY = re.compile(r'127\.0\.0\.1:[1-9][0-9]{0,4}/otziv-prepared/ci-[a-z][a-z0-9-]*'
                                r'|ghcr\.io/claidd/otziv-security')
DIGEST = re.compile(r'sha256:[a-f0-9]{64}')
REFERENCE_FILE_LIMIT = 1024 * 1024
STATUS_SCHEMA = 'otziv-ops-disk-status-v1'
DOCKER_DF_TYPES = {'Images': 'images', 'Containers': 'containers', 'Local Volumes': 'volumes',
                   'Build Cache': 'buildCache'}
PROBE_ERRORS = (RuntimeError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired)


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


def created_at(value):
    # Docker reports nanoseconds; Python 3.10 on the host parses microseconds only.
    match = re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?(Z|[+-]\d\d:\d\d)', value)
    if not match:
        raise ValueError('Unexpected Docker image creation time')
    zone = '+00:00' if match[3] == 'Z' else match[3]
    return dt.datetime.fromisoformat(match[1] + '.' + (match[2] or '')[:6].ljust(6, '0') + zone)


def rollback_digests(root):
    """Digests named by the live deployment and by every retained rollback point.

    Deploy keeps the newest three .deploy-backups entries; each stores the Compose
    file and env of the release it replaced, so those images remain available.
    """
    live = root / 'docker-compose.yaml'
    if live.is_symlink() or not live.is_file() or not DIGEST.search(live.read_text(errors='replace')):
        raise RuntimeError('Live Compose image digests are unavailable; release images preserved')
    files = [live, root / '.env']
    backups = root / '.deploy-backups'
    if backups.is_symlink() or not backups.is_dir():
        # Without a rollback point the previous release cannot be identified.
        raise RuntimeError('Deploy rollback points are unavailable; release images preserved')
    def unreadable(error):
        raise error
    for directory, _, names in os.walk(backups, onerror=unreadable):
        files.extend(Path(directory) / name for name in sorted(names))
    digests = set()
    for path in files:
        # Large files are database dumps; Compose, env and rollout plans are small.
        if path.is_symlink() or not path.is_file() or path.stat().st_size > REFERENCE_FILE_LIMIT:
            continue
        digests.update(DIGEST.findall(path.read_text(errors='replace')))
    return digests


def superseded_release(item, protected):
    digests = item.get('RepoDigests') or []
    return bool(digests and not item.get('RepoTags')
                and all(RELEASE_REPOSITORY.fullmatch(reference.split('@', 1)[0]) for reference in digests)
                and item['Id'] not in protected
                and not any(reference.split('@', 1)[1] in protected for reference in digests))


def remove_unreferenced_images(root):
    """Keep tagged, running and rollback images; drop superseded release images.

    A release image survives while any container uses it, the live deployment names
    it, or a retained rollback point names it. Other images with digests are kept.
    """
    ids = command(['docker', 'image', 'ls', '-a', '-q', '--no-trunc']).split()
    if not ids:
        return
    images = json.loads(command(['docker', 'image', 'inspect', *sorted(set(ids))]))
    container_ids = command(['docker', 'container', 'ls', '-a', '-q']).split()
    used = set()
    if container_ids:
        used = {item['Image'] for item in json.loads(command(['docker', 'container', 'inspect', *container_ids]))}
    protected = rollback_digests(root)
    cutoff = dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=7)
    for item in images:
        if item['Id'] in used:
            continue
        if superseded_release(item, protected):
            # Each name is removed without force; removing the last one deletes the
            # image, and Docker refuses if a container started using it meanwhile.
            for reference in item['RepoDigests']:
                command(['docker', 'image', 'rm', reference])
            log(f'Docker: removed superseded release image {item["Id"]}')
            continue
        if item.get('RepoTags') or item.get('RepoDigests'):
            continue
        created = created_at(item['Created'])
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


def iso(seconds):
    return dt.datetime.fromtimestamp(seconds, dt.timezone.utc).isoformat(timespec='seconds')


def docker_usage():
    """`docker system df` per kind, as Docker prints it (sizes are display strings)."""
    result = {}
    for line in command(['docker', 'system', 'df', '--format', '{{json .}}'], timeout=300).splitlines():
        item = json.loads(line)
        kind = DOCKER_DF_TYPES.get(item.get('Type'))
        if kind:
            result[kind] = {'total': item.get('TotalCount'), 'active': item.get('Active'),
                            'size': item.get('Size'), 'reclaimable': item.get('Reclaimable')}
    return result


def container_health():
    """Running, stopped and unhealthy service containers; one-off `compose run` containers are ignored."""
    ids = command(['docker', 'container', 'ls', '-a', '-q']).split()
    items = json.loads(command(['docker', 'container', 'inspect', *ids])) if ids else []
    stopped, unhealthy, total = [], [], 0
    for item in items:
        labels = (item.get('Config') or {}).get('Labels') or {}
        if labels.get('com.docker.compose.oneoff') == 'True':
            continue
        total += 1
        name = (item.get('Name') or '').lstrip('/')
        state = item.get('State') or {}
        if not state.get('Running'):
            stopped.append(name)
        elif (state.get('Health') or {}).get('Status') == 'unhealthy':
            unhealthy.append(name)
    return {'total': total, 'running': total - len(stopped), 'stopped': sorted(stopped), 'unhealthy': sorted(unhealthy)}


def binlog_usage(container):
    retention = mysql(container, 'SELECT @@global.binlog_expire_logs_seconds;')
    if not retention.isdigit():
        raise ValueError('Unexpected MySQL retention setting')
    sizes = []
    for row in mysql(container, 'SHOW BINARY LOGS;').splitlines():
        columns = row.split('\t')
        if len(columns) < 2 or not columns[1].isdigit():
            raise ValueError('Unexpected binary log listing')
        sizes.append(int(columns[1]))
    return {'retentionSeconds': int(retention), 'files': len(sizes), 'bytes': sum(sizes)}


def directory_bytes(path):
    """Allocated bytes of regular files below path, without following symlinks; None when absent or unreadable."""
    try:
        if path.is_symlink() or not path.is_dir():
            return None
    except OSError:
        return None
    total = 0
    for directory, _, names in os.walk(path):
        for name in names:
            try:
                info = (Path(directory) / name).lstat()
            except OSError:
                continue
            if stat.S_ISREG(info.st_mode):
                # Allocated blocks on the Linux host; the apparent size where blocks are unknown.
                blocks = getattr(info, 'st_blocks', None)
                total += blocks * 512 if blocks is not None else info.st_size
    return total


def storage_sizes(root):
    sizes = {'deployBackups': directory_bytes(root / '.deploy-backups'),
             'containerLogs': directory_bytes(Path('/var/lib/docker/containers')),
             'systemJournal': directory_bytes(Path('/var/log/journal'))}
    data = root / 'data'
    if data.is_dir() and not data.is_symlink():
        sizes['data'] = {child.name: directory_bytes(child) for child in sorted(data.iterdir())
                         if child.is_dir() and not child.is_symlink()}
    return sizes


def cleanup_summary(path):
    """The last cleanup recorded in a state file, without its raw byte counters."""
    try:
        value = json.loads(path.read_text()) if path.is_file() and not path.is_symlink() else None
    except (OSError, ValueError):
        return None
    if not isinstance(value, dict) or type(value.get('lastAttemptAt')) not in (int, float):
        return None
    return {'at': iso(value['lastAttemptAt']),
            'beforePercent': (value.get('before') or {}).get('usedPercent'),
            'afterPercent': (value.get('after') or {}).get('usedPercent'),
            'freedBytes': value.get('freedBytes'),
            'failedActions': value.get('failedActions')}


def collect_status(args, now):
    """Host facts for the daily operations report: no secrets, bucket names or backup paths."""
    status = {'schema': STATUS_SCHEMA, 'generatedAt': iso(now), 'usage': usage(args.root),
              'triggerPercent': args.trigger_percent, 'targetPercent': args.target_percent,
              'lastCleanup': cleanup_summary(args.state_dir / 'state.json'),
              'lastPostDeployCleanup': cleanup_summary(args.state_dir / 'post-deploy.json')}
    unavailable = []
    for key, probe in (('docker', docker_usage), ('containers', container_health),
                       ('binlog', lambda: binlog_usage(args.mysql_container)),
                       ('sizes', lambda: storage_sizes(args.root))):
        try:
            status[key] = probe()
        except PROBE_ERRORS:
            status[key] = None
            unavailable.append(key)
    status['unavailable'] = unavailable
    return status


def status_due(path, now, interval):
    try:
        return now - path.stat().st_mtime >= interval or path.stat().st_mtime > now
    except FileNotFoundError:
        return True


def write_status(path, status):
    """Atomic, world-readable (the application reads it through a read-only mount)."""
    folder = path.parent
    if folder.is_symlink() or path.is_symlink():
        raise RuntimeError('Status file must not be a symlink')
    folder.mkdir(mode=0o755, parents=True, exist_ok=True)
    os.chmod(folder, 0o755)
    temporary = folder / (path.name + '.tmp')
    if temporary.is_symlink():
        raise RuntimeError('Status file must not be a symlink')
    temporary.write_text(json.dumps(status, sort_keys=True) + '\n')
    os.chmod(temporary, 0o644)
    temporary.replace(path)


def post_deploy_clean(args):
    """After a release: superseded release images and old build cache, without a fill threshold.

    Running, live and retained rollback images stay protected; apt, journald and MySQL logs are untouched.
    """
    before = usage(args.root)
    errors = []
    for name, action in (('old Docker build cache', prune_build_cache),
                         ('unreferenced Docker images', lambda: remove_unreferenced_images(args.root))):
        try:
            action()
            log(f'Completed: {name}')
        except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
            errors.append(name)
            log(f'Failed: {name} ({type(error).__name__}); continuing other safe cleanup')
    after = usage(args.root)
    return before, after, errors


def clean(args, before):
    actions = [
        ('package download cache', lambda: command(['apt-get', 'clean'])),
        ('archived system journal', lambda: command(['journalctl', '--vacuum-time=7d', '--vacuum-size=256M'])),
        ('old Docker build cache', prune_build_cache),
        ('unreferenced Docker images', lambda: remove_unreferenced_images(args.root)),
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
    # Below the application's 80 % disk alert, so housekeeping runs before the alert fires.
    parser.add_argument('--trigger-percent', type=int, default=75)
    parser.add_argument('--target-percent', type=int, default=70)
    parser.add_argument('--cooldown-seconds', type=int, default=3600)
    parser.add_argument('--ignore-cooldown', action='store_true', help='Manual retry; still requires disk pressure')
    parser.add_argument('--mysql-container', default='my-mysql')
    parser.add_argument('--mysql-pressure-hours', type=int, default=0,
                        help='0 preserves MySQL retention; >=24 explicitly permits shorter retention under pressure')
    parser.add_argument('--report', action='store_true',
                        help='Read-only: print the host status JSON of the daily operations report and exit')
    parser.add_argument('--status-file', type=Path,
                        help='With --apply: refresh this host status JSON (for the operations report)')
    parser.add_argument('--status-interval-seconds', type=int, default=1800)
    parser.add_argument('--post-deploy', action='store_true',
                        help='With --apply: drop superseded release images and old build cache without a threshold')
    args = parser.parse_args(argv)
    if not 1 <= args.target_percent < args.trigger_percent <= 99:
        parser.error('Require 1 <= target < trigger <= 99')
    if args.cooldown_seconds < 300 or (args.mysql_pressure_hours != 0 and args.mysql_pressure_hours < 24):
        parser.error('Cooldown must be >=300s; pressure retention must be 0 or >=24h')
    if args.status_interval_seconds < 300:
        parser.error('Status interval must be >=300s')
    if args.report and (args.apply or args.post_deploy):
        parser.error('--report is read-only and cannot be combined with --apply or --post-deploy')
    if args.post_deploy and not args.apply:
        parser.error('--post-deploy requires --apply')
    if args.status_file is not None and not args.status_file.is_absolute():
        parser.error('Status file must be an absolute path')
    if not args.root.is_absolute() or args.root.is_symlink() or not args.root.is_dir() or args.root.resolve() == Path(args.root.anchor):
        parser.error('Root must be an existing specific absolute deployment directory, not a symlink')
    args.root = args.root.resolve()
    return args


def main(argv=None):
    args = parse_args(argv)
    if args.report:
        log(json.dumps(collect_status(args, time.time()), sort_keys=True))
        return 0
    before = usage(args.root)
    if not args.apply:
        log(json.dumps({'mode': 'dry-run', 'usage': before, 'triggerPercent': args.trigger_percent,
                        'targetPercent': args.target_percent, 'mysqlPressureHours': args.mysql_pressure_hours,
                        'preserved': ['database tables', 'volumes', 'containers', 'tagged images',
                                      'running images', 'images of the live and retained rollback releases',
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
        if args.post_deploy:
            with deploy_lock(args.root) as acquired:
                if not acquired:
                    log('Deployment/maintenance in progress; post-deploy cleanup skipped')
                    return 0
                before_clean, after, errors = post_deploy_clean(args)
                write_state(args.state_dir / 'post-deploy.json', {
                    'lastAttemptAt': now, 'lastUsedPercent': before_clean['usedPercent'], 'before': before_clean,
                    'after': after, 'failedActions': errors,
                    'freedBytes': max(0, after['freeBytes'] - before_clean['freeBytes'])})
                log(json.dumps({'mode': 'post-deploy', 'before': before_clean['usedPercent'],
                                'after': after['usedPercent'], 'failedActions': errors}))
                return 1 if errors else 0
        if args.status_file is not None and status_due(args.status_file, now, args.status_interval_seconds):
            # The report reads a stale or missing file as «no data»; a failed refresh never blocks cleanup.
            try:
                write_status(args.status_file, collect_status(args, now))
            except (RuntimeError, OSError, ValueError) as error:
                log(f'Status file not refreshed ({type(error).__name__})')
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
