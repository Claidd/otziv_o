import { clientApiOperations, clientApiSchemas, clientApiCompatibilityFields, type ClientApiOperationKey, type ClientApiOperations } from './client-api.generated';
import type { ApiOperation, ApiSchema } from './client-api-schema';
import { ClientContractError } from './wire-schema';

export { CLIENT_API_CONTRACT_VERSION, clientApiOperations, clientApiSchemas } from './client-api.generated';
export type * from './client-api.generated';
export type { ApiOperation, ApiSchema } from './client-api-schema';
export { ClientContractError } from './wire-schema';

/** Structural wire validation. Output enum strings remain observable so domain decoders can disable unknown capabilities. */
export function validateClientJson(value: unknown, schema: ApiSchema, direction: 'request' | 'response', at = '$',
  schemaCatalog: Readonly<Record<string, ApiSchema>> = clientApiSchemas): void {
  const fail = (): never => { throw new ClientContractError(`Invalid API ${direction} at ${at}`); };
  if (schema.$ref) {
    const targetName = schema.$ref.split('/').at(-1)!;
    const target = schemaCatalog[targetName];
    if (!target) throw new ClientContractError('Unknown generated API schema');
    const optional = direction === 'response' && schemaCatalog === clientApiSchemas ? clientApiCompatibilityFields[targetName] ?? [] : [];
    validateClientJson(value, optional.length ? { ...target, required: target.required?.filter(field => !optional.includes(field)) } : target, direction, at, schemaCatalog); return;
  }
  if (schema.anyOf) {
    for (const choice of schema.anyOf) {
      try { validateClientJson(value, choice, direction, at, schemaCatalog); return; } catch (error) { if (!(error instanceof ClientContractError)) throw error; }
    }
    fail();
  }
  switch (schema.type) {
    case undefined: return; // Explicit Object/JSON declaration in Java; no invented shape.
    case 'null': if (value !== null) fail(); break;
    case 'string': if (typeof value !== 'string') fail(); break;
    case 'boolean': if (typeof value !== 'boolean') fail(); break;
    case 'number': if (typeof value !== 'number' || !Number.isFinite(value)) fail(); break;
    case 'integer': if (typeof value !== 'number' || !Number.isSafeInteger(value)) fail(); break;
    case 'array':
      if (!Array.isArray(value)) fail();
      for (const item of value as unknown[]) validateClientJson(item, schema.items!, direction, `${at}[]`, schemaCatalog);
      break;
    case 'object': {
      if (!value || typeof value !== 'object' || Array.isArray(value)) fail();
      const object = value as Record<string, unknown>;
      for (const required of schema.required ?? []) if (object[required] === undefined) fail();
      for (const [name, item] of Object.entries(object)) {
        if (item === undefined && direction === 'request') continue; // Angular JSON serialization omits undefined members.
        const nested = schema.properties?.[name] ?? (typeof schema.additionalProperties === 'object' ? schema.additionalProperties : undefined);
        if (nested) validateClientJson(item, nested, direction, `${at}.${name}`, schemaCatalog);
      }
      break;
    }
    default: throw new ClientContractError('Unsupported generated API schema');
  }
  if (direction === 'request' && schema.enum && !schema.enum.includes(value)) fail();
  if (direction === 'request' && schema['x-not-blank'] && (typeof value !== 'string' || !value.trim())) fail();
}

const routes = Object.values(clientApiOperations).map(operation => {
  const names: string[] = [];
  const source = operation.path.split('/').map(part => {
    if (part.startsWith('{') && part.endsWith('}')) { names.push(part.slice(1, -1)); return '([^/]+)'; }
    return part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }).join('/');
  return { operation, names, regex: new RegExp(`^${source}$`) };
}).sort((a, b) => a.names.length - b.names.length || b.operation.path.length - a.operation.path.length);

export function findClientOperation(method: string, url: string): { operation: ApiOperation; path: Record<string, string> } | undefined {
  const pathname = new URL(url, 'http://client.invalid').pathname;
  const offset = pathname.indexOf('/api/');
  if (offset < 0) return undefined;
  for (const route of routes) {
    if (route.operation.method !== method.toUpperCase()) continue;
    const match = route.regex.exec(pathname.slice(offset));
    if (match) return { operation: route.operation, path: Object.fromEntries(route.names.map((name, index) => [name, decodeURIComponent(match[index + 1])])) };
  }
  return undefined;
}

/** A transport-neutral SDK request description. Angular remains responsible for sending it, auth and native URLs. */
export function prepareClientOperation<K extends ClientApiOperationKey>(key: K, input: Omit<ClientApiOperations[K], 'response'>): {
  method: string; path: string; query: Record<string, unknown>; body: ClientApiOperations[K]['body'];
  decode: (value: unknown) => ClientApiOperations[K]['response'];
} {
  const operation = clientApiOperations[key];
  const path = operation.path.replace(/\{([^}]+)\}/g, (_, name: string) => {
    const value = (input.path as Record<string, unknown>)[name];
    if (value == null) throw new ClientContractError('Missing API path parameter');
    const parameter = operation.parameters.find(item => item.in === 'path' && item.name === name)!;
    validateClientJson(value, parameter.schema, 'request', name);
    return encodeURIComponent(String(value));
  });
  validateClientRequest(operation, input.body);
  return { method: operation.method, path, query: input.query as Record<string, unknown>, body: input.body,
    decode: value => { if (operation.response) validateClientJson(value, operation.response, 'response'); return value as ClientApiOperations[K]['response']; } };
}

export function validateClientRequest(operation: ApiOperation, body: unknown): void {
  if (operation.body) {
    if (body == null && !operation.bodyRequired) return;
    validateClientJson(body, operation.body, 'request');
  }
  // FormData must keep its platform-native boundary; neither clone it nor set a JSON content type.
  if (operation.multipart) {
    if (!body || typeof (body as { has?: unknown }).has !== 'function') throw new ClientContractError('Invalid API multipart body');
    for (const key of operation.multipart.required ?? []) if (!(body as { has(name: string): boolean }).has(key)) throw new ClientContractError('Missing API multipart field');
  }
}

export function validateClientParameters(operation: ApiOperation, path: Readonly<Record<string, string>>,
  read: (location: string, name: string) => readonly string[] | null): void {
  for (const parameter of operation.parameters) {
    const values = parameter.in === 'path' ? path[parameter.name] == null ? null : [path[parameter.name]] : read(parameter.in, parameter.name);
    if (!values?.length) {
      if (parameter.required) throw new ClientContractError('Missing API parameter');
      continue;
    }
    const coerce = (value: string, schema: ApiSchema): unknown => {
      if (schema.anyOf) return coerce(value, schema.anyOf.find(candidate => candidate.type !== 'null')!);
      if (schema.type === 'integer' || schema.type === 'number') return value.trim() ? Number(value) : value;
      if (schema.type === 'boolean') return value === 'true' ? true : value === 'false' ? false : value;
      if (schema.type === 'array') return values.flatMap(item => item.split(',')).map(item => coerce(item, schema.items!));
      return value;
    };
    validateClientJson(coerce(values[0], parameter.schema), parameter.schema, 'request', parameter.name);
  }
}

export function decodeClientError(operation: ApiOperation, status: number, value: unknown): { status: number; body: unknown; schemaVerified: boolean } {
  const schema = operation.errors[String(status)];
  if (!schema) return { status, body: value, schemaVerified: false };
  validateClientJson(value, schema, 'response', '$error');
  return { status, body: value, schemaVerified: true };
}
