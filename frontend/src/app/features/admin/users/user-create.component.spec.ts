import { describe, expect, it } from 'vitest';
import { generateTemporaryPassword } from './user-create.component';

describe('generateTemporaryPassword', () => {
  it('meets the realm policy and mixes upper case, lower case and digits', () => {
    for (let attempt = 0; attempt < 50; attempt += 1) {
      const password = generateTemporaryPassword();
      expect(password).toHaveLength(14);
      expect(password).toMatch(/[A-Z]/);
      expect(password).toMatch(/[a-z]/);
      expect(password).toMatch(/[2-9]/);
      expect(password).toMatch(/^[A-HJ-NP-Za-km-z2-9]+$/);
    }
  });

  it('gives every new account its own password', () => {
    const passwords = new Set(Array.from({ length: 200 }, () => generateTemporaryPassword()));
    expect(passwords.size).toBe(200);
  });
});
