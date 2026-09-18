import { describe, it, expect } from 'vitest';
import { accountCredentialsCopied, recordAccountCredentialCopy } from '@otziv/client-common/account-credential-copy';

describe('account credential copy before block', () => {
  const review = { id: 197623, botId: 871819 };

  it('requires both fields and preserves evidence while working with other cards', () => {
    let state = recordAccountCredentialCopy({}, review, 'login');
    expect(accountCredentialsCopied(state, review)).toBe(false);
    state = recordAccountCredentialCopy(state, review, 'password');
    state = recordAccountCredentialCopy(state, { id: 2, botId: 3 }, 'login');
    expect(accountCredentialsCopied(state, review)).toBe(true);
  });

  it('does not reuse copies for another account or a task with the same numeric id', () => {
    let state = recordAccountCredentialCopy({}, review, 'login');
    state = recordAccountCredentialCopy(state, review, 'password');
    expect(accountCredentialsCopied(state, { ...review, botId: 99 })).toBe(false);
    expect(accountCredentialsCopied(state, { ...review, recoveryTaskId: review.id })).toBe(false);
    expect(accountCredentialsCopied(state, { ...review, badTaskId: review.id })).toBe(false);
  });
});
