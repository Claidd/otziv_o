import type { PendingLogin, StoredTokens } from './auth.models';

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isOptionalString(value: unknown): boolean {
  return value === undefined || typeof value === 'string';
}

function isOptionalPositiveNumber(value: unknown): boolean {
  return value === undefined || (typeof value === 'number' && Number.isFinite(value) && value > 0);
}

export function isStoredTokens(value: unknown): value is StoredTokens {
  return isRecord(value)
    && typeof value['accessToken'] === 'string'
    && value['accessToken'].trim().length > 0
    && typeof value['tokenType'] === 'string'
    && value['tokenType'].trim().length > 0
    && typeof value['expiresAt'] === 'number'
    && Number.isFinite(value['expiresAt'])
    && value['expiresAt'] > 0
    && isOptionalString(value['refreshToken'])
    && isOptionalPositiveNumber(value['refreshExpiresAt'])
    && isOptionalString(value['idToken'])
    && isOptionalString(value['scope']);
}

export function isPendingLogin(value: unknown): value is PendingLogin {
  if (!isRecord(value)) {
    return false;
  }
  const targetUrl = value['targetUrl'];
  return typeof value['state'] === 'string'
    && value['state'].length >= 16
    && typeof value['codeVerifier'] === 'string'
    && value['codeVerifier'].length >= 32
    && typeof targetUrl === 'string'
    && targetUrl.startsWith('/')
    && !targetUrl.startsWith('//')
    && !targetUrl.includes('\\')
    && typeof value['redirectUri'] === 'string'
    && value['redirectUri'].trim().length > 0;
}
