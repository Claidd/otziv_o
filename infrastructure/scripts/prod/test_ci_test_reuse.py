import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from ci_test_reuse import (DECISION, JOBS, RECORD_STEP, REPOSITORY, SCHEMA, WORKFLOW, RUNTIME_SCHEMA,
                           record, select, validate_decision, validate_proof, verify_live_sources,
                           resolve_scope, collect_outcomes, directory_digest)
from release_ci import ACTION_APP_ID, API, GateError


class ReuseTest(unittest.TestCase):
    def setUp(self):
        self.identity = {'testedRevision': 'a'*40, 'tree': 'b'*40, 'workflowBlob': 'c'*40}
        self.runner = {'ImageOS': 'ubuntu24', 'ImageVersion': '20260920.1', 'RUNNER_ARCH': 'X64', 'RUNNER_OS': 'Linux'}
        self.runtime = {'schema': RUNTIME_SCHEMA, 'runner': self.runner, 'architecture': 'x86_64',
                        'python': '3.12.3', 'node': {'node': '24.18.0'}, 'nodeSha256': '3'*64,
                        'npm': '11.12.1', 'systemPackagesSha256': '4'*64}
        self.now = dt.datetime(2026, 9, 27, 19, tzinfo=dt.timezone.utc)
        self.run = {'id': 123, 'run_attempt': 1, 'head_sha': 'd'*40, 'status': 'completed',
                    'conclusion': 'success', 'path': WORKFLOW, 'event': 'pull_request',
                    'repository': {'full_name': REPOSITORY}, 'head_repository': {'full_name': REPOSITORY},
                    'updated_at': '2026-09-27T18:00:00Z', 'check_suite_id': 66}
        self.pr = {'number': 32, 'merged_at': '2026-09-27T18:10:00Z', 'merge_commit_sha': 'a'*40,
                   'head': {'sha': 'd'*40, 'repo': {'full_name': REPOSITORY}},
                   'base': {'sha': 'e'*40, 'repo': {'full_name': REPOSITORY}, 'ref': 'main'}}
        self.value = {'schema': SCHEMA, 'repository': REPOSITORY, 'workflow': WORKFLOW,
                      'execution': 'fresh', 'scope': 'frontend', 'jobName': JOBS['frontend'],
                      'testedRevision': 'f'*40, 'tree': 'b'*40, 'workflowBlob': 'c'*40,
                      'headRevision': 'd'*40, 'baseRevision': 'e'*40, 'runId': 123,
                      'runAttempt': 1, 'pullRequest': 32, 'environment': self.runner, 'runtime': self.runtime}
        self.job = {'id': 55, 'run_id': 123, 'run_attempt': 1, 'head_sha': 'd'*40,
                    'name': JOBS['frontend'], 'status': 'completed', 'conclusion': 'success',
                    'labels': ['ubuntu-24.04'], 'check_run_url': API + '/check-runs/55',
                    'steps': [{'name': RECORD_STEP, 'conclusion': 'success'}]}
        self.check = {'id': 55, 'name': JOBS['frontend'], 'head_sha': 'd'*40,
                      'status': 'completed', 'conclusion': 'success', 'check_suite': {'id': 66},
                      'app': {'id': ACTION_APP_ID, 'slug': 'github-actions'}}
        self.commit = {'sha': 'f'*40, 'tree': {'sha': 'b'*40}, 'parents': [{'sha': 'e'*40}, {'sha': 'd'*40}]}
        self.artifact = {'id': 88, 'name': 'ci-test-frontend-attempt-1', 'expired': False,
                         'workflow_run': {'id': 123, 'head_sha': 'd'*40}, 'digest': 'sha256:'+'1'*64, 'size_in_bytes': 1000}
        self.selection = {'checks': {s: True for s in JOBS}}
        self.artifacts_list = [self.artifact]
        self.runs = [self.run]
        self.run_reads = 0

    def get(self, path):
        if path.endswith('/pulls'): return [self.pr]
        if path.startswith('/actions/workflows/'): return {'workflow_runs': self.runs}
        if path == '/actions/runs/123':
            self.run_reads += 1
            return copy.deepcopy(self.run)
        if '/attempts/' in path: return {'jobs': [self.job], 'total_count': 1}
        if path == '/check-runs/55': return self.check
        if path.startswith('/git/commits/'): return self.commit
        raise AssertionError(path)

    def artifacts(self, run):
        self.assertEqual(123, run)
        return self.artifacts_list

    def download(self, item, path, maximum):
        self.assertEqual(256*1024, maximum)
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('test-evidence.json', json.dumps(self.value))
        return path

    def binding(self):
        return patch.dict(os.environ, {'GITHUB_SHA': 'a'*40, 'GITHUB_RUN_ID': '900', 'GITHUB_RUN_ATTEMPT': '1'})

    def select(self, mode='active', confirm=True):
        with patch('ci_test_reuse.source', return_value=self.identity), self.binding():
            decision = select(self, Path('.'), self.selection, 'a'*40, mode, self.runner, self.now)
            if confirm:
                for scope in JOBS:
                    decision['checks'][scope] = resolve_scope(decision, scope, self.runtime)['decision']
            return decision

    def validate(self):
        validate_proof(self.value, self.run, self.pr, self.identity, self.runner, self.job, self.check, self.commit)

    def test_identical_actual_merge_tree_transfers_only_executed_coverage(self):
        result = self.select()
        self.assertTrue(result['checks']['frontend']['reused'])
        self.assertFalse(result['checks']['backend']['reused'])
        self.assertEqual('f'*40, result['checks']['frontend']['testedRevision'])
        self.assertEqual('a'*40, result['revision'])
        validate_decision(result, 'a'*40, 900, 1)
        verify_live_sources(self, result)

    def test_shadow_reports_eligibility_without_skipping_tests(self):
        result = self.select('shadow')
        self.assertTrue(result['checks']['frontend']['eligible'])
        self.assertFalse(any(r['reused'] for r in result['checks'].values()))

    def test_off_does_not_access_github(self):
        with patch.object(self, 'get', side_effect=AssertionError('network')):
            self.assertFalse(any(r['reused'] for r in self.select('off')['checks'].values()))

    def test_content_workflow_and_runner_changes_execute_full_checks(self):
        for key, value in [('tree', '9'*40), ('workflowBlob', '9'*40), ('environment', {**self.runner, 'ImageVersion': 'next'})]:
            with self.subTest(key=key):
                old=self.value[key]; self.value[key]=value
                self.assertFalse(self.select()['checks']['frontend']['reused']); self.value[key]=old

    def test_expired_missing_and_old_proofs_are_misses(self):
        self.artifact['expired']=True
        self.assertFalse(self.select()['checks']['frontend']['reused'])
        self.artifact['expired']=False; self.artifacts_list=[]
        self.assertFalse(self.select()['checks']['frontend']['reused'])
        self.artifacts_list=[self.artifact]; self.run['updated_at']='2026-09-25T18:00:00Z'
        self.assertFalse(self.select()['checks']['frontend']['reused'])

    def test_fork_and_unmerged_or_different_main_are_not_candidates(self):
        for change in ({'merged_at':None}, {'merge_commit_sha':'9'*40}, {'head': {**self.pr['head'], 'repo': {'full_name':'attacker/repo'}}}):
            old=self.pr; self.pr={**self.pr,**change}
            self.assertFalse(self.select()['checks']['frontend']['reused']); self.pr=old

    def test_latest_failed_run_never_falls_back_to_success(self):
        self.runs=[{**self.run,'id':122},self.run]; self.run['conclusion']='failure'
        with self.assertRaisesRegex(GateError,'Latest source'):self.select()

    def test_rerun_rejects_old_artifact_attempt(self):
        self.run['run_attempt']=2
        self.assertFalse(self.select()['checks']['frontend']['reused'])
        self.artifact['name']='ci-test-frontend-attempt-2'
        with self.assertRaisesRegex(GateError,'attempt'):self.select()

    def test_substituted_scope_artifact_and_commit_are_rejected(self):
        for target,key,bad in [(self.value,'scope','mobile'),(self.artifact,'digest','bad'),
                               (self.commit,'sha','9'*40),(self.artifact,'workflow_run',{'id':124,'head_sha':'d'*40})]:
            old=target[key];target[key]=bad
            with self.subTest(key=key),self.assertRaises(GateError):self.select()
            target[key]=old

    def test_job_check_runner_step_and_source_bindings_fail_closed(self):
        cases=[(self.value,'execution','reused'),(self.value,'baseRevision','9'*40),
               (self.job,'conclusion','skipped'),(self.job,'run_attempt',2),
               (self.job,'labels',['self-hosted']),(self.job,'steps',[]),
               (self.check,'app',{'id':1,'slug':'github-actions'}),
               (self.check,'check_suite',{'id':77}),(self.check,'conclusion','failure'),
               (self.commit,'parents',[{'sha':'d'*40}])]
        for target,key,bad in cases:
            old=target[key];target[key]=bad
            with self.subTest(key=key),self.assertRaises(GateError):self.validate()
            target[key]=old

    def test_live_source_rerun_revokes_transfer_at_deploy(self):
        result=self.select();self.run['run_attempt']=2
        with self.assertRaisesRegex(GateError,'new attempt'):verify_live_sources(self,result)

    def test_decision_cannot_be_relabeled_for_another_main_or_run(self):
        result=self.select()
        for revision,run,attempt in [('0'*40,900,1),('a'*40,901,1),('a'*40,900,2)]:
            with self.assertRaises(GateError):validate_decision(result,revision,run,attempt)
        result['checks']['frontend']['artifactDigest']=''
        with self.assertRaises(GateError):validate_decision(result,'a'*40,900,1)

    def test_backend_proof_requires_complete_suite(self):
        self.value.update(scope='backend',jobName=JOBS['backend'])
        self.job['name']=self.check['name']=JOBS['backend']
        with self.assertRaisesRegex(GateError,'backend'):self.validate()
        self.value.update(coverage={'result':'PASS','shards':3,'classes':742,'tests':5280,'failures':0,'errors':0},coverageSha256='1'*64)
        self.validate()
        self.value['coverage']['failures']=1
        with self.assertRaises(GateError):self.validate()

    def test_source_selection_never_skips_before_installed_runtime_confirmation(self):
        decision = self.select(confirm=False)
        self.assertTrue(decision['checks']['frontend']['eligible'])
        self.assertFalse(any(r['reused'] for r in decision['checks'].values()))

    def test_exact_java_patch_and_bytes_node_packages_sdk_and_browser_must_match(self):
        for extra in ({'java': {'version': '26.0.2+7', 'modulesSha256': '5'*64}},
                      {'androidSdk': {'platforms/android-36': '6'*64}},
                      {'chromiumSha256': '7'*64}, {'npm': 'another'},
                      {'systemPackagesSha256': '8'*64}, {'nodeSha256': '9'*64}):
            with self.subTest(extra=extra), self.binding():
                outcome = resolve_scope(self.select(confirm=False), 'frontend', {**self.runtime, **extra})
                self.assertFalse(outcome['decision']['reused'])
                self.assertIn('executing the full check', outcome['decision']['reason'])

    def test_old_or_incomplete_runtime_evidence_cannot_be_reused(self):
        self.value['schema'] = 'otziv-test-evidence-v1'
        self.assertFalse(self.select()['checks']['frontend']['reused'])
        self.value['schema'] = SCHEMA
        self.value['runtime'] = None
        self.assertFalse(self.select()['checks']['frontend']['reused'])
        self.value['runtime'] = {'schema': RUNTIME_SCHEMA}
        with self.assertRaisesRegex(GateError, 'runtime'):self.select()

    def outcomes(self, decision, directory):
        for scope in JOBS:
            path = Path(directory) / scope / 'check-outcome.json'
            path.parent.mkdir()
            path.write_text(json.dumps(resolve_scope(decision, scope, self.runtime)))

    def test_release_requires_complete_current_attempt_runtime_confirmed_outcomes(self):
        with self.binding(), tempfile.TemporaryDirectory() as directory:
            decision = self.select(confirm=False)
            self.outcomes(decision, directory)
            final = collect_outcomes(copy.deepcopy(decision), directory)
            self.assertTrue(final['checks']['frontend']['reused'])
            path = Path(directory) / 'frontend/check-outcome.json'
            original = json.loads(path.read_text())
            for mutate in ('attempt', 'runtime', 'provenance'):
                row = copy.deepcopy(original)
                if mutate == 'attempt': row['runAttempt'] = 2
                elif mutate == 'runtime': row['runtime']['nodeSha256'] = '0'*64
                else: row['decision']['sourceRunId'] = 999
                path.write_text(json.dumps(row))
                with self.subTest(mutate=mutate), self.assertRaises(GateError):
                    collect_outcomes(copy.deepcopy(decision), directory)
            path.unlink()
            with self.assertRaisesRegex(GateError, 'Missing or duplicate'):
                collect_outcomes(copy.deepcopy(decision), directory)

    def test_release_uses_actual_fallback_not_eligible_prediction(self):
        with self.binding(), tempfile.TemporaryDirectory() as directory:
            decision = self.select(confirm=False)
            self.outcomes(decision, directory)
            path = Path(directory) / 'frontend/check-outcome.json'
            path.write_text(json.dumps(resolve_scope(decision, 'frontend', {**self.runtime, 'npm': 'changed'})))
            final = collect_outcomes(decision, directory)
            self.assertFalse(final['checks']['frontend']['reused'])

    def test_installed_distribution_hash_changes_when_same_version_bytes_change(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'binary'
            path.write_bytes(b'version 1; first bytes')
            before = directory_digest(directory)
            path.write_bytes(b'version 1; replaced bytes')
            self.assertNotEqual(before, directory_digest(directory))

    def test_backend_evidence_requires_all_actual_shards_and_does_not_reuse_mixed_runners(self):
        with tempfile.TemporaryDirectory() as directory, self.binding(), patch.dict(os.environ, {
                'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_SHA': 'f'*40}), \
                patch('ci_test_reuse.environment', return_value=self.runner), \
                patch('ci_test_reuse.source', return_value={**self.identity, 'testedRevision': 'f'*40}):
            decision = {'schema': DECISION, 'revision': 'f'*40, 'runId': 900, 'runAttempt': 1,
                        'tree': 'b'*40, 'mode': 'off', 'checks': {s: {'reused': False} for s in JOBS}}
            outcome = resolve_scope(decision, 'backend', self.runtime)
            root = Path(directory); summary = root / 'suite.json'; result = root / 'outcome.json'
            summary.write_text(json.dumps({'result': 'PASS', 'shards': 3, 'tests': 100, 'classes': 30, 'failures': 0, 'errors': 0}))
            result.write_text(json.dumps(outcome))
            event = {'pull_request': self.pr}
            def issue():
                return record(Path('.'), 'backend', event, summary, result, root)
            with self.assertRaisesRegex(GateError, 'runtime attestations'):issue()
            for index in range(3):
                folder = root / str(index); folder.mkdir()
                (folder / 'shard-runtime.json').write_text(json.dumps({
                    'revision': 'f'*40, 'runId': 900, 'runAttempt': 1, 'index': index, 'runtime': self.runtime}))
            self.assertEqual(self.runtime, issue()['runtime'])
            path = root / '1/shard-runtime.json'; row = json.loads(path.read_text())
            row['runtime']['npm'] = 'changed'; path.write_text(json.dumps(row))
            self.assertIsNone(issue()['runtime'])


if __name__=='__main__':unittest.main()
