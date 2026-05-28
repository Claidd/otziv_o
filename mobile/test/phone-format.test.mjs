import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { displayPhone, normalizePhoneDigits, phoneHref } = loadTsModule('src/app/shared/phone-format.ts');

test('normalizes Russian phone numbers for copying and links', () => {
  assert.equal(normalizePhoneDigits('8 (924) 640-44-70'), '79246404470');
  assert.equal(normalizePhoneDigits('9246404470'), '79246404470');
  assert.equal(phoneHref('7-924-640-44-70'), 'tel:+79246404470');
});

test('formats card phone numbers consistently', () => {
  assert.equal(displayPhone('79246404470'), '7-924-640-44-70');
  assert.equal(displayPhone('8 924 640 44 70'), '7-924-640-44-70');
  assert.equal(displayPhone('', '-'), '-');
});
