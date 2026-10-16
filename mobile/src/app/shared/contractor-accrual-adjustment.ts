import type {
  ContractorAccrualAdjustmentKind,
  ContractorAccrualAdjustmentRequest
} from '../core/api.service';

export interface ContractorAccrualDraft {
  kind: ContractorAccrualAdjustmentKind;
  amountRubles: string;
  reason: string;
  referenceMonth: string;
  evidenceReference: string;
  idempotencyKey: string;
}

export function currentContractorAccrualMonth(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Irkutsk', year: 'numeric', month: '2-digit'
  }).formatToParts(now);
  const year = parts.find((part) => part.type === 'year')?.value ?? '';
  const month = parts.find((part) => part.type === 'month')?.value ?? '';
  return `${year}-${month}`;
}

export function newContractorAccrualOperationKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `accrual-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`;
}

export function newContractorAccrualDraft(): ContractorAccrualDraft {
  return {
    kind: 'BONUS', amountRubles: '', reason: '',
    referenceMonth: currentContractorAccrualMonth(),
    evidenceReference: '', idempotencyKey: newContractorAccrualOperationKey()
  };
}

export function contractorAccrualRequest(
  draft: ContractorAccrualDraft,
  currentMonth = currentContractorAccrualMonth()
): { request: ContractorAccrualAdjustmentRequest; error: null } | { request: null; error: string } {
  const normalizedAmount = draft.amountRubles.trim().replace(',', '.');
  if (!/^\d{1,12}(?:\.\d{1,2})?$/.test(normalizedAmount)) {
    return { request: null, error: 'Укажите положительную сумму в рублях с точностью до копейки.' };
  }
  const [rubles, fraction = ''] = normalizedAmount.split('.');
  const amountKopecks = Number(rubles) * 100 + Number(fraction.padEnd(2, '0'));
  if (!Number.isSafeInteger(amountKopecks) || amountKopecks <= 0 || amountKopecks > 100_000_000_000) {
    return { request: null, error: 'Сумма должна быть больше нуля и не превышать 1 млрд рублей.' };
  }
  const reason = draft.reason.trim();
  if (!reason || reason.length > 255) {
    return { request: null, error: 'Укажите причину длиной до 255 символов; её увидит сотрудник.' };
  }
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(draft.referenceMonth) || draft.referenceMonth > currentMonth) {
    return { request: null, error: 'Укажите текущий или прошлый месяц основания в формате ГГГГ-ММ.' };
  }
  const evidenceReference = draft.evidenceReference.trim();
  if (evidenceReference.length > 160) {
    return { request: null, error: 'ID документа не должен превышать 160 символов.' };
  }
  return {
    request: {
      kind: draft.kind, amountKopecks, reason, referenceMonth: draft.referenceMonth,
      evidenceReference: evidenceReference || undefined,
      idempotencyKey: draft.idempotencyKey
    },
    error: null
  };
}
