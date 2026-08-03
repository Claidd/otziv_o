export type PaymentNavigationPurpose = 'manual' | 'payment' | 'sbp';

const PAYMENT_PROVIDER_HOSTS = new Set(['securepay.tinkoff.ru', 'securepay.tbank.ru']);
const SBP_WEB_HOST = 'qr.nspk.ru';
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
    if (protocol === 'https:') {
      if (!url.hostname || url.username || url.password) {
        return null;
      }
      const hostname = url.hostname.toLowerCase();
      if (purpose === 'manual') {
        return target;
      }
      if (purpose === 'payment') {
        return PAYMENT_PROVIDER_HOSTS.has(hostname) ? target : null;
      }
      return hostname === SBP_WEB_HOST ? target : null;
    }
    if (purpose !== 'sbp' || !url.hostname || url.username || url.password) {
      return null;
    }
    if (protocol === 'bankapp:') {
      return url.hostname.toLowerCase() === 'pay' ? target : null;
    }
    return NSPK_BANK_PROTOCOL.test(protocol) && url.hostname.toLowerCase() === SBP_WEB_HOST
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
