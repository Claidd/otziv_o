import type {
  ManualCardPaymentContext,
  ManualCardPaymentRecipientOption
} from '../../core/manager.api';
import {
  manualCardRecipientKey,
  manualCardPaymentSelectionRecipient,
  manualCardPaymentSubmission,
  originalManualCardRecipient
} from './manager-manual-card-payment-modal.component';

describe('manager manual card payment recipient selection', () => {
  const owner: ManualCardPaymentRecipientOption = {
    recipientType: 'OWNER',
    recipientProfileId: null,
    recipientUserId: 1,
    displayName: 'Владелец',
    availableKopecks: 0,
    projectedOverrunKopecks: 0
  };
  const specialist: ManualCardPaymentRecipientOption = {
    recipientType: 'SPECIALIST',
    recipientProfileId: 25,
    recipientUserId: 5,
    displayName: 'Елена',
    availableKopecks: 8_580_00,
    projectedOverrunKopecks: 0
  };
  const context = (overrides: Partial<ManualCardPaymentContext> = {}): ManualCardPaymentContext => ({
    orderId: 123,
    amountKopecks: 1_000_00,
    originalRecipient: specialist,
    candidates: [owner, specialist],
    anomalyWarning: null,
    recipientSelectionFrozen: false,
    preparedRecipient: null,
    preparedReason: null,
    preparedReceiptUrl: null,
    ...overrides
  });

  it('uses the explicit backend JSON field names', () => {
    const fixture = context();

    expect(fixture.originalRecipient.recipientType).toBe('SPECIALIST');
    expect(fixture.originalRecipient.recipientProfileId).toBe(25);
    expect(manualCardRecipientKey(fixture.originalRecipient)).toBe('SPECIALIST:25');
  });

  it('defaults only to the exact original recipient before a selection is frozen', () => {
    expect(originalManualCardRecipient([owner, specialist], specialist)).toBe(specialist);
    expect(manualCardPaymentSelectionRecipient(context())).toBe(specialist);
  });

  it('fails closed when the original recipient is absent', () => {
    expect(originalManualCardRecipient([owner], specialist)).toBeNull();
  });

  it('replays the exact prepared recipient and values when selection is frozen', () => {
    const frozen = context({
      recipientSelectionFrozen: true,
      preparedRecipient: owner,
      preparedReason: '  сохранённая причина  ',
      preparedReceiptUrl: null
    });

    expect(manualCardPaymentSelectionRecipient(frozen)).toBe(owner);
    expect(manualCardPaymentSubmission(frozen, specialist, 'другая причина', 'https://wrong.test')).toEqual({
      recipient: owner,
      reason: '  сохранённая причина  ',
      receiptUrl: null
    });
  });

  it('fails closed when the frozen prepared recipient is absent from candidates', () => {
    expect(manualCardPaymentSelectionRecipient(context({
      recipientSelectionFrozen: true,
      candidates: [owner],
      preparedRecipient: specialist,
      preparedReason: 'сохранено'
    }))).toBeNull();
  });
});
