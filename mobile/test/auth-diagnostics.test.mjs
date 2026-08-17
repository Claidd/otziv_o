import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';

const diagnostics = readFileSync(new URL('../src/app/core/mobile-auth-diagnostics.service.ts', import.meta.url), 'utf8');
const auth = readFileSync(new URL('../src/app/core/auth.service.ts', import.meta.url), 'utf8');
const header = readFileSync(new URL('../src/app/shared/mobile-header.component.ts', import.meta.url), 'utf8');
const confirmService = readFileSync(new URL('../src/app/shared/mobile-confirm.service.ts', import.meta.url), 'utf8');
const confirmHost = readFileSync(new URL('../src/app/shared/mobile-confirm-host.component.ts', import.meta.url), 'utf8');
const home = readFileSync(new URL('../src/app/features/home.page.ts', import.meta.url), 'utf8');
const profile = readFileSync(new URL('../src/app/features/profile.page.ts', import.meta.url), 'utf8');
const appDiagnosticsPlugin = readFileSync(new URL('../src/app/core/app-diagnostics.plugin.ts', import.meta.url), 'utf8');
const telemetryInterceptor = readFileSync(new URL('../src/app/core/mobile-telemetry.interceptor.ts', import.meta.url), 'utf8');
const externalLinks = readFileSync(new URL('../src/app/shared/mobile-external-link.service.ts', import.meta.url), 'utf8');
const androidDiagnostics = readFileSync(
  new URL('../android/app/src/main/java/com/hunt/otziv/AppDiagnosticsPlugin.java', import.meta.url),
  'utf8'
);
const mainActivity = readFileSync(
  new URL('../android/app/src/main/java/com/hunt/otziv/MainActivity.java', import.meta.url),
  'utf8'
);
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
  assert.match(header, /this\.auth\.logoutFrom\('header_menu',/);
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
  assert.match(header, /confirmDelayMs: HEADER_LOGOUT_CONFIRM_GUARD_MS/);
  assert.match(confirmService, /result && !this\.confirmArmed\(\)/);
  assert.match(confirmHost, /\[disabled\]="!confirm\.confirmArmed\(\)"/);

  const start = header.indexOf('async logout(event: MouseEvent): Promise<void>');
  const logout = header.slice(start, header.indexOf('\n  }', start) + 4);
  assert.ok(logout.indexOf('await this.confirm.confirm') < logout.indexOf("await this.auth.logoutFrom('header_menu',"));
  assert.match(logout, /if \(!confirmed\) \{\s*return;/);
});

test('header logout records tap provenance and rejects an immediate or duplicate trigger', () => {
  assert.match(header, /HEADER_LOGOUT_MENU_GUARD_MS = 600/);
  assert.match(header, /menuOpenDurationMs < HEADER_LOGOUT_MENU_GUARD_MS/);
  assert.match(header, /'ui\.logout_trigger_ignored'/);
  assert.match(header, /'ui\.logout_prompt_opened'/);
  assert.match(header, /'ui\.logout_prompt_result'/);
  assert.match(header, /pointerType/);
  assert.match(header, /isTrusted/);
  assert.match(auth, /diagnosticDetails: Record<string, MobileAuthDiagnosticValue>/);
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

test('Android exit reasons are captured, deduplicated and attached to a bounded process summary', () => {
  assert.match(mainActivity, /registerPlugin\(AppDiagnosticsPlugin\.class\)/);
  assert.match(androidDiagnostics, /getHistoricalProcessExitReasons/);
  assert.match(androidDiagnostics, /getProcessStateSummary/);
  assert.match(androidDiagnostics, /setProcessStateSummary/);
  assert.match(androidDiagnostics, /MAX_PROCESS_STATE_BYTES = 128/);
  assert.match(androidDiagnostics, /KEY_ACKNOWLEDGED_EXIT_TIMESTAMP/);
  assert.match(androidDiagnostics, /REASON_LOW_MEMORY/);
  assert.match(androidDiagnostics, /REASON_CRASH_NATIVE/);
  assert.match(androidDiagnostics, /REASON_ANR/);
  assert.match(androidDiagnostics, /isOtzivProcessStateSummary/);
  assert.match(androidDiagnostics, /androidStateSummaryRejected/);
  assert.match(appDiagnosticsPlugin, /getPreviousExits/);
  assert.match(diagnostics, /'app\.previous_exit'/);
  assert.match(diagnostics, /androidStateSummaryRejected/);
  assert.match(diagnostics, /acknowledgePreviousExits/);
});

test('last-state breadcrumbs omit query values and dynamic resource identifiers', () => {
  assert.match(diagnostics, /split\(\/\[\?\#\]\//);
  assert.match(diagnostics, /replace\(\/\\\/\\d\+\(\?=\\\/\|\$\)\/gu, '\/:id'\)/);
  assert.match(telemetryInterceptor, /diagnosticRequestPath/);
  assert.match(telemetryInterceptor, /http\.\$\{request\.method\.toLowerCase\(\)\}/);
  assert.match(externalLinks, /diagnostics\.checkpoint\(`external\.https:/);
  assert.doesNotMatch(externalLinks, /checkpoint\(`external\.https:\$\{target\}/);
});

test('JavaScript errors and unhandled rejections enter the persistent diagnostic buffer', () => {
  assert.match(diagnostics, /window\.addEventListener\('error'/);
  assert.match(diagnostics, /'runtime\.javascript_error'/);
  assert.match(diagnostics, /window\.addEventListener\('unhandledrejection'/);
  assert.match(diagnostics, /'runtime\.unhandled_rejection'/);
  assert.match(diagnostics, /route: this\.currentRoute/);
  assert.match(diagnostics, /sanitizeRuntimeMessage/);
  assert.match(diagnostics, /redacted-jwt/);
  assert.match(diagnostics, /access_token\|refresh_token\|id_token/);
});
