import fs from 'node:fs';
import path from 'node:path';

const argumentIndex = process.argv.findIndex(value =>
  value.replaceAll('\\', '/').endsWith('manual-task-lifecycle-fixes.patch')
);
if (argumentIndex < 0) throw new Error('Lifecycle patch argument is required');
const sourcePath = path.resolve(process.cwd(), process.argv[argumentIndex]);
const generatedPath = path.resolve(process.cwd(), 'codex-tmp/manual-task-lifecycle-fixes.generated.patch');
const normalized = fs.readFileSync(sourcePath, 'utf8').replaceAll('\r\n', '\n').trimEnd();
fs.writeFileSync(generatedPath, normalized, 'utf8');
process.argv[argumentIndex] = path.relative(process.cwd(), generatedPath);
await import('./apply-custom-patches-v3.mjs');

