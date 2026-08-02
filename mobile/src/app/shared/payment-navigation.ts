export type PaymentNavigationPurpose = 'manual' | 'payment' | 'sbp';

const WEB_PROTOCOLS = new Set(['http:', 'https:']);
const NSPK_BANK_PROTOCOL = /^bank(?:b2b)?[0-9]{12}:$/i;
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/;
const ENCODED_CONTROL = /%(?:0[0-9a-f]|1[0-9a-f]|7f)/i;
const MAX_LENGTH: Record<PaymentNavigationPurpose, number> = {
  manual: 512,
  payment: 1024,
  sbp: 2048
};

/** Never replace an absent/quarantined backend destination with another recipient. */
export function configuredPaymentTarget(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

export function paymentTargetForUpdate(
  value: unknown,
  backendDestinationConfigured: boolean | undefined
): string | null {
  const target = configuredPaymentTarget(value);
  return backendDestinationConfigured === false && !target ? null : target;
}

export function safePaymentNavigationTarget(
  value: unknown,
  purpose: PaymentNavigationPurpose
): string | null {
  if (typeof value !== 'string' || CONTROL_CHARACTER.test(value)) {
    return null;
  }
  const target = value.trim();
  if (!target || target.length > MAX_LENGTH[purpose] || ENCODED_CONTROL.test(target)) {
    return null;
  }

  try {
    const url = new URL(target);
    const protocol = url.protocol.toLowerCase();
    if (WEB_PROTOCOLS.has(protocol)) {
      return url.hostname && !url.username && !url.password ? target : null;
    }
    if (purpose !== 'sbp' || !url.hostname || url.username || url.password) {
      return null;
    }
    if (protocol === 'bankapp:') {
      return url.hostname.toLowerCase() === 'pay' ? target : null;
    }
    return NSPK_BANK_PROTOCOL.test(protocol) && url.hostname.toLowerCase() === 'qr.nspk.ru'
      ? target
      : null;
  } catch {
    return null;
  }
}

export function navigateToPaymentTarget(
  value: unknown,
  purpose: PaymentNavigationPurpose,
  navigate: (target: string) => void
): boolean {
  const target = safePaymentNavigationTarget(value, purpose);
  if (!target) {
    return false;
  }
  navigate(target);
  return true;
}
