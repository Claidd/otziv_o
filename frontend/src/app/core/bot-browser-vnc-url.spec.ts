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
    'http://vnc.example.test/session',
    '/relative/vnc/session',
    'not a url',
    'https://'
  ])('rejects unsafe or malformed URL %s', (rawUrl) => {
    expect(prepareBotBrowserVncUrl(rawUrl)).toBeNull();
  });

  it.each(['https://vnc.example.test/session', 'http://localhost:6080/session'])(
    'accepts an explicitly allowed secure URL %s and adds client parameters',
    (rawUrl) => {
      const prepared = prepareBotBrowserVncUrl(rawUrl, {
        pageOrigin: 'https://app.example.test',
        allowedOrigins: [new URL(rawUrl).origin]
      });

      expect(prepared).not.toBeNull();
      const url = new URL(prepared!);
      expect(['http:', 'https:']).toContain(url.protocol);
      expect(url.host).toBe(new URL(rawUrl).host);
      expect(url.searchParams.get('autoconnect')).toBe('1');
      expect(url.searchParams.get('reconnect')).toBe('1');
      expect(url.searchParams.get('resize')).toBe('none');
      expect(url.searchParams.get('clip')).toBe('true');
    }
  );

  it('rejects an HTTPS origin that was not configured', () => {
    expect(prepareBotBrowserVncUrl('https://evil.example.test/session', {
      pageOrigin: 'https://app.example.test',
      allowedOrigins: ['https://vnc.example.test']
    })).toBeNull();
  });

  it('allows different loopback ports when the page is also local', () => {
    expect(prepareBotBrowserVncUrl('http://127.0.0.1:49152/vnc.html', {
      pageOrigin: 'http://localhost:4200'
    })).not.toBeNull();
  });

  it('does not allow a loopback capability from a production page', () => {
    expect(prepareBotBrowserVncUrl('http://127.0.0.1:49152/vnc.html', {
      pageOrigin: 'https://app.example.test'
    })).toBeNull();
  });
});
