export type PaymentNavigationPurpose = 'manual' | 'payment' | 'sbp';

const PAYMENT_PROVIDER_HOSTS = new Set(['securepay.tinkoff.ru', 'securepay.tbank.ru', 'pay.tbank.ru']);
const SBP_WEB_HOSTS = new Set(['qr.nspk.ru', 'www.tbank.ru', 'payzonaecom.com']);
const SBP_CUSTOM_PATH = String.raw`(?:[/?#][A-Za-z0-9._~%!$&'()*+,;=:@/?#-]*)?`;
const NSPK_BANK_TARGET = new RegExp(
  String.raw`^bank(?:b2b)?[0-9]{12}:\/\/qr\.nspk\.ru${SBP_CUSTOM_PATH}$`,
  'i'
);
const LEGACY_BANKAPP_TARGET = new RegExp(
  String.raw`^bankapp:\/\/pay${SBP_CUSTOM_PATH}$`,
  'i'
);
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/;
const ENCODED_CONTROL = /%(?:0[0-9a-f]|1[0-9a-f]|7f)/i;
const MAX_LENGTH: Record<PaymentNavigationPurpose, number> = {
  manual: 512,
  payment: 1024,
  sbp: 2048
};

/**
 * Keeps the recipient supplied by the backend explicit. In particular, an
 * empty value is a quarantined/missing destination and must never be replaced
 * in the client with a different recipient's default URL.
 */
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

  // Chrome only started exposing hostname for non-special URL schemes in
  // version 130. Validate provider-defined SBP deep links directly so older
  // Android Chrome versions do not reject a legitimate bankNNN:// target.
  if (purpose === 'sbp'
    && (NSPK_BANK_TARGET.test(target) || LEGACY_BANKAPP_TARGET.test(target))) {
    return target;
  }

  try {
    const url = new URL(target);
    const protocol = url.protocol.toLowerCase();
    if (protocol === 'https:') {
      if (!url.hostname || url.username || url.password) {
        return null;
      }
      const hostname = url.hostname.toLowerCase();
      // Manual links are operator-configured and may legitimately point to a
      // different acquiring bank. Machine-created links are provider-bound.
      if (purpose === 'manual') {
        return target;
      }
      if (purpose === 'payment') {
        return PAYMENT_PROVIDER_HOSTS.has(hostname) ? target : null;
      }
      return SBP_WEB_HOSTS.has(hostname) ? target : null;
    }
    return null;
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
