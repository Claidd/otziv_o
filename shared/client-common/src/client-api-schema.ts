export interface ApiSchema {
  readonly $ref?: string;
  readonly anyOf?: readonly ApiSchema[];
  readonly type?: string;
  readonly format?: string;
  readonly enum?: readonly unknown[];
  readonly required?: readonly string[];
  readonly properties?: Readonly<Record<string, ApiSchema>>;
  readonly items?: ApiSchema;
  readonly additionalProperties?: boolean | ApiSchema;
  readonly [extension: `x-${string}`]: unknown;
}

/** Structural platform adapter; the SDK does not import or construct native/DOM FormData. */
export interface MultipartBody { has(name: string): boolean; }

export interface ApiOperation {
  readonly method: string;
  readonly path: string;
  readonly parameters: readonly { name: string; in: string; required: boolean; schema: ApiSchema; 'x-server-default'?: string }[];
  readonly body?: ApiSchema;
  readonly multipart?: ApiSchema;
  readonly response?: ApiSchema;
  readonly bodyRequired: boolean;
  readonly successStatus: number | null;
  readonly permission: string | null;
  readonly publicCapability: boolean;
  readonly errors: Readonly<Record<string, ApiSchema | null>>;
}
