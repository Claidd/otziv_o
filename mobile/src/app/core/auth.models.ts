export type AuthStatus = 'initializing' | 'anonymous' | 'authenticated' | 'refreshing' | 'retrying' | 'error';

export type AuthRefreshResult =
  | { status: 'ready'; accessToken: string; refreshed: boolean }
  | { status: 'temporary-unavailable' }
  | { status: 'session-invalid' }
  | { status: 'superseded' };

/** A recoverable outage must not be turned into an anonymous API request or logout. */
export class AuthTemporarilyUnavailableError extends Error {
  constructor() {
    super('Не удалось обновить сессию. Проверьте соединение и повторите действие.');
  }
}

export interface AuthUser {
  subject: string;
  preferredUsername: string;
  email?: string;
  name?: string;
  roles: string[];
}

export interface StoredTokens {
  accessToken: string;
  tokenType: string;
  expiresAt: number;
  refreshToken?: string;
  refreshExpiresAt?: number;
  idToken?: string;
  scope?: string;
}

export interface PendingLogin {
  state: string;
  codeVerifier: string;
  targetUrl: string;
  redirectUri: string;
}

export interface TokenEndpointResponse {
  access_token: string;
  token_type?: string;
  expires_in: number;
  refresh_token?: string;
  refresh_expires_in?: number;
  id_token?: string;
  scope?: string;
}
