import type {
  ContractorDirectSettlementRequest, ContractorPaymentMode,
  ContractorPaymentProfile, ContractorPaymentProfileRequest
} from '../core/api.service';
import { normalizeManualTransferDestination } from './manual-transfer-destination';
import { newContractorAccrualOperationKey } from './contractor-accrual-adjustment';

export interface ContractorProfileDraft {
  enabled: boolean;
  liveEnabled: boolean;
  recipientName: string;
  paymentPhone: string;
  bankName: string;
  paymentComment: string;
  openingBalanceRubles: string;
  openingBalanceReason: string;
}

export interface ContractorSettlementDraft {
  amountRubles: string;
  effectiveAt: string;
  reason: string;
  evidenceReference: string;
  idempotencyKey: string;
}

export function contractorProfileDraft(profile: ContractorPaymentProfile): ContractorProfileDraft {
  return {
    enabled: profile.enabled, liveEnabled: profile.liveEnabled,
    recipientName: profile.recipientName ?? '', paymentPhone: profile.paymentPhone ?? '',
    bankName: profile.bankName ?? '', paymentComment: profile.paymentComment ?? '',
    openingBalanceRubles: (profile.openingBalanceKopecks / 100).toFixed(2),
    openingBalanceReason: ''
  };
}

export function parseKopecks(value: string, allowZero = false): number | null {
  const normalized = value.trim().replace(',', '.');
  if (!/^\d{1,12}(?:\.\d{1,2})?$/.test(normalized)) return null;
  const [rubles, fraction = ''] = normalized.split('.');
  const kopecks = Number(rubles) * 100 + Number(fraction.padEnd(2, '0'));
  return Number.isSafeInteger(kopecks) && kopecks <= 100_000_000_000 && (allowZero || kopecks > 0)
    ? kopecks : null;
}

export function contractorProfileRequest(
  profile: ContractorPaymentProfile,
  draft: ContractorProfileDraft
): { request: ContractorPaymentProfileRequest; error: null } | { request: null; error: string } {
  if (draft.liveEnabled && !draft.enabled) {
    return { request: null, error: 'Выдача реквизитов для новых счетов требует включённого профиля.' };
  }
  const openingBalanceKopecks = parseKopecks(draft.openingBalanceRubles, true);
  if (openingBalanceKopecks === null) {
    return { request: null, error: 'Укажите неотрицательный переходящий остаток с точностью до копейки.' };
  }
  const openingChanged = openingBalanceKopecks !== profile.openingBalanceKopecks;
  const openingBalanceReason = draft.openingBalanceReason.trim();
  if (openingChanged && (!openingBalanceReason || openingBalanceReason.length > 255)) {
    return { request: null, error: 'Укажите причину изменения переходящего остатка длиной до 255 символов.' };
  }
  const recipientName = draft.recipientName.trim();
  const paymentPhone = normalizeManualTransferDestination(draft.paymentPhone);
  const bankName = draft.bankName.trim();
  const paymentComment = draft.paymentComment.trim();
  const reduction = (profile.liveEnabled && !draft.liveEnabled && (!draft.enabled || profile.enabled))
    || (profile.enabled && !draft.enabled);
  const phoneChanged = paymentPhone !== normalizeManualTransferDestination(profile.paymentPhone);
  if (phoneChanged || (draft.enabled && !reduction)) {
    const phoneDigits = paymentPhone.startsWith('+') ? paymentPhone.slice(1) : paymentPhone;
    const validPhone = /^\d{10,15}$/.test(phoneDigits);
    const validCard = !paymentPhone.startsWith('+') && /^\d{16,19}$/.test(paymentPhone);
    if (paymentPhone && !validPhone && !validCard) {
      return { request: null, error: 'Введите телефон из 10–15 цифр или номер карты из 16–19 цифр.' };
    }
  }
  if (draft.enabled && !reduction && (!recipientName || !paymentPhone || !bankName)) {
    return { request: null, error: 'Для включения укажите ФИО получателя, телефон или карту и банк.' };
  }
  return {
    request: {
      role: profile.role, expectedVersion: profile.rowVersion,
      enabled: draft.enabled, liveEnabled: draft.liveEnabled,
      recipientName, paymentPhone: reduction && !phoneChanged ? profile.paymentPhone ?? '' : paymentPhone,
      bankName, paymentComment, openingBalanceKopecks,
      openingBalanceReason: openingChanged ? openingBalanceReason : undefined
    }, error: null
  };
}

export function localDateTimeInput(now = new Date()): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
    + `T${pad(now.getHours())}:${pad(now.getMinutes())}`;
}

export function newContractorSettlementDraft(): ContractorSettlementDraft {
  return {
    amountRubles: '', effectiveAt: localDateTimeInput(), reason: '',
    evidenceReference: '', idempotencyKey: newContractorAccrualOperationKey()
  };
}

export function contractorSettlementRequest(
  mode: ContractorPaymentMode, draft: ContractorSettlementDraft,
  now = localDateTimeInput()
): { request: ContractorDirectSettlementRequest; error: null } | { request: null; error: string } {
  const amountKopecks = parseKopecks(draft.amountRubles);
  if (amountKopecks === null) {
    return { request: null, error: 'Укажите положительную сумму до 1 млрд ₽ с точностью до копейки.' };
  }
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(draft.effectiveAt);
  const date = dateMatch ? new Date(draft.effectiveAt) : null;
  const validDate = !!dateMatch && !!date && !Number.isNaN(date.getTime())
    && date.getFullYear() === Number(dateMatch[1]) && date.getMonth() + 1 === Number(dateMatch[2])
    && date.getDate() === Number(dateMatch[3]) && date.getHours() === Number(dateMatch[4])
    && date.getMinutes() === Number(dateMatch[5]);
  if (!validDate || draft.effectiveAt > now) {
    return { request: null, error: 'Укажите существующую дату и время перевода, не позднее текущего.' };
  }
  const reason = draft.reason.trim();
  const evidenceReference = draft.evidenceReference.trim();
  if (!reason || reason.length > 255) {
    return { request: null, error: 'Укажите основание длиной до 255 символов.' };
  }
  if (!evidenceReference || evidenceReference.length > 160) {
    return { request: null, error: 'Укажите ID внутреннего подтверждающего документа длиной до 160 символов.' };
  }
  return {
    request: {
      expectedMode: mode, amountKopecks, effectiveAt: draft.effectiveAt,
      reason, evidenceReference, idempotencyKey: draft.idempotencyKey
    }, error: null
  };
}
