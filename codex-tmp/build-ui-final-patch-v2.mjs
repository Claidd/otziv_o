import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

await import('./build-ui-final-patch.mjs');

const root = process.cwd();
const virtualRoot = path.join(root, 'codex-tmp', 'virtual-ui-final');
const baseRoot = path.join(virtualRoot, 'base');
const finalRoot = path.join(virtualRoot, 'final');
const supplement = fs.readFileSync(
  path.join(root, 'codex-tmp', 'manual-task-card-ledger-existing.patch'), 'utf8'
).replaceAll('\r\n', '\n');

for (const section of parseSections(supplement)) {
  const finalPath = path.join(finalRoot, section.file);
  const working = fs.readFileSync(finalPath, 'utf8').replaceAll('\r\n', '\n').split('\n');
  let cursor = 0;
  for (const hunk of section.hunks) {
    const oldLines = hunk.lines.filter(line => line[0] === ' ' || line[0] === '-').map(line => line.slice(1));
    const newLines = hunk.lines.filter(line => line[0] === ' ' || line[0] === '+').map(line => line.slice(1));
    const match = findSequence(working, oldLines, cursor);
    if (match < 0) throw new Error(`Cannot locate ledger hunk in ${section.file}`);
    working.splice(match, oldLines.length, ...newLines);
    cursor = match + newLines.length;
  }
  fs.writeFileSync(finalPath, working.join('\n'), 'utf8');
}

const output = [];
for (const file of listFiles(baseRoot).sort()) {
  const relativeBase = path.relative(root, path.join(baseRoot, file)).replaceAll('\\', '/');
  const relativeFinal = path.relative(root, path.join(finalRoot, file)).replaceAll('\\', '/');
  const result = spawnSync(
    'git',
    ['diff', '--no-index', '--no-prefix', '--no-ext-diff', '--text', '--', relativeBase, relativeFinal],
    { cwd: root, encoding: 'utf8' }
  );
  if (result.status !== 0 && result.status !== 1) throw new Error(result.stderr);
  if (result.status === 0) continue;
  const lines = result.stdout.replaceAll('\r\n', '\n').split('\n');
  lines[0] = `diff --git a/${file} b/${file}`;
  lines[lines.findIndex(line => line.startsWith('--- '))] = `--- a/${file}`;
  lines[lines.findIndex(line => line.startsWith('+++ '))] = `+++ b/${file}`;
  output.push(...lines.filter((line, index) => index < lines.length - 1 || line !== ''));
}

const outputPath = path.join(root, 'codex-tmp', 'manual-task-ui-existing-final.patch');
fs.writeFileSync(outputPath, `${output.join('\n')}\n`, 'utf8');
console.log(`Wrote final consolidated patch: ${outputPath}`);

function parseSections(source) {
  const sections = [];
  let section = null;
  let hunk = null;
  for (const line of source.split('\n')) {
    const diff = /^diff --git a\/(.+) b\/(.+)$/.exec(line);
    if (diff) {
      section = { file: diff[1], hunks: [] };
      sections.push(section);
      hunk = null;
      continue;
    }
    if (!section || line.startsWith('--- ') || line.startsWith('+++ ')) continue;
    if (line.startsWith('@@')) {
      hunk = { lines: [] };
      section.hunks.push(hunk);
      continue;
    }
    if (hunk && (line[0] === ' ' || line[0] === '+' || line[0] === '-')) hunk.lines.push(line);
  }
  return sections;
}

function findSequence(haystack, needle, start) {
  for (let index = Math.max(0, start); index <= haystack.length - needle.length; index += 1) {
    if (needle.every((line, offset) => haystack[index + offset] === line)) return index;
  }
  return -1;
}

function listFiles(directory, prefix = '') {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    return entry.isDirectory() ? listFiles(path.join(directory, entry.name), relative) : [relative];
  });
}
