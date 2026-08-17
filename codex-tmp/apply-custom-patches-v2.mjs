import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const mode = args.shift();
if (!['--check', '--apply'].includes(mode) || args.length === 0) {
  throw new Error('Usage: node apply-custom-patches-v2.mjs --check|--apply PATCH...');
}

const root = path.resolve(process.cwd());
const state = new Map();
const touched = new Set();
let whitespaceMatches = 0;

function safePath(relative) {
  const absolute = path.resolve(root, relative.replaceAll('/', path.sep));
  const prefix = root.endsWith(path.sep) ? root : root + path.sep;
  if (absolute !== root && !absolute.startsWith(prefix)) throw new Error(`Patch path escapes workspace: ${relative}`);
  return absolute;
}

function load(relative, mustExist = true) {
  if (state.has(relative)) return state.get(relative);
  const absolute = safePath(relative);
  if (!fs.existsSync(absolute)) {
    if (mustExist) throw new Error(`Missing target: ${relative}`);
    const created = { relative, absolute, lines: [], eol: '\n', finalNewline: true, existed: false };
    state.set(relative, created);
    return created;
  }
  const raw = fs.readFileSync(absolute, 'utf8');
  const eol = raw.includes('\r\n') ? '\r\n' : '\n';
  const normalized = raw.replaceAll('\r\n', '\n');
  const finalNewline = normalized.endsWith('\n');
  const lines = normalized.split('\n');
  if (finalNewline) lines.pop();
  const loaded = { relative, absolute, lines, eol, finalNewline, existed: true };
  state.set(relative, loaded);
  return loaded;
}

function comparable(line) {
  return line.trim().replace(/\s+/g, ' ');
}

function startsAt(lines, index, expected, ignoreWhitespace) {
  if (index + expected.length > lines.length) return false;
  for (let i = 0; i < expected.length; i += 1) {
    const actualLine = ignoreWhitespace ? comparable(lines[index + i]) : lines[index + i];
    const expectedLine = ignoreWhitespace ? comparable(expected[i]) : expected[i];
    if (actualLine !== expectedLine) return false;
  }
  return true;
}

function findMatch(lines, expected, cursor, label) {
  if (expected.length === 0) throw new Error(`Unsafe empty-context hunk: ${label}`);
  for (let index = cursor; index <= lines.length - expected.length; index += 1) {
    if (startsAt(lines, index, expected, false)) return index;
  }
  for (let index = cursor; index <= lines.length - expected.length; index += 1) {
    if (startsAt(lines, index, expected, true)) {
      whitespaceMatches += 1;
      return index;
    }
  }
  throw new Error(`Hunk context not found after line ${cursor + 1}: ${label}\n${expected.slice(0, 10).join('\n')}`);
}

function parseHunks(sectionLines, label) {
  const hunks = [];
  let current = null;
  for (const line of sectionLines) {
    if (line === '@@') {
      if (current) hunks.push(current);
      current = { oldLines: [], operations: [] };
      continue;
    }
    if (!current) {
      if (line.trim() === '') continue;
      throw new Error(`Content before first hunk in ${label}: ${line}`);
    }
    if (line.startsWith(' ')) {
      current.oldLines.push(line.slice(1));
      current.operations.push({ kind: 'context', line: line.slice(1) });
    } else if (line.startsWith('-')) {
      current.oldLines.push(line.slice(1));
      current.operations.push({ kind: 'remove', line: line.slice(1) });
    } else if (line.startsWith('+')) {
      current.operations.push({ kind: 'add', line: line.slice(1) });
    } else if (line !== '\\ No newline at end of file') {
      throw new Error(`Malformed hunk line in ${label}: ${line}`);
    }
  }
  if (current) hunks.push(current);
  if (hunks.length === 0) throw new Error(`No hunks in ${label}`);
  return hunks;
}

function applyUpdate(relative, sectionLines, patchName) {
  const target = load(relative, true);
  const hunks = parseHunks(sectionLines, `${patchName}:${relative}`);
  let cursor = 0;
  for (let i = 0; i < hunks.length; i += 1) {
    const hunk = hunks[i];
    const index = findMatch(target.lines, hunk.oldLines, cursor, `${patchName}:${relative}:hunk${i + 1}`);
    let sourceOffset = 0;
    const replacement = [];
    for (const operation of hunk.operations) {
      if (operation.kind === 'context') {
        replacement.push(target.lines[index + sourceOffset]);
        sourceOffset += 1;
      } else if (operation.kind === 'remove') {
        sourceOffset += 1;
      } else {
        replacement.push(operation.line);
      }
    }
    target.lines.splice(index, hunk.oldLines.length, ...replacement);
    cursor = index + replacement.length;
  }
  touched.add(relative);
}

function applyAdd(relative, sectionLines, patchName) {
  const absolute = safePath(relative);
  if (fs.existsSync(absolute) || state.has(relative)) throw new Error(`Add target already exists: ${patchName}:${relative}`);
  const content = [];
  for (const line of sectionLines) {
    if (!line.startsWith('+')) throw new Error(`Malformed Add File line in ${patchName}:${relative}: ${line}`);
    content.push(line.slice(1));
  }
  state.set(relative, { relative, absolute, lines: content, eol: '\n', finalNewline: true, existed: false });
  touched.add(relative);
}

function applyPatchFile(patchFile) {
  const absolutePatch = path.resolve(root, patchFile);
  const patchName = path.relative(root, absolutePatch);
  const raw = fs.readFileSync(absolutePatch, 'utf8').replaceAll('\r\n', '\n');
  const lines = raw.split('\n');
  if (lines.at(-1) === '') lines.pop();
  if (lines.shift() !== '*** Begin Patch' || lines.pop() !== '*** End Patch') throw new Error(`Invalid patch sentinels: ${patchName}`);
  let index = 0;
  while (index < lines.length) {
    if (lines[index].trim() === '') { index += 1; continue; }
    const match = /^\*\*\* (Update|Add) File: (.+)$/.exec(lines[index]);
    if (!match) throw new Error(`Unsupported directive in ${patchName}: ${lines[index]}`);
    const [, kind, relative] = match;
    index += 1;
    const section = [];
    while (index < lines.length && !/^\*\*\* (Update|Add) File: /.test(lines[index])) {
      section.push(lines[index]);
      index += 1;
    }
    if (kind === 'Update') applyUpdate(relative, section, patchName);
    else applyAdd(relative, section, patchName);
  }
}

for (const patchFile of args) applyPatchFile(patchFile);

if (mode === '--apply') {
  for (const relative of touched) {
    const target = state.get(relative);
    fs.mkdirSync(path.dirname(target.absolute), { recursive: true });
    const body = target.lines.join(target.eol) + (target.finalNewline ? target.eol : '');
    const temporary = `${target.absolute}.codex-patch-tmp`;
    fs.writeFileSync(temporary, body, 'utf8');
    fs.renameSync(temporary, target.absolute);
  }
}

process.stdout.write(`${mode === '--apply' ? 'Applied' : 'Checked'} ${args.length} patches; ${touched.size} files; ${whitespaceMatches} whitespace-tolerant hunks.\n`);
