import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = process.cwd();
const inputs = [
  'codex-tmp/manual-payment-route-ui-existing.patch',
  'codex-tmp/manual-task-api-existing.patch',
  'codex-tmp/manual-task-home-web-existing.patch',
  'codex-tmp/manual-task-home-web-fix-existing.patch',
  'codex-tmp/manual-task-admin-web-existing.patch',
  'codex-tmp/manual-task-home-mobile-v2-existing.patch',
  'codex-tmp/manual-task-admin-mobile-existing.patch'
];
const virtualRoot = path.join(root, 'codex-tmp', 'virtual-ui-final');
const baseRoot = path.join(virtualRoot, 'base');
const finalRoot = path.join(virtualRoot, 'final');
const outputPath = path.join(root, 'codex-tmp', 'manual-task-ui-existing-final.patch');
const virtualFiles = new Map();

fs.rmSync(virtualRoot, { recursive: true, force: true });

for (const input of inputs) {
  const source = fs.readFileSync(path.join(root, input), 'utf8').replaceAll('\r\n', '\n');
  for (const section of parseSections(source)) {
    const base = virtualFiles.get(section.file)
      ?? fs.readFileSync(path.join(root, section.file), 'utf8').replaceAll('\r\n', '\n').split('\n');
    const working = [...base];
    let cursor = 0;

    for (const hunk of section.hunks) {
      const oldLines = hunk.lines
        .filter(line => line.startsWith(' ') || line.startsWith('-'))
        .map(line => line.slice(1));
      const newLines = hunk.lines
        .filter(line => line.startsWith(' ') || line.startsWith('+'))
        .map(line => line.slice(1));
      const match = findSequence(working, oldLines, cursor);
      if (match < 0) {
        throw new Error(`${input}: cannot locate hunk in ${section.file}: ${oldLines.slice(0, 5).join(' | ')}`);
      }
      working.splice(match, oldLines.length, ...newLines);
      cursor = match + newLines.length;
    }
    virtualFiles.set(section.file, working);
  }
}

const output = [];
for (const [file, finalLines] of [...virtualFiles.entries()].sort(([left], [right]) => left.localeCompare(right))) {
  const sourcePath = path.join(root, file);
  const basePath = path.join(baseRoot, file);
  const finalPath = path.join(finalRoot, file);
  fs.mkdirSync(path.dirname(basePath), { recursive: true });
  fs.mkdirSync(path.dirname(finalPath), { recursive: true });
  fs.copyFileSync(sourcePath, basePath);
  fs.writeFileSync(finalPath, finalLines.join('\n'), 'utf8');

  const relativeBase = path.relative(root, basePath).replaceAll('\\', '/');
  const relativeFinal = path.relative(root, finalPath).replaceAll('\\', '/');
  const result = spawnSync(
    'git',
    ['diff', '--no-index', '--no-prefix', '--no-ext-diff', '--text', '--', relativeBase, relativeFinal],
    { cwd: root, encoding: 'utf8' }
  );
  if (result.status !== 0 && result.status !== 1) {
    throw new Error(`git diff failed for ${file}: ${result.stderr}`);
  }
  if (result.status === 0) continue;

  const diffLines = result.stdout.replaceAll('\r\n', '\n').split('\n');
  diffLines[0] = `diff --git a/${file} b/${file}`;
  const oldHeader = diffLines.findIndex(line => line.startsWith('--- '));
  const newHeader = diffLines.findIndex(line => line.startsWith('+++ '));
  if (oldHeader < 0 || newHeader < 0) {
    throw new Error(`git diff emitted no file headers for ${file}`);
  }
  diffLines[oldHeader] = `--- a/${file}`;
  diffLines[newHeader] = `+++ b/${file}`;
  output.push(...diffLines.filter((line, index) => index < diffLines.length - 1 || line !== ''));
}

fs.writeFileSync(outputPath, `${output.join('\n')}\n`, 'utf8');
console.log(`Wrote ${outputPath}`);
console.log(`Files: ${virtualFiles.size}`);

function parseSections(source) {
  const lines = source.split('\n');
  const sections = [];
  let section = null;
  let hunk = null;
  for (const line of lines) {
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
    if (hunk && (line.startsWith(' ') || line.startsWith('+') || line.startsWith('-'))) {
      hunk.lines.push(line);
    }
  }
  return sections;
}

function findSequence(haystack, needle, start) {
  if (!needle.length) return start;
  for (let index = Math.max(0, start); index <= haystack.length - needle.length; index += 1) {
    let matches = true;
    for (let offset = 0; offset < needle.length; offset += 1) {
      if (haystack[index + offset] !== needle[offset]) {
        matches = false;
        break;
      }
    }
    if (matches) return index;
  }
  return -1;
}
