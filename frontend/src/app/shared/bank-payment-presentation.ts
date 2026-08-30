export interface BankProfilePresentation {
  name?: string | null;
  provider?: string | null;
  enabled?: boolean | null;
  operational?: boolean | null;
  hasPassword?: boolean | null;
}

export function bankProviderLabel(provider?: string | null): string {
  switch ((provider ?? '').trim().toUpperCase()) {
    case 'T_BANK':
    case 'TBANK':
    case 'T-BANK':
      return 'T‑Bank';
    case 'TOCHKA':
      return 'Точка Банк';
    default:
      return 'Банк';
  }
}

export function bankProfileOptionLabel(profile: BankProfilePresentation): string {
  const parts = [bankProviderLabel(profile.provider), profile.name?.trim() || 'Профиль оплаты'];
  if (profile.enabled === false) {
    parts.push('выключен');
  } else if (profile.operational === false) {
    parts.push('не готов');
  } else if (profile.hasPassword === false) {
    parts.push('нет реквизитов');
  }
  return parts.join(' · ');
}

export function isBankPaymentRouteType(routeType?: string | null): boolean {
  switch ((routeType ?? '').trim().toUpperCase()) {
    case 'BANK_LINK':
    case 'TBANK_LINK':
    case 'TOCHKA_LINK':
      return true;
    default:
      return false;
  }
}

export function bankPaymentRouteLabel(
  routeType?: string | null,
  profileName?: string | null,
  provider?: string | null
): string {
  const normalizedRoute = (routeType ?? '').trim().toUpperCase();
  const resolvedProvider = provider?.trim()
    || (normalizedRoute === 'TBANK_LINK' ? 'T_BANK' : normalizedRoute === 'TOCHKA_LINK' ? 'TOCHKA' : '');
  const bank = bankProviderLabel(resolvedProvider);
  const profile = profileName?.trim();
  return profile ? `${bank} · ${profile}` : bank;
}
