import {
  canCloseManualPaymentAsUnpaid,
  manualPaymentUnpaidCloseConfirmation
} from './manual-payment-unpaid-close';

describe('manual payment unpaid close policy', () => {
  const waitingManual = {
    orderId: 24175,
    amount: 1000,
    status: 'WAITING_MANUAL_PAYMENT',
    paymentMethod: 'MANUAL_MOBILE_BANK'
  };

  it('allows only a pending manual instruction without paid evidence', () => {
    expect(canCloseManualPaymentAsUnpaid(waitingManual)).toBe(true);
    expect(canCloseManualPaymentAsUnpaid({ ...waitingManual, status: 'MANUAL_REPORTED' })).toBe(true);
    expect(canCloseManualPaymentAsUnpaid({ ...waitingManual, status: 'CONFIRMED' })).toBe(false);
    expect(canCloseManualPaymentAsUnpaid({ ...waitingManual, confirmedAmountKopecks: 100000 })).toBe(false);
    expect(canCloseManualPaymentAsUnpaid({ ...waitingManual, tbankPaymentId: '123' })).toBe(false);
    expect(canCloseManualPaymentAsUnpaid({ ...waitingManual, paymentMethod: 'BANK_FORM' })).toBe(false);
  });

  it('states that the action changes neither paid nor unpaid order state', () => {
    const warning = manualPaymentUnpaidCloseConfirmation(waitingManual);

    expect(warning).toContain('НЕ поступил');
    expect(warning).toContain('НЕ будет отмечен оплаченным');
    expect(warning).toContain('НЕ перейдет в статус «Не оплачено»');
  });
});
