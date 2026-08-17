import fs from 'node:fs';

const source = fs.readFileSync(new URL('./apply-custom-patches-v3.mjs', import.meta.url), 'utf8');
const oldCode = "  if (expected.length === 0) throw new Error(`Empty context: ${label}`);";
const newCode = "  if (expected.length === 0) {\n    if (cursor <= 0) throw new Error(`Unsafe empty context: ${label}`);\n    return cursor;\n  }";
if (!source.includes(oldCode)) throw new Error('Expected v3 source fragment was not found');
const adjusted = source.replace(oldCode, newCode);
await import(`data:text/javascript;base64,${Buffer.from(adjusted).toString('base64')}`);
