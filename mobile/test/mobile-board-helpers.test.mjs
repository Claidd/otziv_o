import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  mobilePageIndex,
  mobilePageIsFirst,
  mobilePageIsLast,
  mobilePageLabel,
  mobilePageTotal,
  mobileSortTitle,
  mobileToneFromClass
} = loadTsModule('src/app/shared/mobile-board.helpers.ts');

test('normalizes mobile page labels across backend page shapes', () => {
  assert.equal(mobilePageIndex({ number: 0, totalPages: 3 }, 7), 1);
  assert.equal(mobilePageIndex({ pageNumber: 2, totalPages: 3 }, 0), 3);
  assert.equal(mobilePageTotal({ totalPages: 0 }), 1);
  assert.equal(mobilePageLabel({ pageNumber: 1, totalPages: 4 }, 0), '2 / 4');
});

test('keeps mobile page boundary checks consistent', () => {
  assert.equal(mobilePageIsFirst({ first: true }, 3), true);
  assert.equal(mobilePageIsFirst({}, 0), true);
  assert.equal(mobilePageIsLast({ last: true }, 0), true);
  assert.equal(mobilePageIsLast({ pageNumber: 2, totalPages: 3 }, 0), true);
  assert.equal(mobilePageIsLast({ pageNumber: 1, totalPages: 3 }, 0), false);
});

test('maps shared board labels and tones', () => {
  assert.equal(mobileSortTitle('desc'), 'Сначала новые');
  assert.equal(mobileSortTitle('asc', { desc: 'старые', asc: 'новые' }), 'новые');
  assert.equal(mobileToneFromClass('tone-green'), 'green');
  assert.equal(mobileToneFromClass('unknown'), 'blue');
  assert.equal(mobileToneFromClass('unknown', 'gray'), 'gray');
});
