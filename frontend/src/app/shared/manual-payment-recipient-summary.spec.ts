import {
  manualPaymentAccountingDestinationLabel,
  manualPaymentAccountingRecipientLabel,
  manualPaymentAccountingSourceLabel
} from './manual-payment-recipient-summary';

describe('manual payment recipient summary', () => {
  it('renders the immutable accounting recipient instead of bank-facing requisites', () => {
    const item = {
      manualRecipientName: 'Старое имя в банке',
      accountingRecipientLabel: 'Менеджер Анна',
      accountingDestinationKind: 'CONTRACTOR_PROFILE',
      accountingRecipientType: 'MANAGER',
      accountingRecipientProfileId: 42,
      attributionKnown: true
    };

    expect(manualPaymentAccountingRecipientLabel(item)).toBe('Менеджер Анна');
    expect(manualPaymentAccountingDestinationLabel(item)).toBe('Профиль менеджера · профиль №42');
    expect(manualPaymentAccountingSourceLabel(item)).toBe('По фактическому получателю оплаты');
  });

  it('marks legacy rows as unknown and never presents old requisites as fact', () => {
    const item = {
      manualRecipientName: 'Получатель не указан (старые оплаты без атрибуции)',
      attributionKnown: false
    };

    expect(manualPaymentAccountingDestinationLabel(item)).toContain('Нет точной атрибуции');
    expect(manualPaymentAccountingSourceLabel(item)).toContain('без данных о получателе');
  });

  it('describes a task destination by task identity and generation', () => {
    const item = {
      accountingDestinationKind: 'MANUAL_PAYMENT_TASK',
      manualPaymentTaskId: 17,
      manualPaymentTaskGeneration: 3,
      manualPaymentTaskTargetKind: 'EXTERNAL_TASK',
      attributionKnown: true
    };

    expect(manualPaymentAccountingDestinationLabel(item))
      .toBe('Внешний получатель · задание №17 · версия 3');
  });
});
