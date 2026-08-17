import fs from 'node:fs';
import path from 'node:path';

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
const outputDir = path.join(root, 'codex-tmp', 'final');
fs.mkdirSync(outputDir, { recursive: true });

const virtualFiles = new Map();

for (const input of inputs) {
  const source = fs.readFileSync(path.join(root, input), 'utf8').replaceAll('\r\n', '\n');
  const sections = parseSections(source);
  const output = [];

  for (const section of sections) {
    output.push(`diff --git a/${section.file} b/${section.file}`);
    output.push(`--- a/${section.file}`);
    output.push(`+++ b/${section.file}`);

    const base = virtualFiles.get(section.file)
      ?? fs.readFileSync(path.join(root, section.file), 'utf8').replaceAll('\r\n', '\n').split('\n');
    const working = [...base];
    let cursor = 0;
    let delta = 0;

    for (const hunk of section.hunks) {
      const oldLines = hunk.lines
        .filter(line => line.startsWith(' ') || line.startsWith('-'))
        .map(line => line.slice(1));
      const newLines = hunk.lines
        .filter(line => line.startsWith(' ') || line.startsWith('+'))
        .map(line => line.slice(1));
      const match = findSequence(working, oldLines, cursor);
      if (match < 0) {
        throw new Error(`${input}: cannot locate hunk in ${section.file}: ${oldLines.slice(0, 4).join(' | ')}`);
      }
      const oldStart = match + 1 - delta;
      const newStart = match + 1;
      output.push(`@@ -${oldStart},${oldLines.length} +${newStart},${newLines.length} @@`);
      output.push(...hunk.lines);
      working.splice(match, oldLines.length, ...newLines);
      cursor = match + newLines.length;
      delta += newLines.length - oldLines.length;
    }
    virtualFiles.set(section.file, working);
  }

  fs.writeFileSync(path.join(outputDir, path.basename(input)), `${output.join('\n')}\n`, 'utf8');
}

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
    if (!section || line.startsWith('--- ') || line.startsWith('+++ ')) {
      continue;
    }
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
