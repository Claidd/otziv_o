export const TOKEN_REVOCATION_MARKER = 'revoked-v1';

export interface TokenRevocationOperations {
  persistMarker(): Promise<void>;
  overwriteSecureToken(): Promise<void>;
  removeLegacyToken(): Promise<void>;
  removeSecureToken(): Promise<void>;
  clearMarker(): Promise<void>;
}

/**
 * Establishes a durable revocation barrier before deleting token material.
 * Either the non-secret marker or an overwritten secure value is sufficient
 * to prevent an old refresh token from being accepted after a restart.
 */
export async function revokeStoredTokens(operations: TokenRevocationOperations): Promise<void> {
  let markerPersisted = false;
  let secureTokenOverwritten = false;

  try {
    await operations.persistMarker();
    markerPersisted = true;
  } catch {
    // The secure overwrite below is an independent fail-closed barrier.
  }

  try {
    await operations.overwriteSecureToken();
    secureTokenOverwritten = true;
  } catch {
    // A persisted marker still prevents the old secure value from loading.
  }

  if (!markerPersisted && !secureTokenOverwritten) {
    throw new Error('Не удалось надёжно отозвать сохранённую сессию.');
  }

  let legacyTokenRemoved = false;
  try {
    await operations.removeLegacyToken();
    legacyTokenRemoved = true;
  } catch {
    // Keep whichever revocation barrier succeeded. A later startup retries.
  }

  let secureTokenRemoved = false;
  // Without a Preferences marker, retain the overwritten secure value until
  // the legacy plaintext copy is gone; otherwise fallback migration could
  // resurrect that copy after successful secure deletion.
  if (markerPersisted || legacyTokenRemoved) {
    try {
      await operations.removeSecureToken();
      secureTokenRemoved = true;
    } catch {
      // The marker/overwritten value remains authoritative after a restart.
    }
  }

  if (markerPersisted && legacyTokenRemoved && secureTokenRemoved) {
    try {
      await operations.clearMarker();
    } catch {
      // A stale marker is fail-closed; the next successful login clears it.
    }
  }
}

export function isTokenRevocationMarked(value: string | null | undefined): boolean {
  return value === TOKEN_REVOCATION_MARKER;
}
