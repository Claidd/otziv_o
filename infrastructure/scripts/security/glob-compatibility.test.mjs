import assert from 'node:assert/strict';
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
}
