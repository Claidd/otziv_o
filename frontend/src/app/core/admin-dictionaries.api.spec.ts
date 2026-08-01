import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { appEnvironment } from './app-environment';
import { AdminDictionariesApi } from './admin-dictionaries.api';

describe('AdminDictionariesApi bot browser endpoints', () => {
  let get: ReturnType<typeof vi.fn>;
  let post: ReturnType<typeof vi.fn>;
  let api: AdminDictionariesApi;

  beforeEach(() => {
    get = vi.fn().mockReturnValue(of({}));
    post = vi.fn().mockReturnValue(of({}));
    api = new AdminDictionariesApi({ get, post } as unknown as HttpClient);
  });

  it('loads browser display data from the safe metadata endpoint', () => {
    api.getBotBrowserMetadata(37);

    expect(get).toHaveBeenCalledOnce();
    expect(get).toHaveBeenCalledWith(
      `${appEnvironment.apiBaseUrl}/api/bots/37/browser/metadata`
    );
    expect(get.mock.calls[0][0]).not.toContain('/api/admin/bots/');
  });

  it('uses session-scoped heartbeat and close while retaining the legacy fallback', () => {
    const sessionId = '7c121c71-7bc4-4a25-a33c-78c7fe63e5c9';
    api.openBotBrowser(37);
    api.heartbeatBotBrowser(37, sessionId);
    api.closeBotBrowser(37, sessionId);
    api.closeBotBrowser(37);

    expect(post).toHaveBeenNthCalledWith(
      1,
      `${appEnvironment.apiBaseUrl}/api/bots/37/browser/open`,
      { heartbeatSupported: true }
    );
    expect(post).toHaveBeenNthCalledWith(
      2,
      `${appEnvironment.apiBaseUrl}/api/bots/37/browser/sessions/${sessionId}/heartbeat`,
      {}
    );
    expect(post).toHaveBeenNthCalledWith(
      3,
      `${appEnvironment.apiBaseUrl}/api/bots/37/browser/sessions/${sessionId}/close`,
      {}
    );
    expect(post).toHaveBeenNthCalledWith(
      4,
      `${appEnvironment.apiBaseUrl}/api/bots/37/browser/close`,
      {}
    );
  });
});
