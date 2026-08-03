const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/;
const ENCODED_CONTROL = /%(?:0[0-9a-f]|1[0-9a-f]|7f)/i;
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);
const MAX_VNC_URL_LENGTH = 4096;

export type BotBrowserVncUrlPolicy = {
  allowedOrigins?: readonly string[];
  pageOrigin?: string;
};

/**
 * Validates the capability URL before Angular's trust bypass. Production VNC
 * endpoints must use HTTPS and match an exact configured origin. Plain HTTP is
 * limited to an explicitly allowed loopback origin for local development.
 */
export function prepareBotBrowserVncUrl(
  rawUrl: unknown,
  policy: BotBrowserVncUrlPolicy = {}
): string | null {
  if (
    typeof rawUrl !== 'string'
    || rawUrl.length === 0
    || rawUrl.length > MAX_VNC_URL_LENGTH
    || CONTROL_CHARACTER.test(rawUrl)
    || ENCODED_CONTROL.test(rawUrl)
  ) {
    return null;
  }

  try {
    const url = new URL(rawUrl);
    if (!url.hostname || url.username || url.password || !isSecureVncProtocol(url)) {
      return null;
    }

    const pageOrigin = policy.pageOrigin ?? currentPageOrigin();
    const allowedOrigins = normalizedAllowedOrigins([pageOrigin, ...(policy.allowedOrigins ?? [])]);
    if (!allowedOrigins.has(url.origin)) {
      return null;
    }

    url.searchParams.set('autoconnect', '1');
    url.searchParams.set('reconnect', '1');
    url.searchParams.set('resize', 'none');
    url.searchParams.set('clip', 'true');
    return url.toString();
  } catch {
    return null;
  }
}

function isSecureVncProtocol(url: URL): boolean {
  return url.protocol === 'https:'
    || (url.protocol === 'http:' && LOOPBACK_HOSTS.has(url.hostname.toLowerCase()));
}

function normalizedAllowedOrigins(values: readonly (string | undefined)[]): Set<string> {
  const origins = new Set<string>();
  for (const value of values) {
    if (!value) {
      continue;
    }
    try {
      const url = new URL(value);
      if (isSecureVncProtocol(url)) {
        origins.add(url.origin);
      }
    } catch {
      // Invalid deployment configuration is ignored and therefore fails closed.
    }
  }
  return origins;
}

function currentPageOrigin(): string | undefined {
  return typeof window === 'undefined' ? undefined : window.location.origin;
}
