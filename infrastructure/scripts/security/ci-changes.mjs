import {execFileSync} from 'node:child_process';
import {appendFileSync, readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

export const scopes = ['backend', 'issuer', 'frontend', 'mobile', 'browser', 'android',
  'parity', 'whatsapp', 'worker', 'observer', 'publisher', 'upstream', 'monitoring', 'infrastructure',
  'deployment', 'monitoring_config', ...['prometheus', 'loki', 'alloy', 'tempo', 'grafana'].flatMap(x => [`monitoring_${x}`, `monitoring_${x}_build`])];

// Only reviewed, exact helper names are narrow. New helpers still select all checks.
const deployHelpers = new Set([
  'DeploySnapshot.ps1', 'deploy-prod.ps1', 'deploy-prod-fast.ps1', 'deploy-prod-ssh-images.ps1',
  'release_ci.py', 'release_preflight.py', 'release_registry.py', 'remote_release_transport.py',
  'ci_release.py', 'ci_artifacts.py', 'deployment_capacity.py', 'image_layer_capacity.py',
  'selective_rollout.py', 'database_image_guard.py', 'whatsapp_deploy_state.py',
  'disk_maintenance.py', 'docker-disk-maintenance.sh', 'install-disk-maintenance.sh',
  'create-pre-deploy-db-backup.sh', 'production_images.py', 'release_session.py', 'release_metrics.py',
  'ReleaseMetrics.ps1',
]);
const candidates = ['prometheus', 'loki', 'alloy', 'tempo', 'grafana'];

// An unknown input or an unverifiable base expands coverage; it never skips it.
export function selectChecks(paths, event, usableBase = true) {
  const selected = new Set();
  const reasons = [];
  const add = (...values) => values.forEach(value => selected.add(value));
  const all = reason => { add(...scopes); reasons.push(reason); };
  if (!usableBase || !['push', 'pull_request'].includes(event)) {
    all('Full verification: manual run or unavailable comparison base.');
  } else for (const path of paths) {
    if (typeof path !== 'string' || !path || path.includes('\\') || path.startsWith('/')
        || path.split('/').includes('..')) throw new Error('Invalid changed path');
    if (/\.md$/.test(path) || /^(docs\/|diagnostics\/).*\.(png|jpg|jpeg|svg|txt)$/.test(path)) continue;
    if (path.startsWith('backend/external-review-worker/')) add('worker', 'backend');
    else if (path.startsWith('backend/')) add('backend', 'issuer', 'browser', 'parity');
    else if (path.startsWith('frontend/')) add('frontend', 'browser', 'parity');
    else if (path.startsWith('mobile/')) add('mobile', 'android', 'browser', 'parity');
    else if (/^(shared\/|contracts\/)/.test(path)) add('backend', 'issuer', 'frontend', 'mobile', 'android', 'browser', 'parity', 'whatsapp', 'worker');
    else if (path.startsWith('whatsapp/') || path === 'Dockerfile.whatsapp') add('whatsapp', 'backend', 'issuer');
    else if (path === 'deploy.ps1') add('deployment');
    else if (path.startsWith('infrastructure/scripts/prod/') &&
      deployHelpers.has(path.split('/').at(-1).replace(/^test_/, ''))) add('deployment');
    else if (/^infrastructure\/scripts\/prod\/(?:test_)?backend_test_shards\.py$/.test(path)
      || path === 'infrastructure/scripts/prod/backend-test-durations.json') add('backend');
    else if (/^infrastructure\/scripts\/prod\/(?:test_)?ci_image_(?:reuse|bundle)\.py$/.test(path)) {
      add('backend', 'issuer', 'frontend', 'whatsapp', 'worker', 'observer', 'publisher', 'deployment');
    }
    else if (/^infrastructure\/runtime-security\/builds\/(Prometheus|Loki|Alloy|Tempo|Grafana)\.Dockerfile$/.test(path)) {
      const component = path.split('/').at(-1).split('.')[0].toLowerCase();
      add('monitoring', `monitoring_${component}`, `monitoring_${component}_build`, 'upstream');
    }
    else if (/^infrastructure\/runtime-security\/builds\/tempo-queue-shutdown(?:\.patch|_test\.go)$/.test(path))
      add('monitoring', 'monitoring_tempo', 'monitoring_tempo_build', 'upstream');
    else if (path.startsWith('infrastructure/docker-observer/')) add('observer', 'monitoring_config');
    else if (path.startsWith('infrastructure/monitoring/')) add('publisher', 'monitoring_config');
    else if (/^infrastructure\/(prometheus|loki|alloy|tempo|grafana)\//.test(path)) add('monitoring_config', 'upstream');
    else if (path.startsWith('infrastructure/browser-smoke/')) add('browser');
    else all('Shared, infrastructure, policy or unknown input changed: ' + path);
  }
  if (selected.has('monitoring_config')) add('monitoring', ...candidates.map(x => `monitoring_${x}`));
  return {schema: 'otziv-ci-selection-v2', event, paths, reasons,
    checks: Object.fromEntries(scopes.map(scope => [scope, selected.has(scope)]))};
}

export function changedPaths(event, git = args => execFileSync('git', args, {encoding: 'utf8'})) {
  const base = event.pull_request?.base?.sha || event.before || '';
  const head = event.pull_request?.head?.sha || event.after || process.env.GITHUB_SHA || 'HEAD';
  if (!/^[a-f0-9]{40}$/.test(base) || /^0+$/.test(base) || !/^[a-f0-9]{40}$/.test(head)) return null;
  try {
    git(['cat-file', '-e', base + '^{commit}']);
    git(['cat-file', '-e', head + '^{commit}']);
    // PRs compare from the common ancestor; pushes include every changed commit.
    const from = event.pull_request ? git(['merge-base', base, head]).trim() : base;
    return git(['diff', '--name-only', '-z', '--no-renames', from, head]).split('\0').filter(Boolean);
  } catch { return null; }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const paths = changedPaths(event);
  const selection = selectChecks(paths || [], process.env.GITHUB_EVENT_NAME, paths !== null);
  selection.revision = process.env.GITHUB_SHA;
  if (!/^[a-f0-9]{40}$/.test(selection.revision || '')) throw new Error('Missing CI revision');
  for (const [scope, enabled] of Object.entries(selection.checks)) {
    appendFileSync(process.env.GITHUB_OUTPUT, `${scope}=${enabled}\n`);
  }
  const output = process.argv[2];
  if (output) writeFileSync(output, JSON.stringify(selection, null, 2) + '\n');
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, '### Change-based verification\n\n'
    + scopes.map(scope => `- ${scope}: ${selection.checks[scope] ? 'run' : 'unchanged'}`).join('\n') + '\n');
}
