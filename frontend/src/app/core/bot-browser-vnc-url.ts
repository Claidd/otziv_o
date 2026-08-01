const ALLOWED_VNC_PROTOCOLS = new Set(['http:', 'https:']);
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/;
const ENCODED_CONTROL = /%(?:0[0-9a-f]|1[0-9a-f]|7f)/i;
const MAX_VNC_URL_LENGTH = 4096;

/**
 * Validates the upstream VNC URL before it reaches Angular's trust bypass.
 * Relative, malformed, and active-content URLs are rejected.
 */
export function prepareBotBrowserVncUrl(rawUrl: unknown): string | null {
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
    if (
      !ALLOWED_VNC_PROTOCOLS.has(url.protocol.toLowerCase())
      || !url.hostname
      || url.username
      || url.password
    ) {
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
