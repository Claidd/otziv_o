export interface ManualPaymentReviewView {
  id: number;
  orderId: number;
  status: string;
  deadlineAt: string | null;
  creditedAt?: string | null;
  confirmationBasis?: string | null;
  disputeAllowed: boolean;
}

export interface ManualPaymentReviewPresentation {
  title: string;
  detail: string;
  tone: 'success' | 'waiting' | 'attention';
}

type ReviewState = Pick<ManualPaymentReviewView, 'status' | 'deadlineAt' | 'creditedAt' | 'confirmationBasis'>;

/** LocalDateTime is displayed in the server's system time, without inferring elapsed deadlines. */
export function manualPaymentReviewDeadline(value: string | null | undefined): string | null {
  const match = value?.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/);
  return match ? `${match[3]}.${match[2]}.${match[1]} в ${match[4]}:${match[5]} (время системы)` : null;
}

export function manualPaymentReviewPresentation(review: ReviewState): ManualPaymentReviewPresentation {
  const marked = !!review.creditedAt;
  if (review.status === 'DISPUTED') {
    return { title: 'Оплата оспорена', detail: 'Специалист или получатель сообщил, что перевод не поступил. Нужна сверка с менеджером; спор сам по себе не отменяет отметку оплаты.', tone: 'attention' };
  }
  if (review.status === 'CONFIRMED') {
    if (review.confirmationBasis === 'AUTO_NO_OBJECTION') {
      return { title: 'Подтверждено без возражений', detail: 'Срок для возражения завершён, обращений о непоступлении не зафиксировано. Это подтверждение по правилу 24 часов, а не проверка банка.', tone: 'success' };
    }
    if (review.confirmationBasis === 'RECIPIENT_STATEMENT') {
      return { title: 'Поступление подтверждено по выписке', detail: 'Результат сверки получателя сохранён.', tone: 'success' };
    }
    return { title: 'Оплата подтверждена', detail: 'Подтверждение сохранено в системе.', tone: 'success' };
  }
  if (review.status === 'NEEDS_ATTENTION') {
    return { title: 'Требуется сверка оплаты', detail: marked ? 'Оплата отмечена. Менеджеру нужно проверить состояние перевода и обращения получателя.' : 'Результат отметки оплаты требует проверки. Проверьте состояние платежа с менеджером.', tone: 'attention' };
  }
  if (review.status === 'SUPERSEDED') {
    return { title: 'Проверка закрыта', detail: 'Проверьте результат в актуальном платеже заказа.', tone: 'waiting' };
  }
  if (['PENDING_DELIVERY', 'WAITING', 'SETTLING', 'ADMIN_SETTLING'].includes(review.status)) {
    if (!marked) {
      return { title: 'Отметка оплаты ещё не завершена', detail: 'Сервер пока не подтвердил зачисление. Обновите состояние или обратитесь к менеджеру.', tone: 'waiting' };
    }
    const deadline = manualPaymentReviewDeadline(review.deadlineAt);
    return {
      title: 'Оплата отмечена',
      detail: deadline
        ? `Можно оспорить до ${deadline}, сообщив менеджеру, что перевод не поступил. Если возражений нет, система подтвердит оплату после этого срока.`
        : 'Если перевод не поступил, сообщите менеджеру. Срок возражения уточняется в системе.',
      tone: 'waiting'
    };
  }
  return { title: 'Статус оплаты требует уточнения', detail: 'Обновите состояние или обратитесь к менеджеру.', tone: 'attention' };
}

/** Permission is supplied by the server; a client clock never confirms a payment or authorizes a dispute. */
export function manualPaymentReviewCanDispute(review: ManualPaymentReviewView | null | undefined): boolean {
  return review?.disputeAllowed === true;
}

export function manualPaymentReviewDisputeRequest(
  review: ManualPaymentReviewView | null | undefined, checked: boolean, note: string
): { recipientReportedMissing: true; note: string } | null {
  const trimmed = note.trim();
  return manualPaymentReviewCanDispute(review) && checked && trimmed.length >= 10 && trimmed.length <= 500
    ? { recipientReportedMissing: true, note: trimmed }
    : null;
}

export function manualPaymentResultNotice(result: {
  status: string; orderId: number; message?: string | null;
  manualPaymentReview?: ManualPaymentReviewView | null;
}): { title: string; detail: string; tone: 'success' | 'warning' } {
  if (result.status !== 'COMPLETED') {
    return {
      title: result.status === 'OWNER_APPROVAL_PENDING' ? 'Ожидается подтверждение владельца' : 'Отметка оплаты ещё не завершена',
      detail: result.message || 'Обновите состояние заказа. Сервер пока не подтвердил зачисление.',
      tone: 'warning'
    };
  }
  const review = result.manualPaymentReview;
  if (review && review.orderId === result.orderId) {
    const state = manualPaymentReviewPresentation(review);
    return {
      title: state.title, detail: state.detail,
      tone: state.tone === 'attention' || (!review.creditedAt && review.status !== 'CONFIRMED') ? 'warning' : 'success'
    };
  }
  return { title: 'Оплата отмечена', detail: result.message || 'Заказ оплачен.', tone: 'success' };
}
