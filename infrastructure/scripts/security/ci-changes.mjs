import {execFileSync} from 'node:child_process';
import {appendFileSync, readFileSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

export const scopes = ['backend', 'issuer', 'frontend', 'mobile', 'browser', 'android',
  'parity', 'whatsapp', 'worker', 'observer', 'publisher', 'upstream', 'monitoring'];

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
    else if (path.startsWith('infrastructure/docker-observer/')) add('observer', 'monitoring');
    else if (path.startsWith('infrastructure/monitoring/')) add('publisher', 'monitoring');
    else if (path.startsWith('infrastructure/browser-smoke/')) add('browser');
    else all('Shared, infrastructure, policy or unknown input changed: ' + path);
  }
  return {schema: 'otziv-ci-selection-v1', event, paths, reasons,
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
