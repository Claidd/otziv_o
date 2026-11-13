export interface MobileCommonManualPaymentRouteRefreshOptions {
  remainingKopecks: number;
  defaultRecipientKey: string;
  candidates: readonly { key: string }[];
}

export function mobileCommonManualPaymentDraftAfterRouteRefresh(
  options: MobileCommonManualPaymentRouteRefreshOptions,
  reason: string,
  newRowKey: () => string
): {
  rows: Array<{ rowKey: string; recipientKey: string; amountRubles: string }>;
  reason: string;
  paymentReceived: false;
  finalAcknowledged: false;
} {
  return {
    rows: options.remainingKopecks > 0 ? [{
      rowKey: newRowKey(),
      recipientKey: '',
      amountRubles: (options.remainingKopecks / 100).toFixed(2).replace('.', ',')
    }] : [],
    reason,
    paymentReceived: false,
    finalAcknowledged: false
  };
}
