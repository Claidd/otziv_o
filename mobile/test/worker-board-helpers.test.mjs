import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  DEFAULT_WORKER_SECTION,
  ORDER_STATUS_ACTIONS,
  WORKER_SECTIONS,
  isWorkerReviewSection,
  workerReviewTitle,
  workerReviewToneClass,
  workerSectionIcon,
  workerSectionLabel
} = loadTsModule('src/app/features/worker/worker-board.helpers.ts');

test('keeps worker section navigation stable', () => {
  assert.equal(DEFAULT_WORKER_SECTION, 'new');
  assert.equal(JSON.stringify(WORKER_SECTIONS), JSON.stringify(['new', 'correct', 'nagul', 'recovery', 'publish', 'bad', 'all']));
  assert.equal(workerSectionLabel('nagul'), 'Выгул');
  assert.equal(workerSectionIcon('publish'), 'published_with_changes');
});

test('separates order and review worker sections', () => {
  assert.equal(isWorkerReviewSection('new'), false);
  assert.equal(isWorkerReviewSection('nagul'), true);
  assert.equal(isWorkerReviewSection('bad'), true);
});

test('keeps worker order status actions available', () => {
  assert.equal(ORDER_STATUS_ACTIONS.some((action) => action.status === 'На проверке'), true);
  assert.equal(ORDER_STATUS_ACTIONS.some((action) => action.status === 'Оплачено'), true);
});

test('builds review card title and tone from section context', () => {
  const review = {
    companyTitle: 'Iquest',
    filialTitle: 'Филиал 2',
    badTask: false,
    recoveryTask: true
  };

  assert.equal(workerReviewTitle(review, 'nagul'), 'Iquest - Филиал 2');
  assert.equal(workerReviewTitle(review, 'bad'), 'Iquest');
  assert.equal(workerReviewToneClass(review, 'new'), 'tone-recovery');
  assert.equal(workerReviewToneClass({ badTask: true }, 'new'), 'tone-bad');
});
