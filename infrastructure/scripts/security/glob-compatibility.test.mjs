import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { once } from 'node:events';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, test } from 'node:test';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const projects = ['frontend', 'mobile', 'whatsapp'];
const selected = projects.includes(path.basename(process.cwd())) ? [path.basename(process.cwd())] : projects;
const portableNames = files => files.map(name => name.split(path.sep).join('/')).sort();
const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'otziv-glob-'));
for (const folder of ['alpha', 'beta', 'ignored']) fs.mkdirSync(path.join(fixture, folder));
for (const name of ['alpha/a.txt', 'beta/b.txt', 'ignored/c.txt', 'beta/image.png']) {
  fs.writeFileSync(path.join(fixture, name), name);
}
fs.writeFileSync(path.join(fixture, '.npmignore'), 'ignored/**\n');
after(() => {
  const target = fs.realpathSync(fixture);
  assert.equal(path.dirname(target), fs.realpathSync(os.tmpdir()));
  assert.ok(path.basename(target).startsWith('otziv-glob-'));
  fs.rmSync(target, { recursive: true, force: true });
});

for (const project of selected) {
  const require = createRequire(path.join(root, project, 'package.json'));
  const glob = require('glob');
  const { Minimatch, minimatch } = require('minimatch');
  const options = { cwd: fixture, nodir: true, ignore: ['ignored/**'] };

  test(`${project}: synchronous packaging and async glob keep the same file selection`, async () => {
    assert.deepEqual(portableNames(glob.sync('**/*.txt', options)), ['alpha/a.txt', 'beta/b.txt']);
    assert.deepEqual(portableNames(await glob.glob('**/*.txt', options)), ['alpha/a.txt', 'beta/b.txt']);
  });

  test(`${project}: existing CommonJS Minimatch consumers retain braces, match and exclusion behavior`, () => {
    const matcher = new Minimatch('**/*.{txt,png}', { dot: true, optimizationLevel: 2 });
    assert.equal(matcher.match('alpha/a.txt'), true);
    assert.equal(matcher.match('beta/image.png'), true);
    assert.equal(matcher.match('beta/file.csv'), false);
    assert.equal(minimatch('alpha/a.txt', '**/*.txt'), true);
  });

  test(`${project}: an aborted walk rejects without reading directories in the background`, async () => {
    let reads = 0;
    const reason = new Error('fixture traversal cancelled');
    const monitoredFs = { ...fs, readdir(...args) { reads++; return fs.readdir(...args); },
      readdirSync(...args) { reads++; return fs.readdirSync(...args); },
      promises: { ...fs.promises, async readdir(...args) { reads++; return fs.promises.readdir(...args); } } };
    await assert.rejects(glob.glob('**/*', { cwd: fixture, fs: monitoredFs, signal: AbortSignal.abort(reason) }), /fixture traversal cancelled/);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(reads, 0);
  });

  if (project === 'frontend') {
    test(`${project}: npm packaging ignore-walk still honors exclusions`, async () => {
      const walk = require('ignore-walk');
      const files = await walk({ path: fixture, ignoreFiles: ['.npmignore'] });
      assert.ok(files.includes('alpha/a.txt'));
      assert.ok(files.includes('beta/b.txt'));
      assert.ok(!files.includes('ignored/c.txt'));
    });
  } else if (project === 'whatsapp') {
    test('whatsapp: auth archive glob creates a valid zip with exactly the selected files', { timeout: 15000 }, async () => {
      const archive = require('archiver')('zip', { zlib: { level: 0 } });
      const chunks = [];
      archive.on('data', chunk => chunks.push(chunk));
      const ended = once(archive, 'end');
      archive.glob('**/*.txt', options);
      await Promise.all([ended, archive.finalize()]);
      const zip = Buffer.concat(chunks);
      const entries = [];
      for (let offset = 0; offset + 46 <= zip.length; offset++) {
        if (zip.readUInt32LE(offset) !== 0x02014b50) continue;
        const length = zip.readUInt16LE(offset + 28);
        entries.push(zip.subarray(offset + 46, offset + 46 + length).toString());
        offset += 45 + length + zip.readUInt16LE(offset + 30) + zip.readUInt16LE(offset + 32);
      }
      assert.deepEqual(entries.sort(), ['alpha/a.txt', 'beta/b.txt']);
    });
  }

  if (project === 'frontend' || project === 'mobile') {
    test(`${project}: all picomatch consumers use the reviewed security release`, () => {
      const lock = require('./package-lock.json');
      const copies = Object.entries(lock.packages).filter(([name]) => name.endsWith('/picomatch'));
      assert.ok(copies.length > 0);
      for (const [name, metadata] of copies) {
        assert.equal(metadata.name, '@ryotsu/picomatch', name);
        assert.equal(metadata.version, '4.0.7-a', name);
        assert.equal(metadata.integrity, 'sha512-JroBFEmaejC7szGSfqOhF1QZHLKvX2wTA1+fWyfkpy3BjzdQKNPLKa2FtgeKVUDy5/UPUNkXEeCHJ5ZRBMLTVA==', name);
        assert.equal(require(`./${name}/package.json`).name, metadata.name);
      }
      for (const consumer of ['tinyglobby', '@angular/build', 'vite', 'vitest']) {
        const consumerRequire = createRequire(require.resolve(`${consumer}/package.json`));
        assert.equal(consumerRequire('picomatch/package.json').name, '@ryotsu/picomatch', consumer);
      }
    });

    test(`${project}: repeated wildcard extglobs finish without exponential backtracking`, { timeout: 10000 }, () => {
      // A subprocess deadline also catches a regression that blocks Node's event loop.
      const script = `
        const assert = require('node:assert/strict');
        const pm = require(${JSON.stringify(require.resolve('picomatch'))});
        const cases = [
          ['+(*)', 'a'.repeat(80) + '/'], ['*(*)', 'a'.repeat(80) + '/'],
          ['+(*a)', 'a'.repeat(80) + '!'], ['*(*a)', 'a'.repeat(80) + '!'],
          ['+(a*)', 'a'.repeat(80) + '/'], ['*(a*)', 'a'.repeat(80) + '/'],
          ['+(*)b', 'a'.repeat(80) + '!'], ['*(*)b', 'a'.repeat(80) + '!'],
          ['+(a*b)', 'ab'.repeat(40) + '!'], ['+(**a)', 'a'.repeat(80) + '!'],
          ['+(**)', 'a'.repeat(80) + '/.'], ['+(@(*a))', 'a'.repeat(80) + '!'],
          ['+(?(*a))', 'a'.repeat(80) + '!'], ['+(a|aa)', 'a'.repeat(80) + '!']
        ];
        for (const options of [{}, { windows: true }, { capture: true }]) {
          for (const [pattern, input] of cases) assert.equal(pm(pattern, options)(input), false, pattern);
        }
      `;
      const result = spawnSync(process.execPath, ['-e', script], {
        encoding: 'utf8', timeout: 5000, windowsHide: true
      });
      assert.ifError(result.error);
      assert.equal(result.status, 0, result.stderr);
    });

    test(`${project}: Angular and test discovery retain glob matching and exclusions`, async () => {
      const pm = require('picomatch');
      const matches = pm(['**/*.{ts,html}', '!**/*.spec.ts'], { ignore: ['**/*.spec.ts'] });
      assert.equal(matches('src/app/card.component.ts'), true);
      assert.equal(matches('src/app/card.component.html'), true);
      assert.equal(matches('src/app/card.component.spec.ts'), false);
      assert.equal(pm('**/*.ts')('.cache/generated.ts'), false);
      assert.equal(pm('**/*.ts', { dot: true })('.cache/generated.ts'), true);
      assert.equal(pm('**/*.ts', { windows: true })('src\\app\\card.ts'), true);
      assert.equal(pm.scan('src/**/*.spec.ts').base, 'src');
      for (const [pattern, input, expected] of [
        ['+(abc)', 'abcabc', true], ['*(foo|bar)', 'foobar', true],
        ['+(*a)', 'ababa', true], ['+(a*b)', 'axbayb', true], ['+(a*b)', 'axbay', false],
        ['+(a\\*b)', 'a*ba*b', true], ['+("*a")', '*a*a', true],
        ['+(?(*a))', '+(?(*a))', true], ['+(?(*a))', 'aaaa', false]
      ]) assert.equal(pm(pattern)(input), expected, pattern);
      const { glob: discover } = require('tinyglobby');
      assert.deepEqual(portableNames(await discover(['**/*.txt'], {
        cwd: fixture, ignore: ['ignored/**']
      })), ['alpha/a.txt', 'beta/b.txt']);
    });
  }
}
