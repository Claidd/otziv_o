import {
  commonManualPaymentDraftAfterRouteRefresh,
  commonManualPaymentInitialRecipientKey
} from './common-manual-payment-route-refresh';

describe('common manual payment stale route refresh', () => {
  it('preserves the reason while resetting rows and both acknowledgements', () => {
    const refreshed = commonManualPaymentDraftAfterRouteRefresh({
      remainingKopecks: 250_000,
      defaultRecipientKey: 'TASK:12:4',
      candidates: [{ key: 'OWNER:' }, { key: 'TASK:12:4' }]
    }, 'клиент попросил номер', () => 'new-row');

    expect(refreshed).toEqual({
      rows: [{ rowKey: 'new-row', recipientKey: '', amountRubles: '2500,00' }],
      reason: 'клиент попросил номер',
      paymentReceived: false,
      finalAcknowledged: false
    });
  });

  it('requires an explicit choice when the issued recipient is unavailable', () => {
    expect(commonManualPaymentDraftAfterRouteRefresh({
      remainingKopecks: 100,
      defaultRecipientKey: 'TASK:missing',
      candidates: [{ key: 'OWNER:' }]
    }, 'reason', () => 'row').rows[0].recipientKey).toBe('');
  });
});

describe('common manual payment initial recipient', () => {
  it('preselects the recipient named in the invoice when it can be chosen', () => {
    expect(commonManualPaymentInitialRecipientKey({
      defaultRecipientKey: 'PROFILE:7',
      candidates: [{ key: 'OWNER' }, { key: 'PROFILE:7', originalRecipient: true }, { key: 'PROFILE:9' }]
    })).toBe('PROFILE:7');
  });

  it('keeps an explicit choice for a bank-link invoice, where the owner is not offered', () => {
    expect(commonManualPaymentInitialRecipientKey({
      defaultRecipientKey: 'OWNER',
      candidates: [{ key: 'PROFILE:7' }, { key: 'PROFILE:9' }]
    })).toBe('');
  });

  it('does not guess between several invoice recipients', () => {
    expect(commonManualPaymentInitialRecipientKey({
      defaultRecipientKey: 'missing',
      candidates: [{ key: 'TASK:1:1', originalRecipient: true }, { key: 'TASK:2:1', originalRecipient: true }]
    })).toBe('');
  });
});
