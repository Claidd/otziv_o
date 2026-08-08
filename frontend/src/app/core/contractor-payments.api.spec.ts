import { HttpClient, HttpParams } from '@angular/common/http';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ContractorPaymentQueueHealth,
  ContractorPaymentQueueHealthItem,
  ContractorPaymentSystemStatus,
  ContractorPaymentsApi
} from './contractor-payments.api';

describe('ContractorPaymentsApi', () => {
  let get: ReturnType<typeof vi.fn>;
  let post: ReturnType<typeof vi.fn>;
  let put: ReturnType<typeof vi.fn>;
  let api: ContractorPaymentsApi;

  beforeEach(() => {
    get = vi.fn().mockReturnValue(of({}));
    post = vi.fn().mockReturnValue(of({}));
    put = vi.fn().mockReturnValue(of(undefined));
    api = new ContractorPaymentsApi({ get, post, put } as unknown as HttpClient);
  });

  it('loads the summary only for the authenticated user', () => {
    api.getMySummary();

    expect(get).toHaveBeenCalledWith('/api/contractor-payments/me');
    expect(get.mock.calls[0][0]).not.toContain('userId');
  });

  it('loads the global contractor-payment system status for an admin screen', () => {
    const status = systemStatus();
    get.mockReturnValue(of(status));

    let result: ContractorPaymentSystemStatus | undefined;
    api.getSystemStatus().subscribe((value) => {
      result = value;
    });

    expect(get).toHaveBeenCalledWith('/api/admin/contractor-payments/system');
    expect(result).toEqual(status);
  });

  it('activates completion accounting with an explicit revision and typed confirmation', () => {
    const request = {
      attributionStartDate: '2026-08-01',
      confirmation: 'ВКЛЮЧИТЬ НОВУЮ СИСТЕМУ',
      reason: 'Сверка тестового контура завершена',
      expectedRevision: 7
    };

    api.activateSystem(request);

    expect(post).toHaveBeenCalledWith(
      '/api/admin/contractor-payments/system/activate',
      request
    );
  });

  it('pauses and resumes only requisites routing without changing accounting mode', () => {
    api.updateSystemRouting({
      enabled: false,
      confirmation: 'ПРИОСТАНОВИТЬ РЕКВИЗИТЫ',
      reason: 'Плановая проверка поступлений',
      expectedRevision: 8
    });
    api.updateSystemRouting({
      enabled: true,
      confirmation: 'ВКЛЮЧИТЬ РЕКВИЗИТЫ',
      reason: 'Сверка завершена',
      expectedRevision: 9
    });

    expect(post).toHaveBeenNthCalledWith(
      1,
      '/api/admin/contractor-payments/system/routing',
      {
        enabled: false,
        confirmation: 'ПРИОСТАНОВИТЬ РЕКВИЗИТЫ',
        reason: 'Плановая проверка поступлений',
        expectedRevision: 8
      }
    );
    expect(post).toHaveBeenNthCalledWith(
      2,
      '/api/admin/contractor-payments/system/routing',
      {
        enabled: true,
        confirmation: 'ВКЛЮЧИТЬ РЕКВИЗИТЫ',
        reason: 'Сверка завершена',
        expectedRevision: 9
      }
    );
  });

  it('loads queue health including the completion-reward repair queue contract', () => {
    const health: ContractorPaymentQueueHealth = {
      allocationReconciliation: queueItem(),
      rewardRepair: queueItem(),
      shadowBackfill: queueItem(),
      completionRewardRepair: queueItem({ retrying: 2, dueRetries: 1 }),
      observedAt: '2026-08-07T12:00:00'
    };
    get.mockReturnValue(of(health));

    let result: ContractorPaymentQueueHealth | undefined;
    api.getQueueHealth().subscribe((value) => {
      result = value;
    });

    expect(get).toHaveBeenCalledWith('/api/admin/contractor-payment-allocations/health');
    expect(result?.completionRewardRepair).toMatchObject({ retrying: 2, dueRetries: 1 });
  });

  it('passes explicit user, status and page filters to the protected journal', () => {
    api.getAllocationJournal({
      userId: 17,
      status: 'CLIENT_REPORTED',
      mode: 'SHADOW',
      sourceType: 'PAYMENT_LINK',
      sourceId: 99,
      page: 2,
      size: 25
    });

    expect(get.mock.calls[0][0]).toBe('/api/admin/contractor-payment-allocations');
    const params = get.mock.calls[0][1].params as HttpParams;
    expect(params.get('userId')).toBe('17');
    expect(params.get('status')).toBe('CLIENT_REPORTED');
    expect(params.get('mode')).toBe('SHADOW');
    expect(params.get('sourceType')).toBe('PAYMENT_LINK');
    expect(params.get('sourceId')).toBe('99');
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe('25');
  });

  it('records an explicit cumulative return amount without payment requisites', () => {
    api.recordReturnedAmount(41, {
      returnedTotalKopecks: 12_500,
      reason: 'Частичный возврат подтверждён выпиской'
    });

    expect(put).toHaveBeenCalledWith(
      '/api/admin/contractor-payment-allocations/41/returned-amount',
      {
        returnedTotalKopecks: 12_500,
        reason: 'Частичный возврат подтверждён выпиской'
      }
    );
  });

  it('creates and reverses a direct settlement with an explicit accounting mode', () => {
    const request = {
      expectedMode: 'SHADOW' as const,
      amountKopecks: 125_000,
      effectiveAt: '2026-08-07T11:30',
      reason: 'Перевод по акту',
      evidenceReference: 'bank-proof-42',
      idempotencyKey: 'admin-ui-test-42'
    };

    api.createDirectSettlement(17, 8, request);
    api.reverseDirectSettlement(17, 8, 51, { ...request, amountKopecks: 25_000 });

    expect(post).toHaveBeenNthCalledWith(
      1,
      '/api/admin/users/17/contractor-payment-profiles/8/direct-settlements',
      request
    );
    expect(post).toHaveBeenNthCalledWith(
      2,
      '/api/admin/users/17/contractor-payment-profiles/8/direct-settlements/51/reversals',
      { ...request, amountKopecks: 25_000 }
    );
  });
});

function queueItem(
  overrides: Partial<ContractorPaymentQueueHealthItem> = {}
): ContractorPaymentQueueHealthItem {
  return {
    activeClaims: 0,
    expiredClaims: 0,
    retrying: 0,
    dueRetries: 0,
    oldestRetryAt: null,
    oldestDueRetryAt: null,
    lastErrorCode: null,
    ...overrides
  };
}

function systemStatus(): ContractorPaymentSystemStatus {
  return {
    mode: 'LEGACY',
    systemEnabled: false,
    legacyBehavior: true,
    irreversible: false,
    routingRequested: false,
    completionAccountingEffective: false,
    liveRoutingEffective: false,
    completionBacklogReady: true,
    activationAvailable: true,
    activationBlockedReasons: [],
    attributionStartDate: null,
    revision: 7,
    liveRoutingMasterEnabled: false,
    rewardAttributionMasterEnabled: false
  };
}
