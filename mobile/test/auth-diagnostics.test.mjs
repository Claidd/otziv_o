import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const diagnostics = readFileSync(new URL('../src/app/core/mobile-auth-diagnostics.service.ts', import.meta.url), 'utf8');
const auth = readFileSync(new URL('../src/app/core/auth.service.ts', import.meta.url), 'utf8');
const header = readFileSync(new URL('../src/app/shared/mobile-header.component.ts', import.meta.url), 'utf8');
const home = readFileSync(new URL('../src/app/features/home.page.ts', import.meta.url), 'utf8');
const profile = readFileSync(new URL('../src/app/features/profile.page.ts', import.meta.url), 'utf8');
const prodProperties = readFileSync(new URL('../../backend/src/main/resources/application-prod.properties', import.meta.url), 'utf8');

test('auth diagnostics survive process death in a bounded native ring buffer', () => {
  assert.match(diagnostics, /Preferences\.set\(\{\s*key: DIAGNOSTIC_BUFFER_KEY/);
  assert.match(diagnostics, /const MAX_BUFFERED_EVENTS = 160/);
  assert.match(diagnostics, /const MAX_EVENT_AGE_MS = 7 \* 24 \* 60 \* 60 \* 1000/);
  assert.match(diagnostics, /slice\(-MAX_BUFFERED_EVENTS\)/);
  assert.doesNotMatch(diagnostics, /localStorage|sessionStorage/);
});

test('diagnostics upload only after authentication and never persist raw tokens', () => {
  assert.match(diagnostics, /Authorization: `Bearer \$\{accessToken\}`/);
  assert.match(diagnostics, /\/api\/mobile\/auth-diagnostics/);
  assert.match(auth, /void this\.flushDiagnostics\(tokens\.accessToken\)/);
  assert.doesNotMatch(diagnostics, /refreshToken|^\s*accessToken:/m);
  assert.doesNotMatch(diagnostics, /codeVerifier|idToken|password/);
});

test('all logout buttons report an exact source before clearing the session', () => {
  assert.match(header, /this\.auth\.logoutFrom\('header_menu'\)/);
  assert.match(home, /this\.auth\.logoutFrom\('home_actions'\)/);
  assert.match(profile, /this\.auth\.logoutFrom\('profile'\)/);

  const start = auth.indexOf('async logout(');
  const end = auth.indexOf('private async revokeCurrentPushTokenBestEffort', start);
  const logout = auth.slice(start, end);
  assert.ok(logout.indexOf("recordAuthDiagnostic('auth.logout_requested'") < logout.indexOf('clearSession('));
  assert.match(logout, /source,/);
  assert.match(logout, /void this\.flushDiagnostics\(accessToken\)/);
});

test('header-menu logout requires an explicit destructive confirmation', () => {
  assert.match(header, /inject\(MobileConfirmService\)/);
  assert.match(header, /title: 'Выйти из приложения\?'/);
  assert.match(header, /confirmText: 'Выйти'/);
  assert.match(header, /cancelText: 'Остаться'/);
  assert.match(header, /danger: true/);

  const start = header.indexOf('async logout(): Promise<void>');
  const logout = header.slice(start, header.indexOf('\n  }', start) + 4);
  assert.ok(logout.indexOf('await this.confirm.confirm') < logout.indexOf("await this.auth.logoutFrom('header_menu')"));
  assert.match(logout, /if \(!confirmed\) \{\s*return;/);
});

test('app transitions, network changes and every auth-clearing branch are classified', () => {
  assert.match(diagnostics, /'app\.foreground'/);
  assert.match(diagnostics, /'app\.background'/);
  assert.match(diagnostics, /'network\.changed'/);
  assert.match(auth, /'auth\.refresh_transient_failure'/);
  assert.match(auth, /'auth\.refresh_terminal_failure'/);
  assert.match(auth, /'auth\.browser_closed'/);
  assert.match(auth, /'auth\.session_clear_started'/);
  assert.match(auth, /reason,/);
});

test('the dedicated production logger keeps diagnostic info events', () => {
  assert.match(prodProperties, /logging\.level\.MOBILE_AUTH_DIAGNOSTICS=.*INFO/);
});
