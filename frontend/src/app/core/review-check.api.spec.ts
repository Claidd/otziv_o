import { HttpClient, HttpHeaders } from '@angular/common/http';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { appEnvironment } from './app-environment';
import { ReviewCheckApi } from './review-check.api';

describe('ReviewCheckApi capability header', () => {
  let get: ReturnType<typeof vi.fn>;
  let api: ReviewCheckApi;

  beforeEach(() => {
    get = vi.fn().mockReturnValue(of({}));
    api = new ReviewCheckApi({ get } as unknown as HttpClient);
  });

  it('puts only a validated rc1 token in the capability header', () => {
    const validToken = `rc1_${'A'.repeat(43)}`;

    api.getReviewCheck('secure-capability', 'raw-fragment-junk');
    api.getReviewCheck('secure-capability', validToken);

    expect(get).toHaveBeenNthCalledWith(
      1,
      `${appEnvironment.apiBaseUrl}/api/review-capability`,
      expect.objectContaining({ context: expect.anything() })
    );
    const invalidHeaders = get.mock.calls[0][1].headers as HttpHeaders | undefined;
    expect(invalidHeaders?.has('X-Review-Capability') ?? false).toBe(false);

    const validHeaders = get.mock.calls[1][1].headers as HttpHeaders;
    expect(validHeaders.get('X-Review-Capability')).toBe(validToken);
  });
});
