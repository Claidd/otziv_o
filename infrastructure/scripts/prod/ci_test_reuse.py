"""Transfer test evidence from the merged same-repository PR, never CI statuses.

Only identical complete trees and hosted runner generations qualify. Current
main keeps its own required jobs, fresh audits/scans and release artifacts.
An absent/expired proof is a cache miss; malformed or substituted proof fails.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.error

from ci_artifacts import Client, extract_files, validate_artifact
from release_ci import ACTION_APP_ID, API, REPOSITORY, GateError, git, pages, require

SCHEMA = 'otziv-test-evidence-v1'
DECISION = 'otziv-test-reuse-v1'
WORKFLOW = '.github/workflows/quality-gates.yml'
JOBS = {
    'backend': 'Backend (full Testcontainers suite)',
    'frontend': 'Frontend (unit and production build)',
    'mobile': 'Mobile web (unit and production build)',
    'browser': 'Built web and mobile browser scenarios',
    'android': 'Android native tests, lint and debug assembly',
    'parity': 'Shared client package and compatibility fixtures',
    'whatsapp': 'WhatsApp gateway (unit)',
    'worker': 'External review worker (syntax and health)',
}
RECORD_STEP = 'Record successful check evidence'
MAX_AGE = dt.timedelta(hours=24)


def write(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def environment():
    return {k: os.environ.get(k, '') for k in ('ImageOS', 'ImageVersion', 'RUNNER_ARCH', 'RUNNER_OS')}


def source(repo):
    return {'testedRevision': git(repo, 'rev-parse', 'HEAD'), 'tree': git(repo, 'rev-parse', 'HEAD^{tree}'),
            'workflowBlob': git(repo, 'rev-parse', 'HEAD:' + WORKFLOW)}


def record(repo, scope, event, summary=None):
    require(scope in JOBS, 'Unsupported reusable check')
    require(os.environ.get('GITHUB_EVENT_NAME') == 'pull_request', 'Only PR execution can issue reusable evidence')
    pr = event['pull_request']
    require(pr['head']['repo']['full_name'] == REPOSITORY and pr['base']['repo']['full_name'] == REPOSITORY
            and pr['base']['ref'] == 'main', 'Only a trusted repository PR into main may issue evidence')
    identity = source(repo)
    require(identity['testedRevision'] == os.environ['GITHUB_SHA'], 'Evidence must describe the actual tested checkout')
    value = {'schema': SCHEMA, 'repository': REPOSITORY, 'workflow': WORKFLOW, **identity,
             'scope': scope, 'jobName': JOBS[scope], 'runId': int(os.environ['GITHUB_RUN_ID']),
             'runAttempt': int(os.environ['GITHUB_RUN_ATTEMPT']), 'pullRequest': pr['number'],
             'headRevision': pr['head']['sha'], 'baseRevision': pr['base']['sha'],
             'environment': environment(), 'execution': 'fresh'}
    require(all(value['environment'].values()), 'Hosted runner generation is unavailable')
    if scope == 'backend':
        require(summary is not None, 'Complete backend coverage is required')
        data = json.loads(Path(summary).read_text())
        require(data.get('result') == 'PASS' and data.get('shards') == 3 and data.get('tests', 0) > 0
                and data.get('classes', 0) > 0 and data.get('failures') == 0 and data.get('errors') == 0,
                'Incomplete backend coverage')
        value['coverage'] = data
        value['coverageSha256'] = hashlib.sha256(Path(summary).read_bytes()).hexdigest()
    return value


def successful_run(run, head=None):
    require(run.get('path') == WORKFLOW and run.get('event') == 'pull_request'
            and run.get('repository', {}).get('full_name') == REPOSITORY
            and run.get('head_repository', {}).get('full_name') == REPOSITORY,
            'Untrusted source workflow or fork')
    require(run.get('status') == 'completed' and run.get('conclusion') == 'success', 'Latest source CI is not successful')
    require(head is None or run.get('head_sha') == head, 'Source run belongs to another PR head')
    require(type(run.get('run_attempt')) is int and run['run_attempt'] > 0, 'Invalid source run attempt')


def validate_proof(value, run, pr, identity, runner, job, check, commit):
    require(value.get('schema') == SCHEMA and value.get('repository') == REPOSITORY
            and value.get('workflow') == WORKFLOW and value.get('execution') == 'fresh', 'Invalid test evidence schema/source')
    scope = value.get('scope')
    require(scope in JOBS and value.get('jobName') == JOBS[scope], 'Unknown test coverage')
    require(value.get('runId') == run['id'] and value.get('runAttempt') == run['run_attempt']
            and value.get('headRevision') == run['head_sha'] and value.get('pullRequest') == pr['number']
            and value.get('headRevision') == pr['head']['sha'], 'Evidence from another PR/run/attempt')
    require(value.get('testedRevision') == commit.get('sha') and value.get('tree') == commit.get('tree', {}).get('sha'),
            'Tested checkout is not the GitHub commit/tree')
    require([p['sha'] for p in commit.get('parents', [])] == [value.get('baseRevision'), value['headRevision']],
            'Evidence does not describe the actual PR merge checkout')
    require(value.get('tree') == identity['tree'] and value.get('workflowBlob') == identity['workflowBlob'],
            'Tested files or workflow differ from current main')
    require(value.get('environment') == runner and all(runner.values()), 'Hosted runner generation changed')
    require(job.get('run_id') == run['id'] and job.get('run_attempt') == run['run_attempt']
            and job.get('head_sha') == run['head_sha'] and job.get('name') == JOBS[scope]
            and job.get('status') == 'completed' and job.get('conclusion') == 'success'
            and job.get('labels') == ['ubuntu-24.04'], 'Source test job failed, skipped or uses another runner')
    recorded = [s for s in job.get('steps', []) if s.get('name') == RECORD_STEP]
    require(len(recorded) == 1 and recorded[0].get('conclusion') == 'success', 'Evidence was not issued by the successful job')
    require(check.get('id') == int(job['check_run_url'].removeprefix(API + '/check-runs/'))
            and check.get('name') == JOBS[scope] and check.get('head_sha') == run['head_sha']
            and check.get('check_suite', {}).get('id') == run['check_suite_id']
            and check.get('app', {}).get('id') == ACTION_APP_ID and check.get('app', {}).get('slug') == 'github-actions'
            and check.get('status') == 'completed' and check.get('conclusion') == 'success', 'Source check identity mismatch')
    if scope == 'backend':
        coverage = value.get('coverage', {})
        require(coverage.get('result') == 'PASS' and coverage.get('shards') == 3
                and coverage.get('tests', 0) > 0 and coverage.get('classes', 0) > 0
                and coverage.get('failures') == 0 and coverage.get('errors') == 0
                and re.fullmatch('[a-f0-9]{64}', value.get('coverageSha256', '')), 'Full backend suite proof missing')


def select(client, repo, selection, revision, mode='shadow', runner=None, now=None):
    require(mode in ('active', 'shadow', 'off'), 'Unknown test reuse mode')
    identity = source(repo)
    require(identity['testedRevision'] == revision, 'Current checkout differs from main event')
    result = {'schema': DECISION, 'revision': revision, 'runId': int(os.environ.get('GITHUB_RUN_ID', '0')),
              'runAttempt': int(os.environ.get('GITHUB_RUN_ATTEMPT', '0')), 'tree': identity['tree'], 'mode': mode,
              'checks': {scope: {'reused': False, 'reason': 'No qualifying merged PR evidence'} for scope in JOBS}}
    if mode == 'off':
        return result
    runner = runner or environment()
    now = now or dt.datetime.now(dt.timezone.utc)
    pulls = client.get('/commits/' + revision + '/pulls')
    matches = [p for p in pulls if p.get('merged_at') and p.get('merge_commit_sha') == revision
               and p.get('base', {}).get('ref') == 'main'
               and p.get('head', {}).get('repo', {}).get('full_name') == REPOSITORY
               and p.get('base', {}).get('repo', {}).get('full_name') == REPOSITORY]
    if len(matches) != 1:
        return result
    pr = matches[0]
    runs = client.get('/actions/workflows/quality-gates.yml/runs?event=pull_request&head_sha=' + pr['head']['sha'] + '&per_page=100')
    candidates = runs.get('workflow_runs', [])
    if not candidates:
        return result
    # Never choose an older successful run when the latest run/attempt failed.
    latest = max(candidates, key=lambda r: r['id'])
    run = client.get(f"/actions/runs/{latest['id']}")
    successful_run(run, pr['head']['sha'])
    age = now - dt.datetime.fromisoformat(run['updated_at'].replace('Z', '+00:00'))
    if not dt.timedelta(0) <= age <= MAX_AGE:
        return result
    artifacts = client.artifacts(run['id'])
    jobs = pages(client.get, f"/actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", 'jobs')
    for scope in JOBS:
        if not selection['checks'].get(scope):
            result['checks'][scope]['reason'] = 'Component unchanged; no evidence transfer needed'
            continue
        name = f"ci-test-{scope}-attempt-{run['run_attempt']}"
        matches = [a for a in artifacts if a['name'] == name]
        if not matches or (len(matches) == 1 and matches[0].get('expired')):
            continue
        require(len(matches) == 1, 'Ambiguous test artifact')
        item = validate_artifact(matches[0], run['id'], run['head_sha'], name)
        with tempfile.TemporaryDirectory(prefix='test-evidence-') as directory:
            archive = client.download(item, Path(directory) / 'proof.zip', 256 * 1024)
            extract_files(archive, Path(directory) / 'files', {'test-evidence.json': 128 * 1024})
            value = json.loads((Path(directory) / 'files/test-evidence.json').read_text())
        # Legitimate mismatches take the normal full path. Invalid identities fail below.
        if value.get('tree') != identity['tree'] or value.get('workflowBlob') != identity['workflowBlob'] or value.get('environment') != runner:
            result['checks'][scope]['reason'] = 'Files, workflow or hosted runner generation differ; executing checks'
            continue
        require(value.get('scope') == scope and re.fullmatch('[a-f0-9]{40}', value.get('testedRevision', '')), 'Artifact scope/checkout mismatch')
        matched_jobs = [j for j in jobs if j.get('name') == JOBS[scope]]
        require(len(matched_jobs) == 1, 'Source test job missing or ambiguous')
        job = matched_jobs[0]
        url = job.get('check_run_url', '')
        require(url.startswith(API + '/check-runs/') and url.removeprefix(API + '/check-runs/').isdigit(), 'Invalid check API identity')
        check = client.get(url.removeprefix(API))
        commit = client.get('/git/commits/' + value['testedRevision'])
        validate_proof(value, run, pr, identity, runner, job, check, commit)
        result['checks'][scope] = {'reused': mode == 'active', 'eligible': True,
            'reason': 'Verified identical PR checkout, workflow, hosted runner and successful latest attempt',
            'sourceRunId': run['id'], 'sourceAttempt': run['run_attempt'], 'sourceHead': run['head_sha'],
            'testedRevision': value['testedRevision'], 'tree': value['tree'], 'jobId': job['id'],
            'artifactId': item['id'], 'artifactDigest': item['digest'], 'coverage': value.get('coverage')}
    final = client.get(f"/actions/runs/{run['id']}")
    successful_run(final, pr['head']['sha'])
    require(final['run_attempt'] == run['run_attempt'], 'Source CI was rerun during evidence verification')
    return result


def validate_decision(value, revision, run_id, attempt):
    require(value.get('schema') == DECISION and value.get('revision') == revision
            and value.get('runId') == run_id and value.get('runAttempt') == attempt
            and value.get('mode') in ('active', 'shadow', 'off') and set(value.get('checks', {})) == set(JOBS),
            'Test transfer does not belong to current release')
    require(re.fullmatch('[a-f0-9]{40}', value.get('tree', '')), 'Current test tree missing')
    for row in value['checks'].values():
        require(type(row.get('reused')) is bool, 'Test transfer decision missing')
        if row['reused']:
            require(value['mode'] == 'active' and row.get('eligible') is True and row.get('tree') == value['tree']
                    and all(type(row.get(k)) is int and row[k] > 0 for k in ('sourceRunId', 'sourceAttempt', 'jobId', 'artifactId'))
                    and re.fullmatch('[a-f0-9]{40}', row.get('sourceHead', ''))
                    and re.fullmatch('[a-f0-9]{40}', row.get('testedRevision', ''))
                    and re.fullmatch('sha256:[a-f0-9]{64}', row.get('artifactDigest', '')), 'Incomplete test transfer provenance')
    return value


def verify_live_sources(client, value):
    """Recheck revocable source attempts when the deployment downloads its manifest."""
    seen = set()
    for row in value['checks'].values():
        if not row['reused'] or row['sourceRunId'] in seen:
            continue
        seen.add(row['sourceRunId'])
        run = client.get(f"/actions/runs/{row['sourceRunId']}")
        successful_run(run, row['sourceHead'])
        require(run['run_attempt'] == row['sourceAttempt'], 'Reused source tests have a new attempt; rerun main CI')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['record', 'select'])
    p.add_argument('--scope', choices=JOBS); p.add_argument('--summary', type=Path)
    p.add_argument('--selection', type=Path); p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if args.action == 'record':
        value = record(Path.cwd(), args.scope, json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text()), args.summary)
    else:
        selection = json.loads(args.selection.read_text())
        if os.environ.get('GITHUB_EVENT_NAME') == 'push' and os.environ.get('GITHUB_REF') == 'refs/heads/main':
            try:
                value = select(Client(Path.cwd()), Path.cwd(), selection, os.environ['GITHUB_SHA'],
                               os.environ.get('OTZIV_CI_REUSE_MODE', '') or 'shadow')
            except (urllib.error.URLError, TimeoutError):
                value = select(None, Path.cwd(), selection, os.environ['GITHUB_SHA'], 'off')
        else:
            value = select(None, Path.cwd(), selection, os.environ['GITHUB_SHA'], 'off')
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
            for scope, row in value['checks'].items():
                stream.write(f"reuse_{scope}={str(row['reused']).lower()}\n")
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write('\n### Verified test reuse (' + value['mode'] + ')\n\n')
            for scope, row in value['checks'].items():
                stream.write(f"- {scope}: {'reuse' if row['reused'] else 'execute'}; {row['reason']}\n")
    write(args.output, value)


if __name__ == '__main__':
    main()
