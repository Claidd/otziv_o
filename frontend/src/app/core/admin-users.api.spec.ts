import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AdminUsersApi, ContractorAccrualAdjustmentRequest } from './admin-users.api';

describe('AdminUsersApi accrual adjustments', () => {
  it('keeps preview, posting and reversal scoped to the same employee and profile', () => {
    const get = vi.fn().mockReturnValue(of([]));
    const post = vi.fn().mockReturnValue(of({}));
    const api = new AdminUsersApi({ get, post } as unknown as HttpClient);
    const request: ContractorAccrualAdjustmentRequest = {
      kind: 'DEDUCTION',
      amountKopecks: 125_50,
      reason: 'Исправление начисления',
      referenceMonth: '2026-09',
      idempotencyKey: 'one-transaction'
    };
    const base = '/api/admin/users/14/contractor-payment-profiles/27/accrual-adjustments';

    api.getContractorAccrualAdjustments(14, 27);
    api.getContractorAccrualAdjustmentsPage(14, 27, 2);
    api.previewContractorAccrualAdjustment(14, 27, request);
    api.createContractorAccrualAdjustment(14, 27, request);
    api.reverseContractorAccrualAdjustment(14, 27, 38, {
      reason: 'Отмена ошибочного удержания',
      idempotencyKey: 'reverse-one-transaction'
    });

    expect(get).toHaveBeenCalledWith(base);
    expect(get).toHaveBeenCalledWith(`${base}/pages?page=2`);
    expect(post).toHaveBeenNthCalledWith(1, `${base}/preview`, request);
    expect(post).toHaveBeenNthCalledWith(2, base, request);
    expect(post).toHaveBeenNthCalledWith(3, `${base}/38/reversals`, {
      reason: 'Отмена ошибочного удержания',
      idempotencyKey: 'reverse-one-transaction'
    });
  });
});
