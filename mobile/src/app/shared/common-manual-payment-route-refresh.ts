export interface MobileCommonManualPaymentRouteRefreshOptions {
  remainingKopecks: number;
  defaultRecipientKey: string;
  candidates: readonly { key: string }[];
}

export function mobileCommonManualPaymentDraftAfterRouteRefresh(
  options: MobileCommonManualPaymentRouteRefreshOptions,
  reason: string,
  receiptUrl: string,
  newRowKey: () => string
): {
  rows: Array<{ rowKey: string; recipientKey: string; amountRubles: string }>;
  reason: string;
  receiptUrl: string;
  paymentReceived: false;
  finalAcknowledged: false;
} {
  const defaultRecipient = options.candidates.find(candidate => candidate.key === options.defaultRecipientKey);
  if (options.remainingKopecks > 0 && !defaultRecipient) {
    throw new Error('Исходный получатель отсутствует в безопасном списке. Оплата не изменена.');
  }
  return {
    rows: defaultRecipient && options.remainingKopecks > 0 ? [{
      rowKey: newRowKey(),
      recipientKey: defaultRecipient.key,
      amountRubles: (options.remainingKopecks / 100).toFixed(2).replace('.', ',')
    }] : [],
    reason,
    receiptUrl,
    paymentReceived: false,
    finalAcknowledged: false
  };
}
