export function phoneDigits(phone?: string | null): string {
  const digits = (phone ?? '').replace(/\D/g, '');

  if (digits.length === 10) {
    return `7${digits}`;
  }

  if (digits.length === 11 && digits.startsWith('8')) {
    return `7${digits.slice(1)}`;
  }

  return digits;
}

export function formatPhoneForDisplay(phone?: string | null): string {
  const digits = phoneDigits(phone);

  if (digits.length === 11 && digits.startsWith('7')) {
    return `${digits.slice(0, 1)}-${digits.slice(1, 4)}-${digits.slice(4, 7)}-${digits.slice(7, 9)}-${digits.slice(9)}`;
  }

  return phone?.trim() || '-';
}
