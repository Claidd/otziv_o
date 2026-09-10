import { readSourceFingerprints } from './source-fingerprints.mjs';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const file = path.join(root, 'contracts/generated/client-api.openapi.json');
if (!fs.existsSync(file)) throw new Error('Run ClientApiContractExportTest with -Dclient.contract.export=true first.');
const api = JSON.parse(fs.readFileSync(file, 'utf8'));
for (const { path: relative, sha256: expected } of readSourceFingerprints(api)) {
  const actual = crypto.createHash('sha256').update(fs.readFileSync(path.join(root, relative), 'utf8').replaceAll('\r\n', '\n')).digest('hex');
  if (actual !== expected) throw new Error(`Compiled client contract source changed: ${relative}. Re-export and review the contract.`);
}
const schemas = api.components.schemas;
const compatibility = Object.assign({}, ...['order-editor.config.json', 'billing-payments.config.json', 'client-api.config.json'].map(name => {
  const config = JSON.parse(fs.readFileSync(path.join(root, 'contracts', name), 'utf8'));
  return Object.fromEntries(Object.entries(config.optionalForCompatibility ?? {}).map(([type, fields]) => [`${type}Output`, fields]));
}));
for (const [type, fields] of Object.entries(compatibility)) for (const field of fields) {
  if (!schemas[type]?.properties?.[field]) throw new Error(`Unknown compatibility field: ${type}.${field}`);
}

const refName = ref => ref.split('/').at(-1);
function ts(schema) {
  if (!schema) return 'unknown';
  if (schema.$ref) return refName(schema.$ref);
  if (schema.anyOf) return schema.anyOf.map(ts).join(' | ');
  if (schema.enum) return schema.enum.map(value => JSON.stringify(value)).join(' | ');
  if (schema.type === 'array') return `Array<${ts(schema.items)}>`;
  if (schema.type === 'object') {
    if (!schema.properties) return `Record<string, ${typeof schema.additionalProperties === 'object' ? ts(schema.additionalProperties) : 'unknown'}>`;
    const fields = Object.entries(schema.properties).map(([key, value]) => `${JSON.stringify(key)}${schema.required?.includes(key) ? '' : '?'}: ${ts(value)};`);
    return `{ ${fields.join(' ')} }`;
  }
  return { string: 'string', boolean: 'boolean', number: 'number', integer: 'number', null: 'null' }[schema.type] ?? 'unknown';
}
const operations = {};
const typings = [];
for (const [url, verbs] of Object.entries(api.paths)) for (const [verb, operation] of Object.entries(verbs)) {
  const key = `${verb.toUpperCase()} ${url}`;
  const success = Object.entries(operation.responses).find(([status]) => Number(status) >= 200 && Number(status) < 300);
  const content = operation.requestBody?.content;
  const body = content?.['application/json']?.schema;
  const multipart = content?.['multipart/form-data']?.schema;
  const response = success?.[1].content?.['application/json']?.schema;
  operations[key] = { method: verb.toUpperCase(), path: url, parameters: operation.parameters,
    ...(body ? { body } : {}), ...(multipart ? { multipart } : {}), ...(response ? { response } : {}),
    bodyRequired: operation.requestBody?.required ?? false, successStatus: success ? Number(success[0]) : null,
    permission: operation['x-preauthorize'] ?? null, publicCapability: operation['x-public-capability'] ?? false,
    errors: Object.fromEntries(Object.entries(operation.responses).filter(([status]) => Number(status) >= 400).map(([status, value]) => [status, value.content?.['application/json']?.schema ?? null])) };
  const params = location => `{ ${operation.parameters.filter(p => p.in === location).map(p => `${JSON.stringify(p.name)}${p.required ? '' : '?'}: ${ts(p.schema)};`).join(' ')} }`;
  typings.push(`  ${JSON.stringify(key)}: { path: ${params('path')}; query: ${params('query')}; body: ${body ? ts(body) : multipart ? 'MultipartBody' : 'undefined'}; response: ${response ? ts(response) : 'void'} };`);
}
const source = `// Generated from compiled Spring mappings and the MVC Jackson property model. Do not edit.\nimport type { ApiOperation, ApiSchema, MultipartBody } from './client-api-schema';\n\nexport const CLIENT_API_CONTRACT_VERSION = ${JSON.stringify(api.info.version)};\n\n${Object.entries(schemas).map(([name, schema]) => `export type ${name} = ${ts(schema)}${schema.enum && name.endsWith('Output') ? ' | (string & {})' : ''};`).join('\n\n')}\n\nexport interface ClientApiOperations {\n${typings.join('\n')}\n}\nexport type ClientApiOperationKey = keyof ClientApiOperations;\nexport const clientApiCompatibilityFields: Readonly<Record<string, readonly string[]>> = ${JSON.stringify(compatibility)};\nexport const clientApiSchemas: Readonly<Record<string, ApiSchema>> = ${JSON.stringify(schemas)};\nexport const clientApiOperations: Readonly<Record<ClientApiOperationKey, ApiOperation>> = ${JSON.stringify(operations)};\n`;
const target = path.join(root, 'shared/client-common/src/client-api.generated.ts');
if (process.argv.includes('--check')) {
  if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8') !== source) throw new Error('Client SDK drift: run node contracts/generate.mjs');
} else fs.writeFileSync(target, source);
console.log(`${process.argv.includes('--check') ? 'Checked' : 'Generated'} compiled client API: ${Object.keys(operations).length} operations, ${Object.keys(schemas).length} input/output schemas.`);
