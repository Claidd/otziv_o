export interface ManualPaymentRecipientSummaryLike {
  manualRecipientName?: string | null;
  accountingRecipientLabel?: string | null;
  accountingDestinationKind?: string | null;
  accountingRecipientType?: string | null;
  accountingRecipientProfileId?: number | null;
  manualPaymentTaskId?: number | null;
  manualPaymentTaskGeneration?: number | null;
  manualPaymentTaskTargetKind?: string | null;
  attributionKnown?: boolean;
}

export function manualPaymentAccountingRecipientLabel(item: ManualPaymentRecipientSummaryLike): string {
  return clean(item.accountingRecipientLabel)
    || clean(item.manualRecipientName)
    || 'Получатель не указан';
}

export function manualPaymentAccountingDestinationLabel(item: ManualPaymentRecipientSummaryLike): string {
  if (item.attributionKnown === false) {
    return 'Нет точной атрибуции: требуется сверка старых оплат';
  }
  if (item.accountingDestinationKind === 'OWNER') {
    return 'Фактически зачтено владельцу';
  }
  if (item.accountingDestinationKind === 'CONTRACTOR_PROFILE') {
    return `${recipientTypeLabel(item.accountingRecipientType)}${profileSuffix(item.accountingRecipientProfileId)}`;
  }
  if (item.accountingDestinationKind === 'MANUAL_PAYMENT_TASK') {
    const generation = item.manualPaymentTaskGeneration == null
      ? '' : ` · версия ${item.manualPaymentTaskGeneration}`;
    return `${taskTargetLabel(item.manualPaymentTaskTargetKind)} · задание №${item.manualPaymentTaskId ?? '?'}${generation}`;
  }
  return 'Фактическое направление не определено';
}

export function manualPaymentAccountingSourceLabel(item: ManualPaymentRecipientSummaryLike): string {
  return item.attributionKnown === false
    ? 'Старая подтверждённая оплата без данных о получателе'
    : 'По фактическому получателю оплаты';
}

function recipientTypeLabel(type?: string | null): string {
  if (type === 'SPECIALIST') return 'Профиль специалиста';
  if (type === 'MANAGER') return 'Профиль менеджера';
  if (type === 'OWNER') return 'Владелец';
  return 'Платёжный профиль';
}

function taskTargetLabel(kind?: string | null): string {
  if (kind === 'EXTERNAL_TASK') return 'Внешний получатель';
  if (kind === 'OWNER') return 'Владелец';
  if (kind === 'SPECIALIST') return 'Специалист';
  if (kind === 'MANAGER') return 'Менеджер';
  return 'Получатель задания не определён';
}

function profileSuffix(profileId?: number | null): string {
  return profileId == null ? '' : ` · профиль №${profileId}`;
}

function clean(value?: string | null): string {
  return value?.trim() ?? '';
}
