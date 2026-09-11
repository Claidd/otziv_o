"""Read-only GitHub gate for deploying the exact clean production main commit."""
from __future__ import annotations
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

REPOSITORY = 'Claidd/otziv_o'
API = 'https://api.github.com/repos/' + REPOSITORY
ACTION_APP_ID = 15368
MAX_AGE = dt.timedelta(days=7)

class GateError(RuntimeError):
    pass

def require(condition, message):
    if not condition:
        raise GateError(message)

def git(repo, *args):
    result = subprocess.run(['git', '--no-replace-objects', '-C', str(repo), *args],
        capture_output=True, env={**os.environ, 'GIT_OPTIONAL_LOCKS': '0', 'GIT_TERMINAL_PROMPT': '0'}, timeout=90)
    require(result.returncode == 0, 'Git check failed: ' + args[0])
    return result.stdout.decode('utf-8').strip()

def local_revision(repo, *, refresh=True):
    origin = git(repo, 'remote', 'get-url', 'origin')
    require(origin in ('https://github.com/Claidd/otziv_o.git', 'https://github.com/Claidd/otziv_o',
        'git@github.com:Claidd/otziv_o.git'), 'Origin must be the production GitHub repository')
    require(git(repo, 'branch', '--show-current') == 'main', 'Switch to the updated main branch before deployment')
    require(not git(repo, 'status', '--porcelain', '--untracked-files=normal'),
        'Commit and merge local changes before deployment; the working copy must be clean')
    if refresh:
        git(repo, 'fetch', '--quiet', '--no-tags', 'origin', 'refs/heads/main:refs/remotes/origin/main')
    head = git(repo, 'rev-parse', 'HEAD')
    require(re.fullmatch('[0-9a-f]{40}', head), 'Invalid local revision')
    require(head == git(repo, 'rev-parse', 'refs/remotes/origin/main'),
        'Local main differs from origin/main; update it before deployment')
    return head

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise GateError('GitHub redirected a credentialed request')

def github_reader(repo):
    token = os.environ.get('OTZIV_GITHUB_READ_TOKEN', '').strip()
    if not token:
        result = subprocess.run(['git', '-C', str(repo), 'credential', 'fill'],
            input='protocol=https\nhost=github.com\n\n', text=True, capture_output=True, timeout=30,
            env={**os.environ, 'GIT_TERMINAL_PROMPT': '0', 'GCM_INTERACTIVE': 'never'})
        require(result.returncode == 0, 'GitHub sign-in is required in Git Credential Manager')
        token = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line).get('password', '')
    require(token, 'A GitHub read credential is required')
    opener = urllib.request.build_opener(NoRedirect())
    def get(path):
        require(path.startswith('/') and not re.search(r'[\r\n#\\]', path) and '..' not in path.split('/'), 'Invalid GitHub path')
        request = urllib.request.Request(API + path, headers={
            'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
            'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'otziv-release-ci'})
        try:
            with opener.open(request, timeout=30) as response:
                data = response.read(4 * 1024 * 1024 + 1)
                require(len(data) <= 4 * 1024 * 1024, 'GitHub response is too large')
                return json.loads(data)
        except urllib.error.HTTPError as error:
            raise GateError(f'GitHub CI cannot be verified (HTTP {error.code})') from None
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            raise GateError('GitHub CI cannot be verified; no deployment started') from None
    return get

def pages(get, path, key):
    rows = []
    for page in range(1, 11):
        body = get(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')
        batch = body.get(key)
        require(isinstance(batch, list), 'Invalid GitHub pagination')
        rows.extend(batch)
        require(len({r['id'] for r in rows}) == len(rows), 'GitHub pagination changed during verification')
        if len(batch) < 100:
            require(len(rows) == body.get('total_count'), 'Incomplete GitHub result')
            return rows
    raise GateError('GitHub result exceeds the verification limit')

def verify_ci(head, policy, get, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    require(re.fullmatch('[0-9a-f]{40}', head), 'Invalid deployment revision')
    require(policy.get('repository') == REPOSITORY and policy.get('branch') == 'main', 'Wrong production CI policy')
    mapping = policy['checkWorkflows']
    require(set(mapping) == set(policy['expectedCheckNames']) and len(mapping) >= 20, 'Incomplete production CI policy')
    require(get('/branches/main')['commit']['sha'] == head, 'Production main changed before CI verification')
    checks = pages(get, '/commits/' + head + '/check-runs?filter=latest', 'check_runs')
    checks_by_id = {c['id']: c for c in checks}
    receipts = []
    for path in sorted(set(mapping.values())):
        require(re.fullmatch(r'\.github/workflows/[a-z0-9-]+\.yml', path), 'Invalid workflow policy path')
        name = path.rsplit('/', 1)[1]
        workflow = get('/actions/workflows/' + name)
        require(workflow['path'] == path and workflow['state'] == 'active', 'Required workflow is not active: ' + name)
        runs = pages(get, f"/actions/workflows/{workflow['id']}/runs?head_sha={head}&event=push&branch=main", 'workflow_runs')
        require(runs, 'No main CI run for ' + name + '; wait for GitHub Actions')
        run = max(runs, key=lambda row: row['id'])
        def check_run(value):
            require(value['head_sha'] == head and value['head_branch'] == 'main' and value['event'] == 'push'
                and value['path'] == path and value['workflow_id'] == workflow['id']
                and value['repository']['full_name'] == REPOSITORY and value['head_repository']['full_name'] == REPOSITORY,
                'CI belongs to a different source or workflow')
            require(value['status'] == 'completed' and value['conclusion'] == 'success',
                'CI is not green: ' + name + '; wait for completion or fix the failed run')
            finished = dt.datetime.fromisoformat(value['updated_at'].replace('Z', '+00:00'))
            require(dt.timedelta(minutes=-5) <= now - finished <= MAX_AGE, 'CI evidence is stale; rerun main checks')
        check_run(run)
        jobs = pages(get, f"/actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", 'jobs')
        for expected in (key for key, value in mapping.items() if value == path):
            matches = [j for j in jobs if j['name'] == expected]
            require(len(matches) == 1, 'Required CI job is missing or ambiguous: ' + expected)
            job = matches[0]
            require(job['head_sha'] == head and job['run_id'] == run['id'] and job['run_attempt'] == run['run_attempt']
                and job['status'] == 'completed' and job['conclusion'] == 'success', 'Required CI job failed or was skipped: ' + expected)
            url = job.get('check_run_url', '')
            require(url.startswith(API + '/check-runs/'), 'Unexpected CI check URL')
            suffix = url.removeprefix(API + '/check-runs/')
            require(suffix.isdigit(), 'Invalid CI check identity')
            check = checks_by_id.get(int(suffix), {})
            require(check.get('head_sha') == head and check.get('name') == expected
                and check.get('check_suite', {}).get('id') == run['check_suite_id']
                and check.get('app', {}).get('id') == ACTION_APP_ID and check.get('app', {}).get('slug') == 'github-actions'
                and check.get('status') == 'completed' and check.get('conclusion') == 'success', 'CI check identity mismatch: ' + expected)
        final = get(f"/actions/runs/{run['id']}")
        check_run(final)
        require(final['run_attempt'] == run['run_attempt'], 'CI was rerun during verification')
        receipts.append({'workflow': path, 'runId': run['id'], 'attempt': run['run_attempt'], 'url': run['html_url']})
    # Third-party security failures on this exact revision also block release.
    for check in checks:
        if check.get('app', {}).get('slug') != 'github-actions':
            require(check.get('status') == 'completed' and check.get('conclusion') in ('success', 'neutral', 'skipped'),
                'External security check is pending or failed: ' + check.get('name', 'unknown'))
    statuses = get('/commits/' + head + '/status')
    require(statuses.get('sha') == head, 'Commit status belongs to another revision')
    require(statuses.get('total_count') == 0 or statuses.get('state') == 'success',
        'An external commit status is pending or failed')
    require(get('/branches/main')['commit']['sha'] == head, 'Production main changed during CI verification')
    return {'result': 'PASS', 'revision': head, 'verifiedAt': now.isoformat(), 'runs': receipts}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--record', type=Path)
    args = parser.parse_args()
    try:
        head = local_revision(args.repo)
        policy = json.loads((args.repo/'infrastructure/scripts/security/expected-branch-policy.json').read_text())
        receipt = verify_ci(head, policy, github_reader(args.repo))
        require(local_revision(args.repo, refresh=False) == head, 'Local source changed during CI verification')
        if args.record:
            args.record.parent.mkdir(parents=True, exist_ok=True)
            args.record.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(receipt))
    except (GateError, KeyError, ValueError, OSError, subprocess.SubprocessError) as error:
        print('Release blocked: ' + (str(error) if isinstance(error, GateError) else type(error).__name__))
        return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
