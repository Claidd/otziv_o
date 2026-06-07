import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  COMPANY_ACTIONS,
  DEFAULT_COMPANY_STATUSES,
  DEFAULT_ORDER_STATUSES,
  ORDER_ACTIONS,
  ensureAllStatus,
  managerOrderActionsFor,
  managerStatusLabel,
  normalizedManagerStatus
} = loadTsModule('src/app/features/manager/manager-board.helpers.ts');

test('normalizes legacy manager statuses for mobile filters', () => {
  assert.equal(normalizedManagerStatus('Рабочие'), 'Все');
  assert.equal(normalizedManagerStatus(''), 'Все');
  assert.equal(managerStatusLabel('Новый заказ'), 'новый заказ');
});

test('keeps all status first and removes duplicates', () => {
  assert.equal(
    JSON.stringify(ensureAllStatus(['Рабочие', 'Новая', 'Новая', 'Бан'])),
    JSON.stringify(['Все', 'Новая', 'Бан'])
  );
});

test('keeps manager default status and action sets stable', () => {
  assert.equal(DEFAULT_COMPANY_STATUSES.includes('Новый заказ'), true);
  assert.equal(DEFAULT_ORDER_STATUSES.includes('Не оплачено'), true);
  assert.equal(COMPANY_ACTIONS.some((action) => action.status === 'К рассылке'), true);
  assert.equal(ORDER_ACTIONS.some((action) => action.status === 'Оплачено'), true);
});

test('matches web manager bad-review order action flow', () => {
  assert.equal(
    JSON.stringify(managerOrderActionsFor({ status: 'На проверке' }).map((action) => action.status)),
    JSON.stringify(['Коррекция', 'Архив', 'Публикация'])
  );
  assert.equal(
    JSON.stringify(managerOrderActionsFor({ status: 'Выставлен счет' }).map((action) => action.status)),
    JSON.stringify(['Напоминание', 'Не оплачено', 'Оплачено'])
  );
  assert.equal(
    JSON.stringify(managerOrderActionsFor({ status: 'Не оплачено', badReviewTasksTotal: 2, badReviewTasksPending: 1 }).map((action) => action.status)),
    JSON.stringify(['Оплачено'])
  );
  assert.equal(
    JSON.stringify(managerOrderActionsFor({ status: 'Не оплачено', badReviewTasksTotal: 2, badReviewTasksPending: 1 }, false, true).map((action) => action.status)),
    JSON.stringify(['Оплачено', 'Бан'])
  );
  assert.equal(
    JSON.stringify(managerOrderActionsFor({ status: 'Не оплачено', badReviewTasksTotal: 2, badReviewTasksPending: 0 }).map((action) => action.status)),
    JSON.stringify(['Оплачено', 'Бан'])
  );
  assert.equal(managerOrderActionsFor({ status: 'Новый' }, true).some((action) => action.status === 'Бан'), false);
});
