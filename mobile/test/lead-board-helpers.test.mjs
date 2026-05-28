import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const {
  LEAD_BUCKETS,
  leadActionIcon,
  leadActionLabel,
  leadAddressLine,
  leadCategoryLine,
  leadContactLinks,
  leadStatusActions,
  leadTitle,
  leadTone,
  normalizedLeadPhone
} = loadTsModule('src/app/features/leads/lead-board.helpers.ts');

test('keeps lead buckets in the expected mobile order', () => {
  assert.equal(
    JSON.stringify(LEAD_BUCKETS.map((bucket) => bucket.key)),
    JSON.stringify(['newLeads', 'toWork', 'inWork', 'send', 'archive', 'all'])
  );
});

test('builds lead card display values from normalized data', () => {
  const lead = {
    id: 7,
    telephoneLead: '8 (924) 640-44-70',
    companyName: '  Iquest  ',
    lidStatus: 'В работе',
    companyType: 'Квесты',
    industries: 'Развлечения',
    region: 'Иркутская область',
    address: 'Ленина, 1'
  };

  assert.equal(leadTitle(lead), 'Iquest');
  assert.equal(leadCategoryLine(lead), 'Квесты • Развлечения');
  assert.equal(leadAddressLine(lead), 'Иркутская область • Ленина, 1');
  assert.equal(normalizedLeadPhone(lead), '79246404470');
  assert.equal(leadTone(lead), 'work');
});

test('normalizes lead social links for card buttons', () => {
  const links = leadContactLinks({
    telephoneLead: '79246404470',
    whatsappPhones: '',
    emails: 'info@example.test',
    websites: 'example.test',
    vkUrl: '@club',
    telegramUrl: '@otziv'
  });

  assert.equal(
    JSON.stringify(links.map((link) => [link.key, link.url])),
    JSON.stringify([
      ['wa', 'https://wa.me/79246404470'],
      ['email', 'mailto:info@example.test'],
      ['site', 'https://example.test'],
      ['vk', 'https://vk.com/club'],
      ['tg', 'https://t.me/otziv']
    ])
  );
});

test('maps lead actions without component state', () => {
  assert.equal(JSON.stringify(leadStatusActions({ lidStatus: 'Новый' }, true)), JSON.stringify(['send']));
  assert.equal(
    JSON.stringify(leadStatusActions({ lidStatus: 'Отправленный' }, true)),
    JSON.stringify(['toWork', 'resend', 'archive'])
  );
  assert.equal(
    JSON.stringify(leadStatusActions({ lidStatus: 'Отправленный' }, false)),
    JSON.stringify(['resend', 'archive'])
  );
  assert.equal(leadActionLabel('toWork'), 'передать');
  assert.equal(leadActionIcon('toWork'), 'swap_horiz');
});
