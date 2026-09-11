export type WireSchema = {
  $ref?: string;
  anyOf?: readonly WireSchema[];
  type?: string;
  minimum?: number;
  required?: readonly string[];
  properties?: Readonly<Record<string, WireSchema>>;
  items?: WireSchema;
};

export class ClientContractError extends Error {
  constructor(message: string) { super(message); this.name = 'ClientContractError'; }
}

export function validateWire(
  value: unknown,
  schema: WireSchema,
  schemas: Readonly<Record<string, WireSchema>>,
  optionalFields: Readonly<Record<string, readonly string[]>>,
  name: string,
  context = 'Invalid API response'
): void {
  const child = (item: unknown, nested: WireSchema, key: string) => validateWire(item, nested, schemas, optionalFields, key, context);
  if (schema.$ref) {
    const target = schema.$ref.split('/').at(-1)!;
    child(value, schemas[target], target);
    return;
  }
  if (schema.anyOf) {
    for (const candidate of schema.anyOf) {
      try { child(value, candidate, name); return; } catch { /* Try the next allowed wire type. */ }
    }
    throw new ClientContractError(`${context}: ${name}`);
  }
  let valid = false;
  switch (schema.type) {
    case 'null': valid = value === null; break;
    case 'string': valid = typeof value === 'string'; break;
    case 'boolean': valid = typeof value === 'boolean'; break;
    case 'number': valid = typeof value === 'number' && Number.isFinite(value); break;
    case 'integer': valid = typeof value === 'number' && Number.isSafeInteger(value); break;
    case 'array':
      valid = Array.isArray(value);
      if (valid) for (const item of value as unknown[]) child(item, schema.items!, `${name}[]`);
      break;
    case 'object':
      valid = value !== null && typeof value === 'object' && !Array.isArray(value);
      if (valid) {
        const object = value as Record<string, unknown>;
        for (const required of schema.required ?? []) {
          if (!(required in object) && !optionalFields[name]?.includes(required)) {
            throw new ClientContractError(`${context}: missing ${name}.${required}`);
          }
        }
        for (const [field, nested] of Object.entries(schema.properties ?? {})) {
          if (field in object) child(object[field], nested, `${name}.${field}`);
        }
      }
      break;
  }
  if (!valid || (typeof value === 'number' && schema.minimum != null && value < schema.minimum)) {
    throw new ClientContractError(`${context}: ${name}`);
  }
}
