import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (relativeUrl) => readFileSync(new URL(relativeUrl, import.meta.url), 'utf8');

const interceptor = read('../src/app/core/auth.interceptor.ts');
const api = read('../src/app/core/api.service.ts');
const routes = read('../src/app/app.routes.ts');
const tokenCapture = read('../src/app/core/review-capability-token.ts');
const appConfig = read('../src/app/app.config.ts');
const reviewCheckPage = read('../src/app/features/review-check.page.ts');

test('opaque review capability is a public API and never waits for mobile auth refresh', () => {
  assert.match(interceptor, /requestPath === '\/api\/review-capability'/);
  assert.match(interceptor, /requestPath\.startsWith\('\/api\/review-capability\/'\)/);
});

test('opaque review token stays out of API URL and is sent in the capability header', () => {
  assert.match(api, /this\.apiUrl\(`\/api\/review-capability\$\{suffix\}`\)/);
  assert.match(api, /OPAQUE_REVIEW_CAPABILITY\.test\(capabilityToken\)/);
  assert.match(api, /'X-Review-Capability': capabilityToken/);
  assert.doesNotMatch(api, /api\/review-capability\/\$\{capabilityToken\}/);
});

test('public mobile route reads opaque tokens from a URL fragment contract', () => {
  assert.match(routes, /path: 'review\/c'/);
  assert.match(appConfig, /captureReviewCapabilityToken\(\)/);
  assert.match(tokenCapture, /window\.history\.replaceState/);
  assert.match(tokenCapture, /\^rc1_\[A-Za-z0-9_\-\]\{43\}\$/);
});

test('only the validated captured token can enter the capability header', () => {
  assert.match(reviewCheckPage, /capabilityRoute\s*\? reviewCapabilityToken\(\)\s*:\s*null/);
  assert.doesNotMatch(reviewCheckPage, /snapshot\??\.fragment/i);
});
