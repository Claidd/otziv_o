import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { appEnvironment } from './app-environment';
import { ManagerApi } from './manager.api';
import { WorkerApi } from './worker.api';

describe('credential reveal API contracts', () => {
  let post: ReturnType<typeof vi.fn>;
  let workerApi: WorkerApi;
  let managerApi: ManagerApi;

  beforeEach(() => {
    post = vi.fn().mockReturnValue(of({ value: 'revealed-value' }));
    const http = { post } as unknown as HttpClient;
    workerApi = new WorkerApi(http);
    managerApi = new ManagerApi(http);
  });

  it('uses the worker review, bad-task and recovery-task reveal endpoints', () => {
    const source = { sourcePage: 'worker-board', sourceSection: 'publish' };

    workerApi.revealReviewCredential(11, 'password', source);
    workerApi.revealBadReviewTaskCredential(12, 'login', source);
    workerApi.revealRecoveryTaskCredential(13, 'password', source);

    expect(post).toHaveBeenNthCalledWith(
      1,
      `${appEnvironment.apiBaseUrl}/api/worker/reviews/11/credential-reveal`,
      { field: 'password', ...source }
    );
    expect(post).toHaveBeenNthCalledWith(
      2,
      `${appEnvironment.apiBaseUrl}/api/worker/bad-review-tasks/12/credential-reveal`,
      { field: 'login', ...source }
    );
    expect(post).toHaveBeenNthCalledWith(
      3,
      `${appEnvironment.apiBaseUrl}/api/worker/recovery-tasks/13/credential-reveal`,
      { field: 'password', ...source }
    );
  });

  it('uses only order-scoped manager reveal endpoints', () => {
    const source = { sourcePage: 'order-details', sourceEntry: 'manager' };

    managerApi.revealOrderReviewCredential(21, 31, 'password', source);
    managerApi.revealBadReviewTaskCredential(21, 32, 'login', source);
    managerApi.revealRecoveryTaskCredential(21, 33, 'password', source);

    expect(post).toHaveBeenNthCalledWith(
      1,
      `${appEnvironment.apiBaseUrl}/api/manager/orders/21/reviews/31/credential-reveal`,
      { field: 'password', ...source }
    );
    expect(post).toHaveBeenNthCalledWith(
      2,
      `${appEnvironment.apiBaseUrl}/api/manager/orders/21/bad-review-tasks/32/credential-reveal`,
      { field: 'login', ...source }
    );
    expect(post).toHaveBeenNthCalledWith(
      3,
      `${appEnvironment.apiBaseUrl}/api/manager/orders/21/recovery-tasks/33/credential-reveal`,
      { field: 'password', ...source }
    );

    for (const [url] of post.mock.calls) {
      expect(url).not.toContain('/copy-click');
      expect(url).not.toContain('/api/worker/');
    }
  });
});
