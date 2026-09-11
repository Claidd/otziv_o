import copy
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release_ci as ci
import release_registry as registry

HEAD = 'a' * 40
NOW = dt.datetime(2026, 9, 11, tzinfo=dt.timezone.utc)
POLICY = json.loads((Path(__file__).parents[1] / 'security/expected-branch-policy.json').read_text())

class GitHubFixture:
    def __init__(self):
        self.responses = {'/branches/main': {'commit': {'sha': HEAD}},
            '/commits/' + HEAD + '/status': {'sha': HEAD, 'total_count': 0, 'state': 'pending'}}
        self.checks = []
        self.runs = []
        for index, path in enumerate(sorted(set(POLICY['checkWorkflows'].values())), 1):
            self.responses['/actions/workflows/' + path.rsplit('/', 1)[1]] = dict(id=index, path=path, state='active')
            run = dict(id=index, head_sha=HEAD, head_branch='main', event='push', path=path,
                workflow_id=index, repository={'full_name': ci.REPOSITORY}, head_repository={'full_name': ci.REPOSITORY},
                status='completed', conclusion='success', updated_at=NOW.isoformat(), run_attempt=1,
                check_suite_id=index, html_url=f'https://github.com/{ci.REPOSITORY}/actions/runs/{index}')
            self.runs.append(run)
            self.responses[f'/actions/workflows/{index}/runs?head_sha={HEAD}&event=push&branch=main&per_page=100&page=1'] = {
                'total_count': 1, 'workflow_runs': [run]}
            self.responses[f'/actions/runs/{index}'] = run
            jobs = []
            for name, workflow in POLICY['checkWorkflows'].items():
                if workflow != path:
                    continue
                identity = len(self.checks) + 100
                self.checks.append(dict(id=identity, head_sha=HEAD, name=name, check_suite={'id': index},
                    app={'id': ci.ACTION_APP_ID, 'slug': 'github-actions'}, status='completed', conclusion='success'))
                jobs.append(dict(id=identity, name=name, head_sha=HEAD, run_id=index, run_attempt=1,
                    check_run_url=ci.API + '/check-runs/' + str(identity), status='completed', conclusion='success'))
            self.responses[f'/actions/runs/{index}/attempts/1/jobs?per_page=100&page=1'] = {'total_count': len(jobs), 'jobs': jobs}
        self.responses[f'/commits/{HEAD}/check-runs?filter=latest&per_page=100&page=1'] = {
            'total_count': len(self.checks), 'check_runs': self.checks}

    def get(self, path):
        return copy.deepcopy(self.responses[path])

class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.fixture = GitHubFixture()

    def verify(self):
        return ci.verify_ci(HEAD, POLICY, self.fixture.get, NOW)

    def test_exact_successful_main(self):
        result = self.verify()
        self.assertEqual('PASS', result['result'])
        self.assertEqual(4, len(result['runs']))

    def test_never_uses_older_green_run_over_new_failed_run(self):
        key = next(k for k in self.fixture.responses if '/runs?head_sha=' in k)
        body = self.fixture.responses[key]
        failed = dict(body['workflow_runs'][0], id=1000, conclusion='failure')
        body['workflow_runs'].append(failed)
        body['total_count'] = 2
        with self.assertRaisesRegex(ci.GateError, 'not green'):
            self.verify()

    def test_rejects_wrong_source_event_branch_and_repository(self):
        for field, value in [('head_sha', 'b' * 40), ('head_branch', 'feature'), ('event', 'pull_request'),
                ('head_repository', {'full_name': 'fork/repo'})]:
            with self.subTest(field=field):
                self.fixture = GitHubFixture()
                self.fixture.runs[0][field] = value
                with self.assertRaises(ci.GateError):
                    self.verify()

    def test_pending_skipped_stale_or_untrusted_checks_block(self):
        for change in ['pending', 'skipped', 'stale', 'app']:
            with self.subTest(change=change):
                self.fixture = GitHubFixture()
                if change == 'pending': self.fixture.runs[0]['status'] = 'in_progress'
                if change == 'stale': self.fixture.runs[0]['updated_at'] = (NOW - dt.timedelta(days=8)).isoformat()
                if change == 'skipped': self.fixture.checks[0]['conclusion'] = 'skipped'
                if change == 'app': self.fixture.checks[0]['app']['id'] = 1
                with self.assertRaises(ci.GateError): self.verify()

    def test_new_attempt_or_main_movement_during_verification_blocks(self):
        for move_main in [True, False]:
            self.fixture = GitHubFixture()
            reads = 0
            def get(path):
                nonlocal reads
                body = self.fixture.get(path)
                if path == '/branches/main':
                    reads += 1
                    if move_main and reads > 1: body['commit']['sha'] = 'b' * 40
                if not move_main and path == '/actions/runs/1': body['run_attempt'] = 2
                return body
            with self.assertRaises(ci.GateError): ci.verify_ci(HEAD, POLICY, get, NOW)

    def test_failed_external_commit_status_blocks(self):
        self.fixture.responses['/commits/' + HEAD + '/status'].update(total_count=1, state='failure')
        with self.assertRaisesRegex(ci.GateError, 'commit status'): self.verify()

    def test_pagination_must_be_complete(self):
        key = f'/commits/{HEAD}/check-runs?filter=latest&per_page=100&page=1'
        self.fixture.responses[key]['total_count'] += 1
        with self.assertRaisesRegex(ci.GateError, 'Incomplete'): self.verify()

    def test_local_dirty_old_or_non_main_source_never_reaches_github(self):
        for change in ['dirty', 'old', 'branch', 'origin']:
            values = {'remote': 'https://github.com/Claidd/otziv_o.git', 'branch': 'main', 'status': '',
                'HEAD': HEAD, 'refs/remotes/origin/main': HEAD}
            if change == 'dirty': values['status'] = ' M deploy.ps1'
            if change == 'old': values['refs/remotes/origin/main'] = 'b' * 40
            if change == 'branch': values['branch'] = 'feature'
            if change == 'origin': values['remote'] = 'https://example.com/repo'
            def git(repo, *args): return values[args[1] if args[0] == 'rev-parse' else args[0]]
            with patch.object(ci, 'git', git), self.assertRaises(ci.GateError):
                ci.local_revision(Path('.'), refresh=False)

class RegistrySafetyTest(unittest.TestCase):
    def test_foreign_resource_names_rejected_before_docker(self):
        record = dict(owner='a'*32, container='some-production-service', volume='database', port=5000)
        with patch.object(registry, 'run') as docker, self.assertRaises(ValueError): registry.owned(record)
        docker.assert_not_called()

    def test_seal_must_prove_readonly_filesystem_and_write_rejection(self):
        record = dict(owner='a'*32, container='otziv-release-'+'a'*32,
            volume='otziv-release-'+'a'*32+'-data', port=5000, state='readonly')
        for writable, status in [(True, 405), (False, 202)]:
            with tempfile.TemporaryDirectory() as temp:
                path = Path(temp)/'record.json'; path.write_text(json.dumps(record))
                with patch.object(registry, 'owned', return_value={'Mounts': [{'Destination': '/var/lib/registry', 'RW': writable}]}), \
                     patch.object(registry, 'request', return_value=status), self.assertRaises(RuntimeError):
                    registry.seal(path)

if __name__ == '__main__': unittest.main()
