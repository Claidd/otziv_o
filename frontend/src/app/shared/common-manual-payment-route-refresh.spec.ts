import { commonManualPaymentDraftAfterRouteRefresh } from './common-manual-payment-route-refresh';

describe('common manual payment stale route refresh', () => {
  it('preserves only reason and receipt while resetting rows and both acknowledgements', () => {
    const refreshed = commonManualPaymentDraftAfterRouteRefresh({
      remainingKopecks: 250_000,
      defaultRecipientKey: 'TASK:12:4',
      candidates: [{ key: 'OWNER:' }, { key: 'TASK:12:4' }]
    }, 'клиент попросил номер', 'https://example.test/receipt', () => 'new-row');

    expect(refreshed).toEqual({
      rows: [{ rowKey: 'new-row', recipientKey: 'TASK:12:4', amountRubles: '2500,00' }],
      reason: 'клиент попросил номер',
      receiptUrl: 'https://example.test/receipt',
      paymentReceived: false,
      finalAcknowledged: false
    });
  });

  it('fails closed when the refreshed default recipient is absent', () => {
    expect(() => commonManualPaymentDraftAfterRouteRefresh({
      remainingKopecks: 100,
      defaultRecipientKey: 'TASK:missing',
      candidates: [{ key: 'OWNER:' }]
    }, 'reason', '', () => 'row')).toThrow(/безопасном списке/);
  });
});
