import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {githubGet, inspectPolicy, observationExitCode} from './verify-branch-policy.mjs';

const HEAD = '1'.repeat(40), MAIN = '2'.repeat(40), NEXT = '3'.repeat(40);
const NOW = Date.parse('2026-09-08T12:00:00Z'), WEEK = 7 * 24 * 60 * 60 * 1000;
const configFor = (names = ['Fixture gate']) => ({schema: 'otziv-required-check-policy-v2', repository: 'fixture/repository', branch: 'main',
  bindingSource: {pullRequest: 2, branch: 'codex/reviewed-fixture', head: HEAD}, expectedCheckNames: names,
  checkWorkflows: Object.fromEntries(names.map(name => [name, '.github/workflows/quality.yml'])),
  proposedPolicy: {required_status_checks: {strict: true, checks: []}, enforce_admins: true,
    required_pull_request_reviews: null, restrictions: null, allow_force_pushes: false, allow_deletions: false}});

function fixture({config = configFor(), candidateHead = HEAD, protectedBranch = false} = {}) {
  const prefix = `repos/${config.repository}`, paths = [], source = config.bindingSource;
  const app = {id: 42, slug: 'github-actions', owner: {login: 'github'}, html_url: 'https://github.com/apps/github-actions'};
  const pull = {number: source.pullRequest, state: 'open', draft: true, merged: false,
    head: {sha: candidateHead, ref: source.branch, repo: {full_name: config.repository}},
    base: {ref: config.branch, repo: {full_name: config.repository}}};
  const protection = {required_status_checks: {strict: true, checks: config.expectedCheckNames.map(context => ({context, app_id: app.id}))},
    enforce_admins: {enabled: true}, allow_force_pushes: {enabled: false}, allow_deletions: {enabled: false}};
  const graphs = new Map();
  for (const head of new Set([source.head, candidateHead])) {
    const offset = graphs.size * 1000;
    const runs = [...new Set(Object.values(config.checkWorkflows))].map((path, index) => ({id: 100 + offset + index, run_attempt: 1,
      check_suite_id: 200 + offset + index, head_sha: head, head_branch: source.branch, event: 'pull_request', path,
      repository: {full_name: config.repository}, head_repository: {full_name: config.repository},
      pull_requests: [{number: source.pullRequest, base: {ref: config.branch}}]}));
    const checks = config.expectedCheckNames.map((name, index) => ({id: 300 + offset + index, name, head_sha: head,
      app: structuredClone(app), check_suite: {id: runs.find(run => run.path === config.checkWorkflows[name]).check_suite_id},
      status: 'completed', conclusion: 'success', completed_at: new Date(NOW - 60000).toISOString()}));
    const jobs = checks.map((check, index) => {const run = runs.find(row => row.check_suite_id === check.check_suite.id);
      return {id: 400 + offset + index, run_id: run.id, run_attempt: run.run_attempt, head_sha: head, name: check.name,
        status: check.status, conclusion: check.conclusion, check_run_url: `https://api.github.com/${prefix}/check-runs/${check.id}`};});
    graphs.set(head, {runs, checks, jobs});
  }
  const state = {app, pull, protection, graphs, config, paths, protectedBranch,
    protectionStatus: protectedBranch ? 200 : 404, rulesStatus: 200, rules: [],
    commits: [...graphs.keys()].map(sha => ({sha})), afterRun: value => value, afterPull: value => value, pullReads: 0};
  const page = (rows, url, key) => {
    const number = Number(url.searchParams.get('page')); assert.equal(url.searchParams.get('per_page'), '100');
    const chunk = rows.slice((number - 1) * 100, number * 100);
    return {status: 200, body: key ? {total_count: rows.length, [key]: chunk} : chunk};
  };
  state.get = async path => {
    paths.push(path); const url = new URL('https://api.github.com/' + path), route = url.pathname.slice(1);
    let result;
    if (path === 'apps/github-actions') result = {status: 200, body: state.app};
    else if (route === `${prefix}/branches/main`) result = {status: 200, body: {protected: state.protectedBranch, commit: {sha: MAIN}}};
    else if (route === `${prefix}/branches/main/protection`) result = {status: state.protectionStatus, body: state.protectionStatus === 200 ? state.protection : null};
    else if (route === `${prefix}/rules/branches/main`) result = {status: state.rulesStatus, body: state.rules};
    else if (route === `${prefix}/rulesets`) result = {status: 200, body: []};
    else if (route === `${prefix}/pulls/2`) { state.pullReads++; result = {status: 200,
      body: state.pullReads === 1 ? state.pull : state.afterPull(structuredClone(state.pull))}; }
    else if (route === `${prefix}/pulls/2/commits`) result = page(state.commits, url);
    else if (route === `${prefix}/actions/runs`) {
      assert.equal(url.searchParams.get('event'), 'pull_request');
      result = page(state.graphs.get(url.searchParams.get('head_sha')).runs, url, 'workflow_runs');
    } else if (route.includes('/commits/') && route.endsWith('/check-runs')) {
      assert.equal(url.searchParams.get('filter'), 'latest'); result = page(state.graphs.get(route.split('/').at(-2)).checks, url, 'check_runs');
    } else if (route.includes('/attempts/') && route.endsWith('/jobs')) {
      const runId = Number(route.split('/').at(-4)), attempt = Number(route.split('/').at(-2));
      const graph = [...state.graphs.values()].find(graph => graph.runs.some(run => run.id === runId));
      result = page(graph.jobs.filter(job => job.run_id === runId && job.run_attempt === attempt), url, 'jobs');
    } else if (route.startsWith(`${prefix}/actions/runs/`)) {
      const runId = Number(route.split('/').at(-1));
      const run = [...state.graphs.values()].flatMap(graph => graph.runs).find(run => run.id === runId);
      result = {status: 200, body: state.afterRun(structuredClone(run))};
    } else throw new Error('unexpected_fixture_endpoint');
    return structuredClone(result);
  };
  state.inspect = (candidate = candidateHead) => inspectPolicy(config, {get: state.get, now: () => NOW, candidateHead: candidate});
  state.changeCheck = (change, index = 0, head = HEAD) => {
    const graph = graphs.get(head); Object.assign(graph.checks[index], change);
    graph.jobs[index].status = graph.checks[index].status; graph.jobs[index].conclusion = graph.checks[index].conclusion;
  };
  return state;
}

test('failed CI establishes trusted identity and a strict twenty-check proposal, never acceptance', async () => {
  const config = configFor(Array.from({length: 20}, (_, i) => `Fixture gate ${i}`)), f = fixture({config});
  f.changeCheck({conclusion: 'failure'}, 18); f.changeCheck({conclusion: 'failure'}, 19);
  const result = await f.inspect();
  assert.equal(result.identity.status, 'IDENTITY_VERIFIED'); assert.equal(result.readyForPolicyReview, true);
  assert.equal(result.serverPolicy.status, 'UNPROTECTED'); assert.equal(result.candidateAcceptance.status, 'BLOCKED');
  assert.equal(result.candidateAcceptance.checks.filter(row => row.state === 'PASSED').length, 18);
  assert.deepEqual(result.proposedRequestBody.required_status_checks, {strict: true, checks: config.expectedCheckNames.map(context => ({context, app_id: 42}))});
  assert.equal(observationExitCode(result), 2); assert.equal(result.remoteMutations, 0);
  assert.ok(f.paths.every(path => !/artifacts|logs|dispatch|rerun/.test(path)));
});

test('enforcement remains verified when the exact candidate failed; explicit enforcement exit is not acceptance', async () => {
  const f = fixture({protectedBranch: true}); f.changeCheck({conclusion: 'failure'});
  const result = await f.inspect();
  assert.equal(result.serverPolicy.status, 'ENFORCEMENT_VERIFIED'); assert.equal(result.candidateAcceptance.status, 'BLOCKED');
  assert.equal(observationExitCode(result, 'enforcement'), 0);
  assert.equal(observationExitCode(result, 'acceptance'), 2); assert.equal(observationExitCode(result), 2);
});

test('historical binding and current candidate use different exact SHAs, independently of main HEAD', async () => {
  const f = fixture({candidateHead: NEXT, protectedBranch: true});
  f.changeCheck({conclusion: 'failure', completed_at: new Date(NOW - 4 * WEEK).toISOString()});
  const result = await f.inspect();
  assert.equal(result.identity.revision, HEAD); assert.equal(result.serverPolicy.revision, MAIN);
  assert.equal(result.candidateAcceptance.revision, NEXT); assert.equal(result.candidateAcceptance.status, 'ALL_REQUIRED_CHECKS_PASSED');
  assert.equal(observationExitCode(result), 0);
  assert.ok(!f.paths.some(path => path.includes(`/commits/${MAIN}/check-runs`)));
});

test('queued, running, cancelled, neutral and skipped checks can prove identity but never acceptance', async () => {
  for (const change of [{status: 'queued', conclusion: null, completed_at: null}, {status: 'in_progress', conclusion: null, completed_at: null},
    {conclusion: 'cancelled'}, {conclusion: 'neutral'}, {conclusion: 'skipped'}, {conclusion: 'timed_out'}]) {
    const f = fixture({protectedBranch: true}); f.changeCheck(change); const result = await f.inspect();
    assert.equal(result.identity.status, 'IDENTITY_VERIFIED'); assert.equal(result.serverPolicy.status, 'ENFORCEMENT_VERIFIED');
    assert.equal(result.candidateAcceptance.status, 'BLOCKED');
  }
});

test('missing, invalid, future and expired completion times block acceptance without invalidating server protection', async () => {
  for (const completed_at of [undefined, null, 'invalid', new Date(NOW + 1).toISOString(), new Date(NOW - WEEK - 1).toISOString()]) {
    const f = fixture({protectedBranch: true}); f.changeCheck({completed_at}); const result = await f.inspect();
    assert.equal(result.identity.status, 'IDENTITY_VERIFIED'); assert.equal(result.serverPolicy.status, 'ENFORCEMENT_VERIFIED');
    assert.equal(result.candidateAcceptance.status, 'BLOCKED');
  }
  const f = fixture({protectedBranch: true}); f.changeCheck({completed_at: new Date(NOW - WEEK).toISOString()});
  assert.equal((await f.inspect()).candidateAcceptance.status, 'ALL_REQUIRED_CHECKS_PASSED');
});

test('without an explicit candidate SHA acceptance is not evaluated and the conservative CLI result stays nonzero', async () => {
  const f = fixture({protectedBranch: true});
  const result = await inspectPolicy(f.config, {get: f.get, now: () => NOW});
  assert.equal(result.serverPolicy.status, 'ENFORCEMENT_VERIFIED'); assert.equal(result.candidateAcceptance.status, 'NOT_EVALUATED');
  assert.equal(observationExitCode(result), 2); assert.equal(observationExitCode(result, 'enforcement'), 0);
});

test('wrong independently resolved GitHub application or unverified app origin rejects all bindings', async () => {
  for (const change of [app => app.id = 77, app => app.slug = 'other', app => app.owner.login = 'not-github', app => app.html_url = 'https://example.invalid']) {
    const f = fixture(); change(f.app); const result = await f.inspect();
    assert.equal(result.identity.status, 'IDENTITY_UNVERIFIED'); assert.equal(result.proposedRequestBody, null);
  }
});

test('foreign PR repository, base, branch or absent immutable source commit fail before a proposal', async () => {
  for (const change of [f => f.pull.head.repo.full_name = 'other/fork', f => f.pull.base.repo.full_name = 'other/repo',
    f => f.pull.base.ref = 'other', f => f.pull.head.ref = 'other', f => f.commits = [{sha: NEXT}]]) {
    const f = fixture(); change(f); const result = await f.inspect();
    assert.equal(result.identity.status, 'IDENTITY_UNVERIFIED'); assert.equal(result.proposedRequestBody, null);
  }
});

test('workflow lineage rejects push/manual runs, foreign repository/head/PR and unreviewed workflow paths', async () => {
  for (const change of [run => run.event = 'push', run => run.event = 'workflow_dispatch', run => run.head_sha = NEXT,
    run => run.repository.full_name = 'other/repo', run => run.head_repository.full_name = 'other/fork',
    run => run.head_branch = 'other', run => run.pull_requests[0].number = 3, run => run.path = '.github/workflows/unreviewed.yml']) {
    const f = fixture(); change(f.graphs.get(HEAD).runs[0]); const result = await f.inspect();
    assert.equal(result.identity.status, 'IDENTITY_UNVERIFIED'); assert.equal(result.candidateAcceptance.status, 'BLOCKED');
  }
});

test('a passing push check cannot replace a failed required PR job with the same name', async () => {
  const f = fixture(); f.changeCheck({conclusion: 'failure'});
  const graph = f.graphs.get(HEAD); graph.checks.unshift({...structuredClone(graph.checks[0]), id: 999,
    conclusion: 'success', check_suite: {id: 999}});
  const result = await f.inspect();
  assert.equal(result.identity.status, 'IDENTITY_VERIFIED'); assert.equal(result.identity.checks[0].checkRunId, 300);
  assert.equal(result.candidateAcceptance.status, 'BLOCKED');
});

test('duplicate PR runs or duplicate matching jobs stay ambiguous regardless of status or ordering', async () => {
  for (const type of ['run', 'job']) for (const reversed of [false, true]) {
    const f = fixture(), graph = f.graphs.get(HEAD);
    const list = type === 'run' ? graph.runs : graph.jobs;
    list.push({...structuredClone(list[0]), id: 999, conclusion: 'failure'}); if (reversed) list.reverse();
    const result = await f.inspect(); assert.equal(result.identity.status, 'IDENTITY_UNVERIFIED');
    assert.equal(result.proposedRequestBody, null); assert.equal(result.candidateAcceptance.status, 'BLOCKED');
  }
});

test('missing required jobs/checks and foreign check URL/app/suite/head cannot establish identity', async () => {
  for (const change of [g => g.jobs = [], g => g.checks = [], g => g.jobs[0].check_run_url = 'https://example.invalid/check-runs/300',
    g => g.jobs[0].check_run_url += '?spoof=1', g => g.checks[0].app.id = 99,
    g => g.checks[0].app.slug = 'other', g => g.checks[0].head_sha = NEXT,
    g => g.checks[0].check_suite.id = 999, g => g.jobs[0].head_sha = NEXT]) {
    const f = fixture(); change(f.graphs.get(HEAD)); const result = await f.inspect();
    assert.equal(result.identity.status, 'IDENTITY_UNVERIFIED'); assert.equal(result.proposedRequestBody, null);
  }
});

test('attempt and check/job state races fail closed without borrowing old successful results', async () => {
  for (const change of [f => f.afterRun = run => ({...run, run_attempt: 2}),
    f => f.graphs.get(HEAD).jobs[0].run_attempt = 2, f => f.graphs.get(HEAD).jobs[0].conclusion = 'failure']) {
    const f = fixture(); change(f); const result = await f.inspect();
    assert.equal(result.identity.status, 'IDENTITY_UNVERIFIED'); assert.equal(result.candidateAcceptance.status, 'BLOCKED');
  }
});

test('candidate PR head drift or closure blocks acceptance while retaining a valid historical binding', async () => {
  for (const change of [pull => ({...pull, head: {...pull.head, sha: NEXT}}), pull => ({...pull, state: 'closed', merged: true})]) {
    const f = fixture({protectedBranch: true}); f.afterPull = change; const result = await f.inspect();
    assert.equal(result.identity.status, 'IDENTITY_VERIFIED'); assert.equal(result.serverPolicy.status, 'ENFORCEMENT_VERIFIED');
    assert.equal(result.candidateAcceptance.status, 'BLOCKED'); assert.equal(result.candidateAcceptance.reason, 'candidate_head_changed');
  }
});

test('server flags, exact app binding and full expected names remain required even with all CI passing', async () => {
  for (const change of [p => p.required_status_checks.strict = false, p => p.enforce_admins.enabled = false,
    p => p.allow_force_pushes.enabled = true, p => p.allow_deletions.enabled = true,
    p => p.required_status_checks.checks[0].app_id = -1, p => p.required_status_checks.checks = []]) {
    const f = fixture({protectedBranch: true}); change(f.protection); const result = await f.inspect();
    assert.notEqual(result.serverPolicy.status, 'ENFORCEMENT_VERIFIED'); assert.equal(result.candidateAcceptance.status, 'ALL_REQUIRED_CHECKS_PASSED');
    assert.equal(observationExitCode(result), 2);
  }
});

test('unavailable protection and ruleset-only policy cannot prove classic enforcement; unrelated rules HTTP does not erase detailed proof', async () => {
  for (const code of [401, 403, 404, 500]) {
    const f = fixture({protectedBranch: true}); f.protectionStatus = code;
    assert.equal((await f.inspect()).serverPolicy.status, 'PROTECTION_UNVERIFIED');
  }
  const f = fixture({protectedBranch: true}); f.protectionStatus = 404;
  f.rules = [{type: 'required_status_checks', parameters: {required_status_checks: [{context: 'Fixture gate', integration_id: 42}]}}];
  const result = await f.inspect(); assert.equal(result.serverPolicy.status, 'RULESET_ONLY_NOT_EVALUATED');
  assert.equal(result.serverPolicy.rulesetEnforcement, 'NOT_EVALUATED');
  const full = fixture({protectedBranch: true}); full.rulesStatus = 403;
  assert.equal((await full.inspect()).serverPolicy.status, 'ENFORCEMENT_VERIFIED');
});

test('bounded pagination handles a second check page without truncation and rejects duplicate/over-limit inputs', async () => {
  const f = fixture(), graph = f.graphs.get(HEAD), actual = graph.checks[0];
  graph.checks = [...Array.from({length: 100}, (_, i) => ({...structuredClone(actual), id: 10000 + i, name: `Other ${i}`})), actual];
  assert.equal((await f.inspect()).identity.status, 'IDENTITY_VERIFIED');
  assert.ok(f.paths.some(path => path.includes('/check-runs?') && path.endsWith('page=2')));
  const duplicate = fixture(); duplicate.graphs.get(HEAD).checks.push(structuredClone(duplicate.graphs.get(HEAD).checks[0]));
  assert.equal((await duplicate.inspect()).identity.reason, 'source_page_duplicate');
  const large = fixture(); large.commits = Array.from({length: 301}, (_, i) => ({sha: (i + 1).toString(16).padStart(40, '0')}));
  assert.equal((await large.inspect()).identity.reason, 'source_page_limit');
});

test('invalid config, old schema, weakened flags and invalid candidate or clock fail before any reads', async () => {
  let reads = 0; const get = async () => { reads++; throw new Error('no_reads_allowed'); };
  for (const change of [c => c.schema = 'otziv-required-check-policy-v1', c => c.expectedCheckNames = [''],
    c => c.expectedCheckNames.push('Fixture gate'), c => c.checkWorkflows['Fixture gate'] = '../bad.yml',
    c => c.bindingSource.head = 'not-a-sha', c => c.proposedPolicy.enforce_admins = false]) {
    const config = configFor(); change(config); await assert.rejects(inspectPolicy(config, {get, now: () => NOW}));
  }
  await assert.rejects(inspectPolicy(configFor(), {get, now: () => NaN}), /clock_invalid/);
  await assert.rejects(inspectPolicy(configFor(), {get, now: () => NOW, candidateHead: 'latest'}), /candidate_head_invalid/);
  assert.equal(reads, 0);
});

test('the transport is fixed-origin GET, rejects redirects and bounds raw response bytes', async t => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls++; assert.equal(url, 'https://api.github.com/apps/github-actions');
    assert.equal(options.method, 'GET'); assert.equal(options.redirect, 'error');
    return new Response(JSON.stringify({id: 42}), {status: 200});
  });
  assert.deepEqual(await githubGet('apps/github-actions'), {status: 200, body: {id: 42}});
  await assert.rejects(githubGet('https://example.invalid/'), /path_invalid/); assert.equal(calls, 1);
  t.mock.method(globalThis, 'fetch', async () => new Response('x'.repeat(1024 * 1024 + 1)));
  await assert.rejects(githubGet('apps/github-actions'), /response_too_large/);
});

test('checked policy contains twenty unchanged names, exact immutable binding origin and no invented runtime IDs', async () => {
  const config = JSON.parse(await readFile(new URL('./expected-branch-policy.json', import.meta.url)));
  assert.equal(config.expectedCheckNames.length, 20); assert.equal(new Set(config.expectedCheckNames).size, 20);
  assert.deepEqual(config.bindingSource, {pullRequest: 2, branch: 'codex/architecture-remediation-20260907', head: '7141d947ef6b6172c76fdbb9f297895fda107e60'});
  assert.deepEqual(config.proposedPolicy.required_status_checks, {strict: true, checks: []});
  assert.equal(config.checkWorkflows['Dependency audit gate'], '.github/workflows/dependency-audit.yml');
  assert.equal(config.checkWorkflows['gitleaks'], '.github/workflows/secret-scan.yml');
  assert.equal(config.checkWorkflows['SQL injection guard'], '.github/workflows/sql-injection-guard.yml');
  assert.equal(config.proposedPolicy.enforce_admins, true); assert.equal(config.proposedPolicy.allow_force_pushes, false);
  assert.equal(config.proposedPolicy.allow_deletions, false);
  assert.ok(!JSON.stringify(config).includes('app_id'));
});
