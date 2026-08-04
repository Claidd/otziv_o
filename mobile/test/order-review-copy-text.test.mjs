import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { orderReviewCopyText } = loadTsModule('src/app/shared/order-review-copy-text.ts');

test('builds a labelled review message with a visible raw URL', () => {
  const url = 'https://o-ogo.ru/11111111-1111-1111-1111-111111111111';
  const text = orderReviewCopyText({
    companyTitle: 'Сервисный центр',
    filialTitle: 'Улица Льва Толстого, 23',
    firstOrderForCompany: true
  }, url);

  assert.equal(text, [
    'Сервисный центр - Улица Льва Толстого, 23',
    '',
    'Здравствуйте, это новые тексты на проверку. Проверьте, пожалуйста, их в течение трёх дней.',
    '',
    'Перейти к проверке отзывов:',
    url
  ].join('\n'));
  assert.notEqual(text, url);
});

test('uses the repeat-order explanation and omits an empty link action', () => {
  assert.equal(
    orderReviewCopyText({ companyTitle: 'Компания', firstOrderForCompany: false }, '  '),
    'Компания\n\nЗдравствуйте, текст отзывов для новых отзывов на следующий месяц готов.'
  );
});

test('all mobile review-copy routes use the labelled message helper', () => {
  const routes = [
    '../src/app/features/manager.page.ts',
    '../src/app/features/common-billing.page.ts',
    '../src/app/features/common-billing-admin.page.ts'
  ];

  for (const route of routes) {
    const source = fs.readFileSync(new URL(route, import.meta.url), 'utf8');
    assert.match(source, /orderReviewCopyText\(order, (?:reviewUrl|this\.orderReviewUrl\(order\))\)/u);
  }

  const card = fs.readFileSync(new URL('../src/app/shared/mobile-order-card.component.ts', import.meta.url), 'utf8');
  assert.match(card, />перейти<\/a>/u);
  assert.doesNotMatch(card, />url<\/a>/u);
});
