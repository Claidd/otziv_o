import { describe, expect, it } from 'vitest';
import { prepareBotBrowserVncUrl } from './bot-browser-vnc-url';

describe('prepareBotBrowserVncUrl', () => {
  it.each([
    'javascript:alert(document.cookie)',
    'data:text/html,<script>alert(1)</script>',
    'https://user:password@vnc.example.test/session',
    'https://vnc.example.test/session\r\nLocation:https://evil.test',
    'https://vnc.example.test/session%0d%0aLocation:https://evil.test',
    'https://vnc.example.test/session%00',
    'https://vnc.example.test/session%7f',
    '/relative/vnc/session',
    'not a url',
    'https://'
  ])('rejects unsafe or malformed URL %s', (rawUrl) => {
    expect(prepareBotBrowserVncUrl(rawUrl)).toBeNull();
  });

  it.each(['http://vnc.example.test/session', 'https://vnc.example.test/session'])(
    'accepts absolute HTTP(S) URL %s and adds client parameters',
    (rawUrl) => {
      const prepared = prepareBotBrowserVncUrl(rawUrl);

      expect(prepared).not.toBeNull();
      const url = new URL(prepared!);
      expect(['http:', 'https:']).toContain(url.protocol);
      expect(url.host).toBe('vnc.example.test');
      expect(url.searchParams.get('autoconnect')).toBe('1');
      expect(url.searchParams.get('reconnect')).toBe('1');
      expect(url.searchParams.get('resize')).toBe('none');
      expect(url.searchParams.get('clip')).toBe('true');
    }
  );
});
