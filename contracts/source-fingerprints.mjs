import assert from 'node:assert/strict';

/** Provenance entries keep the source path separate from the SHA-256 value. */
export function readSourceFingerprints(contract) {
  const entries = contract['x-source-sha256'];
  assert.ok(Array.isArray(entries) && entries.length > 0, 'Expected source fingerprint entries');
  const paths = new Set();
  for (const entry of entries) {
    assert.equal(typeof entry.path, 'string');
    assert.ok(entry.path && !entry.path.startsWith('/') && !entry.path.includes('\\')
      && !entry.path.split('/').includes('..'), 'Expected a repository-relative source path');
    assert.match(entry.sha256, /^[a-f0-9]{64}$/);
    assert.ok(!paths.has(entry.path), 'Duplicate source fingerprint');
    paths.add(entry.path);
  }
  assert.deepEqual([...paths], [...paths].sort(), 'Source fingerprints must have deterministic path order');
  return entries;
}
