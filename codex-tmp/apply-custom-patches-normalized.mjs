import fs from 'node:fs';
import path from 'node:path';

const patchArguments = process.argv
  .map((value, index) => ({ value, index }))
  .filter(({ value }) => value.toLowerCase().endsWith('.patch'));
if (patchArguments.length === 0) throw new Error('At least one patch argument is required');
for (const { value, index } of patchArguments) {
  const sourcePath = path.resolve(process.cwd(), value);
  const generatedPath = sourcePath.replace(/\.patch$/i, '.normalized.patch');
  const normalized = fs.readFileSync(sourcePath, 'utf8').replaceAll('\r\n', '\n').trimEnd();
  fs.writeFileSync(generatedPath, normalized, 'utf8');
  process.argv[index] = path.relative(process.cwd(), generatedPath);
}
await import('./apply-custom-patches-v3.mjs');

