import {readFile, writeFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const CHECK_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
const MAX_PAGES = 3;
const positiveId = value => Number.isSafeInteger(value) && value > 0;
const validSha = value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value);
const require = (condition, code) => { if (!condition) throw new Error(code); };

// Fixed API origin and GET only. Credentials are never part of returned evidence.
export async function githubGet(path) {
  require(typeof path === 'string' && /^(repos\/|apps\/github-actions$)/.test(path)
    && !/[\\\r\n#]/.test(path) && !path.split('?')[0].split('/').includes('..'), 'github_path_invalid');
  const response = await fetch('https://api.github.com/' + path, {method: 'GET', redirect: 'error',
    headers: {Accept: 'application/vnd.github+json', 'User-Agent': 'otziv-readonly-required-check-verifier-v2',
      ...(process.env.OTZIV_GITHUB_READ_TOKEN ? {Authorization: `Bearer ${process.env.OTZIV_GITHUB_READ_TOKEN}`} : {})},
    signal: AbortSignal.timeout(10000)});
  const chunks = []; let bytes = 0;
  for await (const chunk of response.body) {
    bytes += chunk.length; require(bytes <= 1024 * 1024, 'github_response_too_large'); chunks.push(chunk);
  }
  return {status: response.status, body: response.ok ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : null};
}

function validateConfig(config, candidateHead, clock) {
  const names = config.expectedCheckNames, source = config.bindingSource, proposed = config.proposedPolicy;
  require(config.schema === 'otziv-required-check-policy-v2', 'branch_policy_schema_v2_required');
  require(/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(config.repository || '')
    && typeof config.branch === 'string' && config.branch.length > 0
    && Array.isArray(names) && names.length > 0 && names.length <= 100 && new Set(names).size === names.length
    && names.every(name => typeof name === 'string' && name.trim())
    && source && positiveId(source.pullRequest) && typeof source.branch === 'string' && source.branch.length > 0 && validSha(source.head)
    && config.checkWorkflows && Object.keys(config.checkWorkflows).length === names.length
    && names.every(name => /^\.github\/workflows\/[A-Za-z0-9_-]+\.ya?ml$/.test(config.checkWorkflows[name] || ''))
    && proposed?.required_status_checks?.strict === true && Array.isArray(proposed.required_status_checks.checks)
    && proposed.required_status_checks.checks.length === 0 && proposed.enforce_admins === true
    && proposed.allow_force_pushes === false && proposed.allow_deletions === false, 'branch_policy_config_invalid');
  require(candidateHead === undefined || validSha(candidateHead), 'candidate_head_invalid');
  require(Number.isFinite(clock), 'branch_policy_clock_invalid');
}

async function paged(get, path, key) {
  const rows = []; const ids = new Set(); let total;
  for (let page = 1; page <= MAX_PAGES; page++) {
    const result = await get(`${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`);
    require(result.status === 200, 'source_read_unavailable');
    const list = key ? result.body?.[key] : result.body;
    require(Array.isArray(list) && list.length <= 100, 'source_page_invalid');
    if (key) {
      require(Number.isSafeInteger(result.body.total_count) && result.body.total_count >= 0
        && (total === undefined || total === result.body.total_count), 'source_page_count_changed');
      total = result.body.total_count;
    }
    for (const row of list) {
      const id = key ? row?.id : row?.sha;
      require(key ? positiveId(id) : validSha(id), 'source_row_id_invalid');
      require(!ids.has(id), 'source_page_duplicate'); ids.add(id); rows.push(row);
    }
    if (key ? rows.length === total : list.length < 100) return rows;
    require(list.length === 100 && (total === undefined || rows.length < total), 'source_page_incomplete');
  }
  throw new Error('source_page_limit');
}

function validatePull(config, pull) {
  const source = config.bindingSource;
  require(pull?.number === source.pullRequest && pull.base?.repo?.full_name === config.repository
    && pull.head?.repo?.full_name === config.repository && pull.base?.ref === config.branch
    && pull.head?.ref === source.branch && validSha(pull.head?.sha), 'pull_request_origin_mismatch');
}

function validateRun(config, head, run, path) {
  require(positiveId(run?.id) && positiveId(run.run_attempt) && positiveId(run.check_suite_id)
    && run.head_sha === head && run.head_branch === config.bindingSource.branch
    && run.repository?.full_name === config.repository && run.head_repository?.full_name === config.repository
    && run.event === 'pull_request' && run.path === path
    && Array.isArray(run.pull_requests) && run.pull_requests.some(pr => pr.number === config.bindingSource.pullRequest
      && pr.base?.ref === config.branch), 'workflow_run_origin_mismatch');
}

async function inspectSource(config, head, appId, get, commitIds) {
  require(commitIds.has(head), 'source_commit_not_in_published_pull_request');
  const prefix = `repos/${config.repository}`;
  const [runs, checkRuns] = await Promise.all([
    paged(get, `${prefix}/actions/runs?head_sha=${head}&event=pull_request`, 'workflow_runs'),
    paged(get, `${prefix}/commits/${head}/check-runs?filter=latest`, 'check_runs')
  ]);
  const checksById = new Map(checkRuns.map(row => [row.id, row]));
  const observed = [];
  for (const path of new Set(Object.values(config.checkWorkflows))) {
    // Match the reviewed PR workflow, never a push/manual result with a convenient conclusion.
    const matching = runs.filter(run => run.path === path);
    require(matching.length === 1, matching.length ? 'workflow_run_ambiguous' : 'workflow_run_missing');
    const run = matching[0]; validateRun(config, head, run, path);
    const jobs = await paged(get, `${prefix}/actions/runs/${run.id}/attempts/${run.run_attempt}/jobs`, 'jobs');
    for (const name of config.expectedCheckNames.filter(name => config.checkWorkflows[name] === path)) {
      const candidates = jobs.filter(job => job.name === name);
      require(candidates.length === 1, candidates.length ? 'required_job_ambiguous' : 'required_job_missing');
      const job = candidates[0];
      require(job.run_id === run.id && job.head_sha === head && job.run_attempt === run.run_attempt, 'job_identity_mismatch');
      const urlPrefix = `https://api.github.com/repos/${config.repository}/check-runs/`;
      require(typeof job.check_run_url === 'string' && job.check_run_url.startsWith(urlPrefix)
        && /^[1-9][0-9]*$/.test(job.check_run_url.slice(urlPrefix.length)), 'check_run_url_invalid');
      const checkId = Number(job.check_run_url.slice(urlPrefix.length)), check = checksById.get(checkId);
      require(positiveId(checkId) && check?.head_sha === head && check.name === name
        && check.check_suite?.id === run.check_suite_id && check.app?.slug === 'github-actions'
        && check.app?.id === appId, 'check_identity_mismatch');
      require(['queued', 'in_progress', 'completed'].includes(check.status)
        && check.status === job.status && check.conclusion === job.conclusion, 'check_job_state_changed');
      observed.push({context: name, app_id: appId, checkRunId: check.id, jobId: job.id,
        workflowPath: path, runId: run.id, runAttempt: run.run_attempt,
        status: check.status, conclusion: check.conclusion, completedAt: check.completed_at ?? null});
    }
    const after = await get(`${prefix}/actions/runs/${run.id}`);
    require(after.status === 200, 'workflow_run_readback_unavailable');
    validateRun(config, head, after.body, path);
    require(after.body.id === run.id && after.body.run_attempt === run.run_attempt
      && after.body.check_suite_id === run.check_suite_id, 'workflow_run_attempt_changed');
  }
  return {status: 'IDENTITY_VERIFIED', revision: head, checks: observed};
}

function sourceFailure(error, head) {
  return {status: 'IDENTITY_UNVERIFIED', revision: head,
    reason: /^[a-z_]+$/.test(error.message || '') ? error.message : 'source_read_failed', checks: []};
}

function inspectServer(config, branch, protection, rules, binding) {
  const body = protection.status === 200 ? protection.body : null;
  const actual = Array.isArray(body?.required_status_checks?.checks) ? body.required_status_checks.checks : [];
  const names = new Set([...(body?.required_status_checks?.contexts || []), ...actual.map(row => row.context)]);
  const missing = config.expectedCheckNames.filter(name => !names.has(name));
  const bindingsMatch = binding.status === 'IDENTITY_VERIFIED' && binding.checks.every(expected =>
    actual.filter(row => row.context === expected.context).length === 1
    && actual.some(row => row.context === expected.context && row.app_id === expected.app_id));
  const flagsMatch = body?.required_status_checks?.strict === true && body.enforce_admins?.enabled === true
    && body.allow_force_pushes?.enabled === false && body.allow_deletions?.enabled === false;
  const rulesetChecks = rules.status === 200 && Array.isArray(rules.body) ? rules.body.filter(row => row.type === 'required_status_checks')
    .flatMap(row => row.parameters?.required_status_checks || []) : [];
  const status = branch.body.protected !== true ? 'UNPROTECTED'
    : protection.status === 404 && rulesetChecks.length ? 'RULESET_ONLY_NOT_EVALUATED'
    : protection.status !== 200 ? 'PROTECTION_UNVERIFIED'
    : missing.length ? 'MISSING_REQUIRED_CHECKS'
    : !flagsMatch ? 'POLICY_FLAGS_MISMATCH'
    : binding.status !== 'IDENTITY_VERIFIED' ? 'APP_BINDINGS_UNVERIFIED'
    : !bindingsMatch ? 'APP_BINDINGS_MISMATCH' : 'ENFORCEMENT_VERIFIED';
  return {status, model: 'CLASSIC_BRANCH_PROTECTION', rulesetEnforcement: 'NOT_EVALUATED',
    revision: branch.body.commit.sha, protected: branch.body.protected === true,
    protectionHttpStatus: protection.status, observedPolicy: body,
    requiredCheckNames: [...names], missingRequiredCheckNames: missing, observedRulesetRequiredChecks: rulesetChecks};
}

function inspectAcceptance(source, now) {
  if (source.status !== 'IDENTITY_VERIFIED') return {status: 'BLOCKED', reason: source.reason,
    revision: source.revision, scope: 'REQUIRED_CI_CHECKS_ONLY', checks: []};
  const checks = source.checks.map(row => {
    const time = typeof row.completedAt === 'string' ? Date.parse(row.completedAt) : NaN;
    const state = row.status !== 'completed' ? 'NOT_COMPLETED' : row.conclusion !== 'success' ? 'NOT_SUCCESSFUL'
      : !Number.isFinite(time) ? 'COMPLETION_TIME_UNVERIFIED' : time > now ? 'COMPLETION_TIME_IN_FUTURE'
      : now - time > CHECK_MAX_AGE_MS ? 'SUCCESS_TOO_OLD' : 'PASSED';
    return {...row, state};
  });
  return {status: checks.every(row => row.state === 'PASSED') ? 'ALL_REQUIRED_CHECKS_PASSED' : 'BLOCKED',
    scope: 'REQUIRED_CI_CHECKS_ONLY', revision: source.revision, checks,
    freshness: {maximumAgeSeconds: CHECK_MAX_AGE_MS / 1000, referenceTime: new Date(now).toISOString()}};
}

export async function inspectPolicy(config, {get = githubGet, now = () => Date.now(), candidateHead} = {}) {
  const clock = now(); validateConfig(config, candidateHead, clock);
  const prefix = `repos/${config.repository}`, branchPath = encodeURIComponent(config.branch);
  const [branch, protection, rules, rulesets] = await Promise.all([
    get(`${prefix}/branches/${branchPath}`), get(`${prefix}/branches/${branchPath}/protection`),
    get(`${prefix}/rules/branches/${branchPath}`), get(`${prefix}/rulesets?includes_parents=true&per_page=100`)
  ]);
  require(branch.status === 200 && validSha(branch.body?.commit?.sha), 'branch_read_unavailable');
  let binding, candidate, pull;
  try {
    const [app, pr, commits] = await Promise.all([get('apps/github-actions'),
      get(`${prefix}/pulls/${config.bindingSource.pullRequest}`),
      paged(get, `${prefix}/pulls/${config.bindingSource.pullRequest}/commits`)]);
    require(app.status === 200 && positiveId(app.body?.id) && app.body.slug === 'github-actions'
      && app.body.owner?.login === 'github' && app.body.html_url === 'https://github.com/apps/github-actions', 'github_actions_app_unverified');
    require(pr.status === 200, 'pull_request_read_unavailable');
    pull = pr.body; validatePull(config, pull);
    const commitIds = new Set(commits.map(commit => commit.sha));
    try { binding = await inspectSource(config, config.bindingSource.head, app.body.id, get, commitIds); }
    catch (error) { binding = sourceFailure(error, config.bindingSource.head); }
    if (candidateHead !== undefined) {
      if (pull.head.sha !== candidateHead || pull.state !== 'open' || pull.merged === true) {
        candidate = sourceFailure(new Error('candidate_not_current_open_pull_request_head'), candidateHead);
      } else if (candidateHead === config.bindingSource.head) candidate = binding;
      else {
        try { candidate = await inspectSource(config, candidateHead, app.body.id, get, commitIds); }
        catch (error) { candidate = sourceFailure(error, candidateHead); }
      }
    }
    const after = await get(`${prefix}/pulls/${config.bindingSource.pullRequest}`);
    require(after.status === 200, 'pull_request_readback_unavailable'); validatePull(config, after.body);
    if (candidateHead !== undefined && (after.body.head.sha !== candidateHead || after.body.state !== 'open' || after.body.merged === true))
      candidate = sourceFailure(new Error('candidate_head_changed'), candidateHead);
  } catch (error) {
    binding = sourceFailure(error, config.bindingSource.head);
    if (candidateHead !== undefined) candidate = sourceFailure(error, candidateHead);
  }
  const server = inspectServer(config, branch, protection, rules, binding);
  return {schema: 'otziv-required-check-observation-v2', repository: config.repository, branch: config.branch,
    observedAt: new Date(clock).toISOString(), readOnly: true, remoteMutations: 0,
    identity: {...binding, source: config.bindingSource}, serverPolicy: server,
    candidateAcceptance: candidateHead === undefined ? {status: 'NOT_EVALUATED', scope: 'REQUIRED_CI_CHECKS_ONLY'} : inspectAcceptance(candidate, clock),
    pullRequest: pull ? {number: pull.number, revision: pull.head.sha, state: pull.state, draft: pull.draft, merged: pull.merged} : null,
    rulesHttpStatus: rules.status, rulesetsHttpStatus: rulesets.status, rules: rules.body, rulesets: rulesets.body,
    readyForPolicyReview: binding.status === 'IDENTITY_VERIFIED',
    proposedRequestBody: binding.status === 'IDENTITY_VERIFIED' ? {...config.proposedPolicy,
      required_status_checks: {strict: true, checks: binding.checks.map(({context, app_id}) => ({context, app_id}))}} : null};
}

// Explicit purpose prevents a protection-only result from silently becoming release acceptance.
// Conservative default requires both; this command still never changes server settings.
export function observationExitCode(result, requirement = 'both') {
  require(['enforcement', 'acceptance', 'both'].includes(requirement), 'verification_requirement_invalid');
  const enforced = result.serverPolicy.status === 'ENFORCEMENT_VERIFIED';
  const accepted = result.candidateAcceptance.status === 'ALL_REQUIRED_CHECKS_PASSED';
  return (requirement === 'enforcement' ? enforced : requirement === 'acceptance' ? accepted : enforced && accepted) ? 0 : 2;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  try {
    const [policyPath, outputPath, ...options] = process.argv.slice(2);
    require(policyPath && outputPath && options.length % 2 === 0, 'usage_expected_policy_output_options');
    const args = new Map();
    for (let i = 0; i < options.length; i += 2) {
      require(['--candidate-head', '--require'].includes(options[i]) && !args.has(options[i]), 'usage_option_invalid');
      args.set(options[i], options[i + 1]);
    }
    const requirement = args.get('--require') || 'both';
    require(['both', 'enforcement', 'acceptance'].includes(requirement), 'verification_requirement_invalid');
    const result = await inspectPolicy(JSON.parse(await readFile(policyPath, 'utf8')), {candidateHead: args.get('--candidate-head')});
    await writeFile(outputPath, JSON.stringify(result, null, 2) + '\n', {mode: 0o600});
    console.log(JSON.stringify({schema: result.schema, requirement, identity: result.identity.status,
      serverPolicy: result.serverPolicy.status, candidateAcceptance: result.candidateAcceptance.status,
      readyForPolicyReview: result.readyForPolicyReview, readOnly: true, remoteMutations: 0}));
    process.exitCode = observationExitCode(result, requirement);
  } catch (error) {
    console.error(JSON.stringify({result: 'FAIL', code: /^[a-z_]+$/.test(error.message || '') ? error.message : 'branch_policy_read_failed'}));
    process.exitCode = 1;
  }
}
