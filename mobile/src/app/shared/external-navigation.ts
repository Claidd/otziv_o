const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/;
const ENCODED_CONTROL_CHARACTER = /%(?:0[0-9a-f]|1[0-9a-f]|7f|8[0-9a-f]|9[0-9a-f])/i;

function normalizedTarget(value: unknown): string | null {
  if (typeof value !== 'string' || CONTROL_CHARACTER.test(value) || ENCODED_CONTROL_CHARACTER.test(value)) {
    return null;
  }
  const target = value.trim();
  return target && target.length <= 2048 ? target : null;
}

export function safeHttpsExternalUrl(value: unknown): string | null {
  const target = normalizedTarget(value);
  if (!target) {
    return null;
  }
  try {
    const url = new URL(target);
    return url.protocol === 'https:' && Boolean(url.hostname) && !url.username && !url.password
      ? target
      : null;
  } catch {
    return null;
  }
}

export function safeInternalNavigationPath(value: unknown): string | null {
  const target = normalizedTarget(value);
  if (!target || !target.startsWith('/') || target.startsWith('//') || target.includes('\\')) {
    return null;
  }
  return target;
}

export function safeHttpsOrInternalUrl(value: unknown): string | null {
  return safeHttpsExternalUrl(value) ?? safeInternalNavigationPath(value);
}

export function safeExternalSchemeUrl(value: unknown, allowedProtocols: readonly string[]): string | null {
  const target = normalizedTarget(value);
  if (!target) {
    return null;
  }
  try {
    const url = new URL(target);
    return allowedProtocols.includes(url.protocol) && !url.username && !url.password ? target : null;
  } catch {
    return null;
  }
}
