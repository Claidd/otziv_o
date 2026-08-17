import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTsModule } from './load-ts-module.mjs';

const { manualTransferDestinationPresentation } = loadTsModule(
  'src/app/shared/manual-transfer-destination.ts'
);

test('shows card labels for a 16 to 19 digit transfer destination', () => {
  const card = manualTransferDestinationPresentation('2202 2082 3839 6676');
  assert.equal(card.kind, 'CARD');
  assert.equal(card.fieldLabel, 'Номер карты');
  assert.equal(card.copyLabel, 'Скопировать карту');
  assert.equal(card.paymentTitle, 'Оплата по номеру карты');
  assert.equal(manualTransferDestinationPresentation('1234-5678-9012-3456-789').kind, 'CARD');
  assert.equal(manualTransferDestinationPresentation('2202 (2082) 3839—6676').kind, 'CARD');
});

test('keeps phone labels for a phone or malformed destination', () => {
  const phone = manualTransferDestinationPresentation('+7 (999) 123-45-67');
  assert.equal(phone.kind, 'PHONE');
  assert.equal(phone.fieldLabel, 'Номер телефона');
  assert.equal(phone.copyLabel, 'Скопировать телефон');
  assert.equal(phone.paymentTitle, 'Оплата через мобильный банк');
  assert.equal(manualTransferDestinationPresentation('+1234567890123456').kind, 'PHONE');
});
