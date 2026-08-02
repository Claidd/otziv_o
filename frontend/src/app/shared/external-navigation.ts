const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/;
const ENCODED_CONTROL_CHARACTER = /%(?:0[0-9a-f]|1[0-9a-f]|7f|8[0-9a-f]|9[0-9a-f])/i;

export function safeHttpsExternalUrl(value: unknown): string | null {
  if (typeof value !== 'string' || CONTROL_CHARACTER.test(value) || ENCODED_CONTROL_CHARACTER.test(value)) {
    return null;
  }
  const target = value.trim();
  if (!target || target.length > 2048) {
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
