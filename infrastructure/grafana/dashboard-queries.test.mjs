import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, chmodSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const dashboard = JSON.parse(readFileSync(new URL('./dashboards/otziv-backend-performance.json', import.meta.url)));
const panel = id => dashboard.panels.find(p => p.id === id);
const expr = (id, ref = 'A', endpoint = '.*', runtime = '.*') => panel(id).targets.find(t => t.refId === ref).expr
  .replaceAll('$http_endpoint', endpoint).replaceAll('$http_runtime', runtime);

function histogram(endpoint, runtime, values, upper = '0.1', status = '2xx') {
  const labels = `endpoint="${endpoint}",runtime="${runtime}",status="${status}"`;
  return [
    { series: `otziv_http_duration_seconds_count{${labels}}`, values },
    { series: `otziv_http_duration_seconds_bucket{${labels},le="${upper}"}`, values },
    { series: `otziv_http_duration_seconds_bucket{${labels},le="+Inf"}`, values },
  ];
}
const sample = (endpoint, runtime, value) => ({ labels: `{endpoint="${endpoint}",runtime="${runtime}"}`, value });
const expectation = (id, values, ref = 'A', endpoint, runtime, at = '5m') => ({
  expr: expr(id, ref, endpoint, runtime), eval_time: at,
  exp_samples: [4, 5].includes(id) ? values.map(s => ({ ...s, labels: '{}' })) : values,
});

test('top cards expose estimates and the matching sample count without stale or green SLO fallback', () => {
  for (const id of [4, 5]) {
    const p = panel(id);
    assert.match(p.title, /≈/);
    assert.deepEqual(p.options.reduceOptions.calcs, ['last']);
    assert.equal(p.fieldConfig.defaults.color.mode, 'fixed');
    assert.equal(p.fieldConfig.defaults.color.fixedColor, 'blue');
    assert.ok(p.targets.every(t => t.instant && !t.range));
    assert.match(p.targets.find(t => t.refId === 'B').legendFormat, /^N ≈/);
    assert.deepEqual(p.fieldConfig.overrides[0].matcher, { id: 'byFrameRefID', options: 'B' });
    assert.equal(p.fieldConfig.overrides[0].properties.find(p => p.id === 'unit').value, 'short');
  }
  assert.match(panel(200).options.content, /Малое N/);
  assert.equal(new Set(dashboard.panels.map(p => p.id)).size, dashboard.panels.length);
});

test('actual dashboard PromQL keeps sparse observations, matching N, idle gaps and runtime isolation', () => {
  const sparse = histogram('manager.orders', '100', '0 0 0 0 1 1');
  const tests = [
    { name: 'one observed request remains visible', interval: '1m', input_series: sparse,
      promql_expr_test: [
        expectation(4, [sample('manager.orders', '100', 0.095)]),
        expectation(5, [sample('manager.orders', '100', 0.099)]),
        expectation(4, [sample('manager.orders', '100', 1)], 'B'),
        expectation(5, [sample('manager.orders', '100', 1)], 'B'),
        expectation(101, [sample('manager.orders', '100', 0.095)]),
        expectation(102, [sample('manager.orders', '100', 0.099)]),
        expectation(4, [], 'A', 'cabinet.team'),
        expectation(4, [], 'A', '.*', '200'),
      ] },
    { name: 'old completed requests do not appear as fresh latency', interval: '1m',
      input_series: histogram('manager.orders', '100', '100+0x5'),
      promql_expr_test: [4, 5, 101, 102].map(id => expectation(id, [])).concat([expectation(4, [], 'B'), expectation(5, [], 'B')]) },
    { name: 'missing telemetry is not zero latency or zero success', interval: '1m', input_series: [],
      promql_expr_test: [expectation(4, []), expectation(5, []), expectation(4, [], 'B')] },
    { name: 'first scrape without baseline cannot fabricate a window observation', interval: '1m',
      input_series: histogram('manager.orders', '100', '_ _ _ _ _ 1'),
      promql_expr_test: [expectation(4, []), expectation(5, [])] },
    { name: 'N belongs to the slowest runtime and endpoint, never total traffic', interval: '1m',
      input_series: [
        ...histogram('manager.orders', '100', '0+100x5'),
        ...histogram('manager.orders', '200', '0 0 0 0 1 1', '1'),
        ...histogram('manager.companies', '200', '0+200x5', '0.01'),
        ...histogram('cabinet.team', '200', '0+100x5', '10', '5xx'),
      ], promql_expr_test: [
        expectation(4, [sample('manager.orders', '200', 0.95)]),
        expectation(5, [sample('manager.orders', '200', 0.99)]),
        expectation(4, [sample('manager.orders', '200', 1)], 'B'),
        expectation(5, [sample('manager.orders', '200', 1)], 'B'),
        expectation(4, [sample('manager.orders', '100', 0.095)], 'A', 'manager.orders', '100'),
        expectation(4, [sample('manager.orders', '100', 500)], 'B', 'manager.orders', '100'),
      ] },
    { name: 'large samples continue to work', interval: '1m',
      input_series: histogram('worker.new', '300', '0+300x5'),
      promql_expr_test: [expectation(4, [sample('worker.new', '300', 0.095)]),
        expectation(5, [sample('worker.new', '300', 0.099)]),
        expectation(5, [sample('worker.new', '300', 1500)], 'B')] },
    { name: 'equal worst estimates show the smaller N without nondeterministic topk pairing', interval: '1m',
      input_series: [...histogram('manager.orders', '100', '0+100x5'), ...sparse.map(s => ({ ...s, series: s.series.replaceAll('runtime="100"', 'runtime="200"') }))],
      promql_expr_test: [expectation(4, [sample('', '', 0.095)]), expectation(5, [sample('', '', 0.099)]),
        expectation(4, [sample('', '', 1)], 'B'), expectation(5, [sample('', '', 1)], 'B')] },
  ];
  const dir = mkdtempSync(join(tmpdir(), 'otziv-dashboard-promql-'));
  try {
    // JSON is a YAML subset. promtool executes the expressions from the actual dashboard.
    writeFileSync(join(dir, 'tests.yml'), JSON.stringify({ rule_files: [], evaluation_interval: '1m', fuzzy_compare: true, tests }));
    // Only synthetic public test data; allow the unprivileged image UID to read
    // this owned fixture on Linux, where mkdtemp defaults to owner-only mode.
    chmodSync(dir, 0o755);
    chmodSync(join(dir, 'tests.yml'), 0o644);
    const image = 'ghcr.io/claidd/otziv-security@sha256:508c918889067ea070d3da34913a81bd6df8092fb18977ca339e2367e3d77692';
    const run = spawnSync('docker', ['run', '--rm', '--network', 'none', '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=128m', '--cap-drop', 'ALL',
      '--security-opt', 'no-new-privileges:true', '--mount', `type=bind,source=${dir},target=/fixture,readonly`,
      '--entrypoint', '/bin/promtool', image, 'test', 'rules', '/fixture/tests.yml'], { encoding: 'utf8', timeout: 180_000 });
    assert.equal(run.status, 0, `${run.error ?? ''}\n${run.stdout}\n${run.stderr}`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
