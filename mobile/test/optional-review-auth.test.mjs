import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = (relativeUrl) => readFileSync(new URL(relativeUrl, import.meta.url), 'utf8');

const interceptor = read('../src/app/core/auth.interceptor.ts');
const auth = read('../src/app/core/auth.service.ts');

test('public review uses only an already-fresh cached token', () => {
  const branchStart = interceptor.indexOf('if (isOptionalReviewApi)');
  const protectedStart = interceptor.indexOf('return from(auth.getAccessToken())', branchStart);
  const optionalBranch = interceptor.slice(branchStart, protectedStart);

  assert.ok(branchStart >= 0 && protectedStart > branchStart);
  assert.match(optionalBranch, /auth\.getOptionalAccessToken\(\)/);
  assert.match(optionalBranch, /Authorization: `Bearer \$\{cachedToken\}`/);
  assert.doesNotMatch(optionalBranch, /refreshTokens|getAccessToken|handleUnauthorized/);

  const methodStart = auth.indexOf('getOptionalAccessToken(');
  const methodEnd = auth.indexOf('async refreshTokens()', methodStart);
  const method = auth.slice(methodStart, methodEnd);
  assert.match(method, /isTokenFresh\(tokens, minValiditySeconds\)/);
  assert.doesNotMatch(method, /refreshTokens|requestToken/);
});

test('stale server-side review auth retries anonymously once without logout', () => {
  const branchStart = interceptor.indexOf('if (isOptionalReviewApi)');
  const protectedStart = interceptor.indexOf('return from(auth.getAccessToken())', branchStart);
  const optionalBranch = interceptor.slice(branchStart, protectedStart);

  assert.match(optionalBranch, /error\.status === 401/);
  assert.match(optionalBranch, /return next\(anonymousRequest\)/);
  assert.doesNotMatch(optionalBranch, /handleUnauthorized|refreshTokens/);
});

test('capability-header and public-payment APIs remain always anonymous', () => {
  assert.match(interceptor, /isAlwaysAnonymousApi = requestPath\.startsWith\('\/api\/payments\/public'\)/);
  assert.match(interceptor, /requestPath === '\/api\/review-capability'/);
  assert.match(interceptor, /headers: request\.headers\.delete\('Authorization'\)/);
  assert.match(interceptor, /return next\(isAlwaysAnonymousApi \? anonymousRequest : request\)/);
});
