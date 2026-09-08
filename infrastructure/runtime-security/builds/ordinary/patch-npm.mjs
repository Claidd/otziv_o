import { readFileSync, readdirSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { createRequire } from 'node:module';
const root = '/usr/local/lib/node_modules/npm';
const require = createRequire(`${root}/package.json`);
const semver = require('semver');
const patches = JSON.parse(readFileSync('/patches/npm-patches.json'));
const expected = { 'brace-expansion': '5.0.7', 'ip-address': '10.2.0', tar: '7.5.19' };
for (const patch of patches) {
  const target = join(root, 'node_modules', patch.name);
  const before = JSON.parse(readFileSync(join(target, 'package.json')));
  if (before.version !== expected[patch.name]) throw Error(`unexpected_original_${patch.name}`);
  const file = `/patches/${patch.name}-${patch.version}.tgz`;
  if (`sha512-${createHash('sha512').update(readFileSync(file)).digest('base64')}` !== patch.integrity) throw Error('npm_patch_integrity');
  // Target is a fixed package directory in this disposable build image; no host paths.
  for (const entry of readdirSync(target)) rmSync(join(target, entry), { recursive: true, force: true });
  execFileSync('tar', ['-xzf', file, '--strip-components=1', '-C', target]);
  const after = JSON.parse(readFileSync(join(target, 'package.json')));
  if (after.name !== patch.name || after.version !== patch.version) throw Error('npm_patch_identity');
  for (const [name, range] of Object.entries(after.dependencies || {})) {
    const from = createRequire(join(target, 'package.json'));
    let path = from.resolve(name);
    while (!path.endsWith('/package.json')) {
      path = path.substring(0, path.lastIndexOf('/'));
      try { const p = JSON.parse(readFileSync(join(path, 'package.json'))); if (p.name === name) { path = join(path, 'package.json'); break; } } catch {}
      if (!path) throw Error('npm_dependency_resolution');
    }
    if (!semver.satisfies(JSON.parse(readFileSync(path)).version, range)) throw Error(`unsatisfied_dependency_${name}`);
  }
  console.log(JSON.stringify({ package: patch.name, before: before.version, after: after.version }));
}
