export function normalizePhoneDigits(value?: string | number | null): string {
  let digits = String(value ?? '').replace(/\D+/g, '');

  if (digits.length === 11 && digits.startsWith('8')) {
    digits = `7${digits.slice(1)}`;
  }

  if (digits.length === 10) {
    digits = `7${digits}`;
  }

  return digits;
}

export function displayPhone(value?: string | number | null, fallback = 'Телефон не указан'): string {
  const digits = normalizePhoneDigits(value);

  if (digits.length === 11) {
    return `${digits.slice(0, 1)}-${digits.slice(1, 4)}-${digits.slice(4, 7)}-${digits.slice(7, 9)}-${digits.slice(9)}`;
  }

  return digits || String(value ?? '').trim() || fallback;
}

export function phoneHref(value?: string | number | null): string {
  const digits = normalizePhoneDigits(value);
  return digits ? `tel:+${digits}` : '';
}
