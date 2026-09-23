import { HttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthService } from './auth.service';
import { WorkerMediaContextService } from './worker-media-context.service';
import type { WorkerReviewItem } from './worker.api';

describe('WorkerMediaContextService', () => {
  let service: WorkerMediaContextService;
  let roles: string[];
  const post = vi.fn();

  beforeEach(() => {
    roles = ['WORKER'];
    post.mockReset().mockReturnValue(of(undefined));
    TestBed.configureTestingModule({ providers: [
      { provide: HttpClient, useValue: { post } },
      { provide: AuthService, useValue: {
        hasAnyRealmRole: (expected: string[]) => expected.some(role => roles.includes(role))
      } }
    ] });
    service = TestBed.inject(WorkerMediaContextService);
  });

  it('does not send worker hints for a manager viewing worker screens', () => {
    roles = ['WORKER', 'MANAGER'];
    service.requestFailed({ status: 500 });
    expect(post).not.toHaveBeenCalled();
  });

  it('ignores validation failures and throttles repeated server errors', () => {
    service.requestFailed({ status: 400 });
    service.requestFailed({ status: 500 });
    service.requestFailed({ status: 503 });
    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][1].action).toBe('SITE_ERROR');
  });

  it('preserves typed task ownership and swallows optional delivery failures', () => {
    post.mockReturnValue(throwError(() => new Error('offline')));
    expect(() => service.cardHint('UNSAVED_CHANGES', {
      id: 999, recoveryTask: true, recoveryTaskId: 8
    } as WorkerReviewItem)).not.toThrow();
    expect(post.mock.calls[0][1]).toMatchObject({
      action: 'UNSAVED_CHANGES', entityType: 'recovery_task', entityId: 8
    });
  });
});
