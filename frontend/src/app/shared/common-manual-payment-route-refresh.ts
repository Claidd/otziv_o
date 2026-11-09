export interface CommonManualPaymentRouteRefreshOptions {
  remainingKopecks: number;
  defaultRecipientKey: string;
  candidates: readonly { key: string }[];
}

export interface CommonManualPaymentRouteRefreshDraftRow {
  rowKey: string;
  recipientKey: string;
  amountRubles: string;
}

export interface CommonManualPaymentRouteRefreshDraft {
  rows: CommonManualPaymentRouteRefreshDraftRow[];
  reason: string;
  receiptUrl: string;
  paymentReceived: false;
  finalAcknowledged: false;
}

export function commonManualPaymentDraftAfterRouteRefresh(
  options: CommonManualPaymentRouteRefreshOptions,
  reason: string,
  receiptUrl: string,
  newRowKey: () => string
): CommonManualPaymentRouteRefreshDraft {
  return {
    rows: options.remainingKopecks > 0 ? [{
      rowKey: newRowKey(),
      recipientKey: '',
      amountRubles: (options.remainingKopecks / 100).toFixed(2).replace('.', ',')
    }] : [],
    reason,
    receiptUrl,
    paymentReceived: false,
    finalAcknowledged: false
  };
}
