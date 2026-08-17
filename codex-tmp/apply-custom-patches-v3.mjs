import fs from 'node:fs';
import path from 'node:path';

const argv = process.argv.slice(2);
const mode = argv.shift();
if (!['--check', '--apply'].includes(mode) || argv.length === 0) throw new Error('Expected --check|--apply and patch files');
const root = path.resolve(process.cwd());
const files = new Map();
const touched = new Set();
let fuzzy = 0;
let reordered = 0;

function resolveSafe(relative) {
  const absolute = path.resolve(root, relative.replaceAll('/', path.sep));
  if (!absolute.startsWith(root + path.sep)) throw new Error(`Unsafe path: ${relative}`);
  return absolute;
}

function readTarget(relative) {
  if (files.has(relative)) return files.get(relative);
  const absolute = resolveSafe(relative);
  if (!fs.existsSync(absolute)) throw new Error(`Missing target: ${relative}`);
  const raw = fs.readFileSync(absolute, 'utf8');
  const eol = raw.includes('\r\n') ? '\r\n' : '\n';
  const normalized = raw.replaceAll('\r\n', '\n');
  const finalNewline = normalized.endsWith('\n');
  const lines = normalized.split('\n');
  if (finalNewline) lines.pop();
  const value = { absolute, lines, eol, finalNewline };
  files.set(relative, value);
  return value;
}

const canon = line => line.trim().replace(/\s+/g, ' ');
function matchesAt(lines, index, expected, loose) {
  if (index + expected.length > lines.length) return false;
  return expected.every((line, offset) => (loose ? canon(lines[index + offset]) === canon(line) : lines[index + offset] === line));
}

function candidates(lines, expected, loose) {
  const found = [];
  for (let i = 0; i <= lines.length - expected.length; i += 1) if (matchesAt(lines, i, expected, loose)) found.push(i);
  return found;
}

function locate(lines, expected, cursor, label) {
  if (expected.length === 0) throw new Error(`Empty context: ${label}`);
  for (const loose of [false, true]) {
    const found = candidates(lines, expected, loose);
    if (found.length === 0) continue;
    const after = found.filter(index => index >= cursor);
    let chosen;
    if (after.length > 0) chosen = after[0];
    else if (found.length === 1) { chosen = found[0]; reordered += 1; }
    else throw new Error(`Ambiguous out-of-order hunk (${found.length} matches): ${label}`);
    if (loose) fuzzy += 1;
    return chosen;
  }
  throw new Error(`Context not found: ${label}\n${expected.slice(0, 12).join('\n')}`);
}

function hunks(section, label) {
  const result = [];
  let current;
  for (const line of section) {
    if (line === '@@') {
      if (current) result.push(current);
      current = { old: [], ops: [] };
    } else if (!current) {
      if (line.trim()) throw new Error(`Unexpected pre-hunk line in ${label}: ${line}`);
    } else if (line.startsWith(' ')) {
      current.old.push(line.slice(1)); current.ops.push(['context', line.slice(1)]);
    } else if (line.startsWith('-')) {
      current.old.push(line.slice(1)); current.ops.push(['remove', line.slice(1)]);
    } else if (line.startsWith('+')) {
      current.ops.push(['add', line.slice(1)]);
    } else if (line !== '\\ No newline at end of file') {
      throw new Error(`Malformed hunk line in ${label}: ${line}`);
    }
  }
  if (current) result.push(current);
  if (!result.length) throw new Error(`No hunks in ${label}`);
  return result;
}

function update(relative, section, patchName) {
  const target = readTarget(relative);
  let cursor = 0;
  let number = 0;
  for (const hunk of hunks(section, `${patchName}:${relative}`)) {
    number += 1;
    const index = locate(target.lines, hunk.old, cursor, `${patchName}:${relative}:hunk${number}`);
    let offset = 0;
    const replacement = [];
    for (const [kind, line] of hunk.ops) {
      if (kind === 'context') { replacement.push(target.lines[index + offset]); offset += 1; }
      else if (kind === 'remove') offset += 1;
      else replacement.push(line);
    }
    target.lines.splice(index, hunk.old.length, ...replacement);
    cursor = index + replacement.length;
  }
  touched.add(relative);
}

function add(relative, section, patchName) {
  const absolute = resolveSafe(relative);
  if (fs.existsSync(absolute) || files.has(relative)) throw new Error(`Add target exists: ${patchName}:${relative}`);
  const lines = section.map(line => {
    if (!line.startsWith('+')) throw new Error(`Malformed add line in ${patchName}:${relative}`);
    return line.slice(1);
  });
  files.set(relative, { absolute, lines, eol: '\n', finalNewline: true });
  touched.add(relative);
}

function parsePatch(patchFile) {
  const patchName = path.relative(root, path.resolve(root, patchFile));
  const lines = fs.readFileSync(path.resolve(root, patchFile), 'utf8').replaceAll('\r\n', '\n').split('\n');
  if (lines.at(-1) === '') lines.pop();
  if (lines.shift() !== '*** Begin Patch' || lines.pop() !== '*** End Patch') throw new Error(`Bad sentinels: ${patchName}`);
  for (let index = 0; index < lines.length;) {
    if (!lines[index].trim()) { index += 1; continue; }
    const directive = /^\*\*\* (Update|Add) File: (.+)$/.exec(lines[index]);
    if (!directive) throw new Error(`Unsupported directive in ${patchName}: ${lines[index]}`);
    index += 1;
    const section = [];
    while (index < lines.length && !/^\*\*\* (Update|Add) File: /.test(lines[index])) section.push(lines[index++]);
    if (directive[1] === 'Update') update(directive[2], section, patchName); else add(directive[2], section, patchName);
  }
}

for (const patch of argv) parsePatch(patch);
if (mode === '--apply') {
  for (const relative of touched) {
    const target = files.get(relative);
    fs.mkdirSync(path.dirname(target.absolute), { recursive: true });
    const body = target.lines.join(target.eol) + (target.finalNewline ? target.eol : '');
    const temporary = target.absolute + '.codex-patch-tmp';
    fs.writeFileSync(temporary, body, 'utf8');
    fs.renameSync(temporary, target.absolute);
  }
}
process.stdout.write(`${mode === '--apply' ? 'Applied' : 'Checked'} ${argv.length} patches; ${touched.size} files; fuzzy=${fuzzy}; reordered=${reordered}.\n`);
