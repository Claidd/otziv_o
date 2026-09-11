import assert from 'node:assert/strict';
import { readFile, appendFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

export const composeFiles = ['compose.yaml', 'compose.prod-local.yaml', 'docker-compose.yaml'];
const separatelyBuilt = /^\$\{(?:APP_IMAGE|WEB_IMAGE|EXTERNAL_REVIEW_WORKER_IMAGE|WHATSAPP_IMAGE|DOCKER_OBSERVER_IMAGE)(?::[-?][^}]*)?\}$/;

// Read literal release defaults, without loading .env or resolving credentials.
// Application images are already built and scanned in their own CI matrix.
export function inventory(documents) {
  const found = new Map();
  for (const { path, text } of documents) {
    let services = false, service;
    for (const [index, line] of text.split(/\r?\n/).entries()) {
      if (/^\S/.test(line) && !line.startsWith('#')) {
        services = /^services:\s*(?:#.*)?$/.test(line); service = undefined;
      }
      if (!services) continue;
      const name = line.match(/^  ([a-zA-Z0-9_-]+):\s*(?:#.*)?$/);
      if (name) service = name[1];
      const image = line.match(/^    image:\s*(.*?)\s*(?:#.*)?$/);
      if (!image) continue;
      assert.ok(service, 'upstream_service_missing');
      let value = image[1].replace(/^(['"])(.*)\1$/, '$2');
      if (separatelyBuilt.test(value)) continue;
      const fallback = value.match(/^\$\{[A-Z0-9_]+:-(.+)\}$/);
      if (fallback) value = fallback[1];
      assert.match(value, /^[a-zA-Z0-9._/:+-]+@sha256:[a-f0-9]{64}$/, 'upstream_image_requires_digest');
      const [nameWithTag, digest] = value.split('@');
      const lastSlash = nameWithTag.lastIndexOf('/'), lastColon = nameWithTag.lastIndexOf(':');
      const repository = lastColon > lastSlash ? nameWithTag.slice(0, lastColon) : nameWithTag;
      const normalized = repository + '@' + digest;
      if (!found.has(normalized)) found.set(normalized, { id: repository.replace(/[^a-zA-Z0-9_-]/g, '-') + '-' +
        createHash('sha256').update(normalized).digest('hex').slice(0, 10), image: normalized, references: [] });
      found.get(normalized).references.push({ path, line: index + 1, service });
    }
  }
  assert.ok(found.size, 'upstream_inventory_empty');
  return [...found.values()].sort((a, b) => a.image.localeCompare(b.image));
}

export async function repositoryInventory(root = process.cwd()) {
  const documents = await Promise.all(composeFiles.map(async path => ({ path, text: await readFile(resolve(root, path), 'utf8') })));
  return inventory(documents);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const rows = await repositoryInventory();
  if (process.argv[2] === 'matrix') {
    assert.ok(process.env.GITHUB_OUTPUT, 'github_output_missing');
    await appendFile(process.env.GITHUB_OUTPUT, 'matrix=' + JSON.stringify({ include: rows }) + '\n');
    console.log(JSON.stringify({ images: rows.length, source: composeFiles, credentialsRead: false }));
  } else {
    assert.equal(process.argv.length, 2, 'upstream_arguments_invalid');
    console.log(JSON.stringify({ schema: 'otziv-compose-upstream-images-v1', images: rows }, null, 2));
  }
}
