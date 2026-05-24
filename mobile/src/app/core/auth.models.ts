export type AuthStatus = 'initializing' | 'anonymous' | 'authenticated' | 'refreshing' | 'error';

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
