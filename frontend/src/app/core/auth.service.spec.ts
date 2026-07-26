import { describe, expect, it } from 'vitest';
import { hasKeycloakAuthenticationCallback } from './auth.service';

describe('hasKeycloakAuthenticationCallback', () => {
  it('recognizes the mobile-safe fragment callback', () => {
    expect(hasKeycloakAuthenticationCallback(
      'https://o-ogo.ru/#state=state-1&session_state=session-1&code=code-1'
    )).toBe(true);
  });

  it('recognizes a callback from the previous query response mode', () => {
    expect(hasKeycloakAuthenticationCallback(
      'https://o-ogo.ru/?state=state-1&session_state=session-1&code=code-1'
    )).toBe(true);
  });

  it('does not mistake an ordinary application URL for an authentication callback', () => {
    expect(hasKeycloakAuthenticationCallback(
      'https://o-ogo.ru/worker?status=publish#orders'
    )).toBe(false);
  });
});
