import { describe, expect, it } from 'vitest';
import { isIncompletePartiallyPaidInvoice } from './common-billing.component';

describe('isIncompletePartiallyPaidInvoice', () => {
  it('recognizes a partially paid invoice that is still being collected', () => {
    expect(isIncompletePartiallyPaidInvoice({
      status: 'PARTIALLY_PAID',
      readyOrders: 7,
      totalOrders: 8
    })).toBe(true);
  });

  it('does not hide the warning after every order is ready', () => {
    expect(isIncompletePartiallyPaidInvoice({
      status: 'PARTIALLY_PAID',
      readyOrders: 8,
      totalOrders: 8
    })).toBe(false);
  });

  it('does not affect other invoice statuses', () => {
    expect(isIncompletePartiallyPaidInvoice({
      status: 'INVOICED',
      readyOrders: 7,
      totalOrders: 8
    })).toBe(false);
  });
});
