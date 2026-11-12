import type { ManualCardPaymentContext, ManualCardPaymentRecipientOption } from '../core/api.service';
import { mobileManualCardBankLinkNotice } from './mobile-manual-card-payment';

describe('bank link notice of the payment mark dialog', () => {
  const owner = { key: 'OWNER', recipientType: 'OWNER', recipientProfileId: null, displayName: 'Владелец' } as ManualCardPaymentRecipientOption;
  const specialist = { key: 'PROFILE:25', recipientType: 'SPECIALIST', recipientProfileId: 25, displayName: 'Наталья С.' } as ManualCardPaymentRecipientOption;
  const context = (patch: Partial<ManualCardPaymentContext>): ManualCardPaymentContext => ({
    orderId: 1, amountKopecks: 125_000, originalRecipient: owner, candidates: [specialist],
    recipientSelectionFrozen: false, preparedRecipient: null, preparedReason: null, preparedReceiptUrl: null, ...patch
  });

  it('says nothing for an invoice with a card number and for an older server', () => {
    expect(mobileManualCardBankLinkNotice(context({ originalRecipient: specialist, bankLinkRoute: false }))).toBe('');
    expect(mobileManualCardBankLinkNotice(context({}))).toBe('');
  });

  it('tells how to report a transfer to the owner where the owner is offered', () => {
    const notice = mobileManualCardBankLinkNotice(context({ bankLinkRoute: true, candidates: [specialist, owner] }));
    expect(notice).toContain('Оплату по ссылке банк отмечает сам');
    expect(notice).toContain('выберите «Владелец»');
    expect(notice).toContain('только после его подтверждения');
  });

  it('explains the missing owner where the owner takes money through the bank only', () => {
    const notice = mobileManualCardBankLinkNotice(context({ bankLinkRoute: true }));
    expect(notice).toContain('на карту этот владелец оплату не принимает');
    expect(notice).not.toContain('выберите «Владелец»');
  });

  it('keeps silent while an earlier, already fixed confirmation is repeated', () => {
    expect(mobileManualCardBankLinkNotice(context({ bankLinkRoute: true, recipientSelectionFrozen: true, preparedRecipient: specialist }))).toBe('');
  });
});
