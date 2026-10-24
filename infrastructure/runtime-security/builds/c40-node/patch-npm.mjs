import { readFileSync, readdirSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { join, dirname } from 'node:path';
import { createRequire } from 'node:module';

const root = '/usr/local/lib/node_modules/npm';
const require = createRequire(`${root}/package.json`);
const semver = require('semver');
const patches = JSON.parse(readFileSync('/patches/npm-patches.json'));
if (JSON.parse(readFileSync(`${root}/package.json`)).version !== '11.19.1') throw Error('unexpected_npm');
const resolvedPackage = (from, name) => {
  let path = dirname(createRequire(join(from, 'package.json')).resolve(name));
  while (path !== dirname(path)) {
    try {
      const value = JSON.parse(readFileSync(join(path, 'package.json')));
      if (value.name === name) return value;
    } catch {}
    path = dirname(path);
  }
  throw Error(`npm_dependency_resolution_${name}`);
};
for (const patch of patches) {
  if (!['brace-expansion', 'undici', 'http-cache-semantics'].includes(patch.name)) throw Error('unexpected_patch');
  const target = join(root, 'node_modules', patch.name);
  const before = JSON.parse(readFileSync(join(target, 'package.json')));
  if (before.name !== patch.name || before.version !== patch.before) throw Error(`unexpected_original_${patch.name}`);
  const file = `/patches/${patch.name}-${patch.version}.tgz`;
  const bytes = readFileSync(file);
  if (createHash('sha256').update(bytes).digest('hex') !== patch.sha256 ||
      `sha512-${createHash('sha512').update(bytes).digest('base64')}` !== patch.integrity) throw Error('npm_patch_integrity');
  // Fixed package paths inside this disposable image, never host directories.
  for (const entry of readdirSync(target)) rmSync(join(target, entry), { recursive: true, force: true });
  execFileSync('tar', ['-xzf', file, '--strip-components=1', '-C', target]);
  const after = JSON.parse(readFileSync(join(target, 'package.json')));
  if (after.name !== patch.name || after.version !== patch.version) throw Error('npm_patch_identity');
  if (after.engines?.node && !semver.satisfies(process.versions.node, after.engines.node)) throw Error('npm_patch_node_engine');
  for (const [name, range] of Object.entries(after.dependencies || {})) {
    if (!semver.satisfies(resolvedPackage(target, name).version, range)) throw Error(`unsatisfied_dependency_${name}`);
  }
  console.log(JSON.stringify({ package: patch.name, before: before.version, after: after.version }));
}
// Verify every installed consumer accepts the exact patched version it resolves.
let consumers = 0;
const inspect = path => {
  const value = JSON.parse(readFileSync(join(path, 'package.json')));
  for (const patch of patches) {
    const range = value.dependencies?.[patch.name] || value.optionalDependencies?.[patch.name];
    if (!range) continue;
    const installed = resolvedPackage(path, patch.name);
    if (!semver.satisfies(installed.version, range)) throw Error(`incompatible_consumer_${value.name}_${patch.name}`);
    consumers++;
  }
};
inspect(root);
const walk = directory => {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const path = join(directory, entry.name);
    if (entry.name.startsWith('@')) { walk(path); continue; }
    inspect(path);
    try { readdirSync(join(path, 'node_modules')); } catch { continue; }
    walk(join(path, 'node_modules'));
  }
};
walk(join(root, 'node_modules'));
if (consumers < 3) throw Error('npm_patch_consumer_proof_missing');
console.log(JSON.stringify({ compatibleConsumers: consumers }));
