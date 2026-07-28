import { HttpClient, HttpParams } from '@angular/common/http';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { WorkloadShadowApi } from './workload-shadow.api';

describe('WorkloadShadowApi', () => {
  let get: ReturnType<typeof vi.fn>;
  let put: ReturnType<typeof vi.fn>;
  let post: ReturnType<typeof vi.fn>;
  let api: WorkloadShadowApi;

  beforeEach(() => {
    get = vi.fn().mockReturnValue(of({}));
    put = vi.fn().mockReturnValue(of({}));
    post = vi.fn().mockReturnValue(of({}));
    api = new WorkloadShadowApi({ get, put, post } as unknown as HttpClient);
  });

  it('uses the protected admin monitoring endpoints and manager filter', () => {
    api.getSummary();
    api.getWorkers(17);
    api.getProposals(null);
    api.getEvents(50);
    api.getHealth();

    expect(get.mock.calls[0][0]).toMatch(/\/api\/admin\/workload-shadow\/monitor\/summary$/);
    expect(get.mock.calls[1][0]).toMatch(/\/api\/admin\/workload-shadow\/monitor\/workers$/);
    expect((get.mock.calls[1][1].params as HttpParams).get('managerId')).toBe('17');
    expect(get.mock.calls[2][0]).toMatch(/\/api\/admin\/workload-shadow\/monitor\/proposals$/);
    expect((get.mock.calls[2][1].params as HttpParams).has('managerId')).toBe(false);
    expect((get.mock.calls[3][1].params as HttpParams).get('limit')).toBe('50');
    expect(get.mock.calls[4][0]).toMatch(/\/api\/admin\/workload-shadow\/monitor\/health$/);
  });

  it('keeps recalculation and repair as explicit observation actions', () => {
    api.recalculate();
    api.repair();

    expect(post.mock.calls[0][0]).toMatch(/\/monitor\/recalculate$/);
    expect(post.mock.calls[0][1]).toEqual({});
    expect(post.mock.calls[1][0]).toMatch(/\/monitor\/repair$/);
    expect(post.mock.calls[1][1]).toEqual({});
  });

  it('reads and updates only the current worker transfer preference', () => {
    api.getMyTransferPreference();
    api.updateMyTransferPreference(false);

    expect(get.mock.calls[0][0]).toMatch(/\/api\/workload-shadow\/preferences\/me$/);
    expect(put.mock.calls[0][0]).toMatch(/\/api\/workload-shadow\/preferences\/me$/);
    expect(put.mock.calls[0][1]).toEqual({ acceptsCompanyTransfers: false });
  });
});
