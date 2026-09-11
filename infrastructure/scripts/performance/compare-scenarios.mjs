import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const scenarios = ['common-invoice-board', 'payment-admin-board', 'manager-control-details'];

export function compareScenarios(beforeFinance, beforeManager, after) {
  const reports = [beforeFinance, beforeManager, after];
  for (const report of reports) {
    if (report.schema !== 'otziv-scenario-benchmark-v1') throw new Error('Unsupported benchmark schema');
    for (const key of ['fixture', 'mysqlImage', 'jdbcPoolSize', 'harnessSha256']) {
      if (report[key] == null || report[key] !== after[key]) throw new Error(`Incomparable ${key}`);
    }
    if (!/^[a-f0-9]{64}$/.test(report.harnessSha256)) throw new Error('Missing harness fingerprint');
    if (!Array.isArray(report.explain) || !report.explain.length) throw new Error('Missing actual query plans');
    if (!Array.isArray(report.results) || report.results.length !== 9) throw new Error('Incomplete scenario matrix');
    const keys = new Set();
    for (const row of report.results) {
      const key = `${row.scenario}:${row.concurrency}`;
      if (!scenarios.includes(row.scenario) || ![1, 4, 8].includes(row.concurrency) || keys.has(key))
        throw new Error(`Invalid scenario row ${key}`);
      keys.add(key);
      if (row.samples < 24 * row.concurrency) throw new Error(`Pilot is not a full measurement: ${key}`);
      if (row.rollbacks !== 0 || !Number.isInteger(row.queriesMax) || row.queriesMin <= 0)
        throw new Error(`Invalid SQL outcome: ${key}`);
      for (const field of ['p50Ms', 'p95Ms', 'completedPerSecond']) {
        if (!Number.isFinite(row[field]) || row[field] <= 0) throw new Error(`Invalid ${field}: ${key}`);
      }
    }
  }
  return after.results.map(current => {
    const baseline = current.scenario === 'manager-control-details' ? beforeManager : beforeFinance;
    const previous = baseline.results.find(row => row.scenario === current.scenario && row.concurrency === current.concurrency);
    return {
      scenario: current.scenario, concurrency: current.concurrency,
      beforeSource: baseline.sourceLabel, afterSource: after.sourceLabel,
      before: previous, after: current,
      p95ChangePercent: 100 * (current.p95Ms / previous.p95Ms - 1),
      throughputChangePercent: 100 * (current.completedPerSecond / previous.completedPerSecond - 1),
      queryChange: current.queriesMax - previous.queriesMax,
    };
  });
}

function main(args) {
  if (args.length !== 4) throw new Error('Usage: node compare-scenarios.mjs before-finance.json before-manager.json after.json output-directory');
  const [finance, manager, after] = args.slice(0, 3).map(file => JSON.parse(fs.readFileSync(file, 'utf8')));
  const comparison = compareScenarios(finance, manager, after);
  const destination = path.resolve(args[3]);
  fs.mkdirSync(destination, { recursive: true });
  fs.writeFileSync(path.join(destination, 'comparison.json'), `${JSON.stringify({ schema: 'otziv-scenario-comparison-v1', comparison }, null, 2)}\n`);
  const text = [
    'Measurements on disposable synthetic MySQL data. Timing describes this host and sample, not production capacity.',
    '', '| Scenario | Concurrent | SQL before → after | p50 ms before → after | p95 ms before → after | Throughput before → after |',
    '| --- | ---: | ---: | ---: | ---: | ---: |',
    ...comparison.map(row => `| ${row.scenario} | ${row.concurrency} | ${row.before.queriesMax} → ${row.after.queriesMax} | ${row.before.p50Ms.toFixed(1)} → ${row.after.p50Ms.toFixed(1)} | ${row.before.p95Ms.toFixed(1)} → ${row.after.p95Ms.toFixed(1)} | ${row.before.completedPerSecond.toFixed(2)} → ${row.after.completedPerSecond.toFixed(2)} |`),
    '', 'Before sources differ by scenario because the retained snapshots precede different completed decomposition steps.',
  ];
  fs.writeFileSync(path.join(destination, 'comparison.md'), `${text.join('\n')}\n`);
  console.log(`Compared ${comparison.length} complete rows; output: ${destination}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main(process.argv.slice(2));
