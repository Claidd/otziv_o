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

/**
 * Recipient chosen when the dialog first opens: the person or task named in the invoice,
 * when it is still in the safe list. After a route change nothing is preselected
 * (see commonManualPaymentDraftAfterRouteRefresh), and a bank-link invoice has no such
 * candidate at all, so those cases keep an explicit choice.
 */
export function commonManualPaymentInitialRecipientKey(options: {
  defaultRecipientKey: string;
  candidates: readonly { key: string; originalRecipient?: boolean }[];
}): string {
  const original = options.candidates.filter(candidate => candidate.originalRecipient);
  const named = original.find(candidate => candidate.key === options.defaultRecipientKey);
  return (named ?? (original.length === 1 ? original[0] : undefined))?.key ?? '';
}
