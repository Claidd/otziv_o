import { readConfig } from './config.mjs';
import { preflight, backup, readJson, writeJsonExclusive } from './backup.mjs';
import { buildManifest, compareIdentities } from './manifest.mjs';
import { postgresDrill } from './drill.mjs';

const [command, ...args] = process.argv.slice(2);
try {
  let result;
  if (command === 'preflight' && args.length === 1) result = await preflight(await readConfig(args[0]));
  else if (command === 'backup' && args.length === 1) result = await backup(await readConfig(args[0]));
  else if (command === 'manifest' && args.length === 2) {
    const manifest = buildManifest(await readJson(args[0]));
    await writeJsonExclusive(args[1], manifest);
    result = { result: 'PASS', manifestSha256: manifest.contentSha256, fullSystemRestore: 'NOT_PROVEN' };
  } else if (command === 'postgres-drill' && args.length === 3) {
    result = await postgresDrill(await readConfig(args[0]), await readJson(args[1]), args[2]);
    if (!result.withinConfiguredRto) process.exitCode = 1;
  } else if (command === 'compare-identities' && args.length === 2) {
    result = compareIdentities(await readJson(args[0]), await readJson(args[1]));
  } else throw new Error('usage: preflight|backup CONFIG; manifest INPUT OUTPUT; postgres-drill CONFIG MANIFEST ARCHIVE; compare-identities MYSQL_JSON KEYCLOAK_JSON');
  // Backup metadata includes only non-secret references; never dump configuration or child output.
  console.log(JSON.stringify(result));
} catch (error) {
  const code = /^[a-z0-9_:|; A-Z-]+$/.test(error.message || '') ? error.message : 'recovery_failed';
  console.error(JSON.stringify({ result: 'FAIL', code }));
  process.exitCode = 1;
}
