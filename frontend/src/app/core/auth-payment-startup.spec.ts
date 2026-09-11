import type Keycloak from 'keycloak-js';
import type { KeycloakInitOptions } from 'keycloak-js';
import { AuthService, isPublicPaymentPath } from './auth.service';

describe('anonymous payment startup', () => {
  function createSdk() {
    return {
      authenticated: false,
      token: undefined,
      tokenParsed: undefined,
      // Model the original failure: silent SSO never sends its callback.
      init: vi.fn((options: KeycloakInitOptions) => options.onLoad === 'check-sso'
        ? new Promise<boolean>(() => {}) : Promise.resolve(false)),
      login: vi.fn().mockResolvedValue(undefined),
      logout: vi.fn().mockResolvedValue(undefined),
      updateToken: vi.fn().mockResolvedValue(false),
      hasRealmRole: vi.fn().mockReturnValue(false),
      loadUserProfile: vi.fn().mockResolvedValue({})
    };
  }
  afterEach(() => {
    window.history.replaceState({}, '', '/');
    vi.clearAllTimers();
    vi.useRealTimers();
  });
  it.each([
    '/pay', '/pay/', '/pay/payment-token', '/pay/group/invoice-token',
    '/pay/success', '/pay/fail', '/%70ay/payment-token',
    '/pay/payment-token;source=message'
  ])('opens %s without an SSO request or redirect', async (pathname) => {
    window.history.replaceState({}, '', pathname);
    const sdk = createSdk();
    const auth = new AuthService(sdk as unknown as Keycloak);
    await auth.init();
    expect(auth.status()).toBe('anonymous');
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.getOptionalToken()).toBeNull();
    expect(sdk.init).toHaveBeenCalledWith(expect.objectContaining({
      onLoad: undefined, silentCheckSsoRedirectUri: undefined,
      checkLoginIframe: false, silentCheckSsoFallback: false
    }));
    expect(sdk.login).not.toHaveBeenCalled();
    expect(sdk.updateToken).not.toHaveBeenCalled();
    expect(sdk.loadUserProfile).not.toHaveBeenCalled();
    await auth.init();
    expect(sdk.init).toHaveBeenCalledTimes(1);
  });
  it('retains explicit sign-in after opening an anonymous payment', async () => {
    window.history.replaceState({}, '', '/pay/payment-token');
    const sdk = createSdk();
    const auth = new AuthService(sdk as unknown as Keycloak);
    await auth.init();
    await auth.login('/admin/dictionaries');
    expect(sdk.login).toHaveBeenCalledWith({
      redirectUri: window.location.origin + '/admin/dictionaries'
    });
  });
  it('keeps the existing SSO behavior for account pages', async () => {
    window.history.replaceState({}, '', '/admin/dictionaries');
    const sdk = createSdk();
    sdk.init.mockResolvedValue(false);
    const auth = new AuthService(sdk as unknown as Keycloak);
    await auth.init();
    expect(sdk.init).toHaveBeenCalledWith(expect.objectContaining({
      onLoad: 'check-sso',
      silentCheckSsoRedirectUri: window.location.origin + '/silent-check-sso.html',
      silentCheckSsoFallback: true
    }));
  });
  it('processes an explicit login callback on a payment route', async () => {
    vi.useFakeTimers();
    window.history.replaceState({}, '', '/pay/payment-token#state=login-state&code=login-code');
    const sdk = createSdk();
    sdk.authenticated = true;
    sdk.init.mockResolvedValue(true);
    const auth = new AuthService(sdk as unknown as Keycloak);
    await auth.init();
    expect(auth.status()).toBe('authenticated');
    expect(sdk.loadUserProfile).toHaveBeenCalledTimes(1);
    await auth.logout();
  });
  it.each([
    '/', '/admin/payments', '/payment', '/payroll/token',
    '/pay/group/token/admin', '/pay/token/extra', '/pay%2fgroup/token',
    '/pay/%', '/pay/..', '/pay/../admin'
  ])('does not classify an unrelated or malformed path as checkout: %s', (pathname) => {
    expect(isPublicPaymentPath(pathname)).toBe(false);
  });
});
