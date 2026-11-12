import { prepareManualTransfer, type ManualTransferLine } from '@otziv/client-common/billing-payments';
import { Component, EventEmitter, Input, Output, inject } from '@angular/core';
import { defer, firstValueFrom, from, map, of } from 'rxjs';
import type {
  CommonManualPaymentAttributionRequest,
  CommonManualPaymentOptions,
  CommonManualPaymentOtherRecipient,
  CommonManualPaymentRecipientCandidate
} from '../../core/common-manual-payment-attribution.api';
import {
  ManagerApi,
  type ManualCardPaymentConfirmationRequest,
  type ManualCardPaymentContext,
  type ManualCardPaymentRecipientOption,
  type ManagerManualCardPaymentResult,
  type OrderCardItem
} from '../../core/manager.api';
import {
  isManualPaymentTaskRecipient,
  manualPaymentRecipientKey as taskAwareRecipientKey,
  manualPaymentRecipientLabel
} from '../../shared/manual-payment-routing';
import {
  CommonManualPaymentAttributionModalComponent,
  type PaymentMarkGuide,
  type PaymentMarkSource,
  type PaymentMarkTransfer
} from '../admin/common-billing/common-manual-payment-attribution-modal.component';
import { ORDER_PAYMENT_GUIDE } from './order-payment-guide';

export interface ManagerManualCardPaymentCompleted {
  context: ManualCardPaymentContext;
  recipient: ManualCardPaymentRecipientOption;
  result: ManagerManualCardPaymentResult;
}

export function manualCardRecipientKey(candidate: ManualCardPaymentRecipientOption): string {
  return taskAwareRecipientKey(candidate);
}

function matchingManualCardRecipient(
  candidates: readonly ManualCardPaymentRecipientOption[],
  expectedRecipient: ManualCardPaymentRecipientOption | null | undefined
): ManualCardPaymentRecipientOption | null {
  if (!expectedRecipient) {
    return null;
  }
  const expectedKey = manualCardRecipientKey(expectedRecipient);
  if (!expectedKey) {
    return null;
  }
  return candidates.find((candidate) => manualCardRecipientKey(candidate) === expectedKey) ?? null;
}

export function originalManualCardRecipient(
  candidates: readonly ManualCardPaymentRecipientOption[],
  originalRecipient: ManualCardPaymentRecipientOption
): ManualCardPaymentRecipientOption | null {
  return matchingManualCardRecipient(candidates, originalRecipient);
}

export function manualCardPaymentSelectionRecipient(
  context: ManualCardPaymentContext
): ManualCardPaymentRecipientOption | null {
  return context.recipientSelectionFrozen
    ? matchingManualCardRecipient(context.candidates ?? [], context.preparedRecipient)
    : originalManualCardRecipient(context.candidates ?? [], context.originalRecipient);
}

export function manualCardPaymentSubmission(
  context: ManualCardPaymentContext,
  selectedRecipient: ManualCardPaymentRecipientOption | null,
  reasonValue: string,
  receiptUrlValue: string
): { recipient: ManualCardPaymentRecipientOption; reason: string; receiptUrl: string | null } | null {
  const recipient = context.recipientSelectionFrozen
    ? manualCardPaymentSelectionRecipient(context)
    : selectedRecipient;
  if (!recipient) {
    return null;
  }
  return {
    recipient,
    reason: context.recipientSelectionFrozen ? (context.preparedReason ?? '') : reasonValue.trim(),
    receiptUrl: context.recipientSelectionFrozen ? context.preparedReceiptUrl : (receiptUrlValue.trim() || null)
  };
}

/** A payment made before the recipient accounting was switched on: it only closes the old order. */
const HISTORICAL_RECIPIENT_KEY = 'LEGACY_PRE_CUTOVER_MANUAL_CARD';

const ORDER_PAYMENT_MARK_GUIDE: PaymentMarkGuide = {
  sections: ORDER_PAYMENT_GUIDE,
  section: 'paid',
  heading: 'Счёт и оплата заказа',
  intro: 'Как заказ получает счёт, как отметить оплату и сменить получателя, что делать, если клиент не платит.'
};

function paymentWindowCandidate(
  candidate: ManualCardPaymentRecipientOption,
  originalKey: string,
  ownToTheOrder: boolean
): CommonManualPaymentRecipientCandidate {
  const key = manualCardRecipientKey(candidate);
  return {
    key,
    cashDestinationKind: candidate.cashDestinationKind ?? null,
    recipientType: candidate.recipientType ?? null,
    recipientProfileId: candidate.recipientProfileId ?? null,
    recipientUserId: candidate.recipientUserId ?? null,
    label: manualPaymentRecipientLabel(candidate),
    originalRecipient: key === originalKey,
    currentParticipant: ownToTheOrder,
    // A switched-off profile is not offered for an order at all.
    profileEnabled: true,
    availableKopecks: candidate.availableKopecks ?? null,
    manualPaymentTaskId: candidate.manualPaymentTaskId ?? null,
    manualPaymentTaskGeneration: candidate.manualPaymentTaskGeneration ?? null,
    taskTargetKind: candidate.taskTargetKind ?? null,
    taskRecipientName: candidate.taskRecipientName ?? null,
    accountingTargetLabel: candidate.accountingTargetLabel ?? null,
    effectText: candidate.effectText ?? null
  };
}

/**
 * The recipients of an order in the shape the «Отметить оплату» window of a common invoice reads.
 * The listed ones are the order's own people — the recipient of its invoice, its specialist and its
 * manager, the owner first as in a common invoice; the rest of the manager's team is found through
 * «Другой получатель». The recipient of the invoice is chosen at once, exactly as for a common invoice.
 */
export function manualCardPaymentWindowOptions(context: ManualCardPaymentContext): {
  options: CommonManualPaymentOptions;
  others: CommonManualPaymentOtherRecipient[];
} {
  const candidates = context.candidates ?? [];
  const frozen = context.recipientSelectionFrozen ? manualCardPaymentSelectionRecipient(context) : null;
  const ownerOffered = candidates.some(candidate => candidate.recipientType === 'OWNER');
  // A server that does not name the route: the owner of the invoice missing from the list means a bank link.
  const bankLink = context.bankLinkRoute ?? (!isManualPaymentTaskRecipient(context.originalRecipient)
    && context.originalRecipient.recipientType === 'OWNER' && !ownerOffered);
  // The recipient of a bank link is the bank: nobody in the list is «в счёте», and nobody is chosen in
  // advance — the usual payment by the link needs no mark at all.
  const originalKey = bankLink && !frozen ? '' : manualCardRecipientKey(context.originalRecipient);
  const team = new Set(context.teamRecipientKeys ?? []);
  let own = candidates.filter(candidate => !team.has(manualCardRecipientKey(candidate)));
  if (!own.length) {
    // A server that does not tell the team apart: everyone is listed.
    own = candidates;
  }
  if (frozen) {
    // An earlier attempt has fixed the recipient on the server: only that one can be repeated.
    own = [frozen];
  }
  const listed = [
    ...own.filter(candidate => candidate.recipientType === 'OWNER'),
    ...own.filter(candidate => candidate.recipientType !== 'OWNER')
  ];
  const notice = frozen
    ? 'Восстанавливаем ранее подготовленное подтверждение. Получатель, комментарий и чек уже зафиксированы сервером: повтор отправит те же данные.'
    : !bankLink
      ? null
      : ownerOffered
        ? 'Счёт этого заказа — ссылка банка владельца. Оплату по ссылке банк отмечает сам: отмечать её здесь не нужно. '
          + 'Если клиент перевёл мимо ссылки владельцу на карту — выберите «Владелец»: запрос уйдёт владельцу, '
          + 'и заказ закроется только после его подтверждения. Если клиент перевёл сотруднику — выберите сотрудника.'
        : 'Счёт этого заказа — ссылка банка владельца. Оплату по ссылке банк отмечает сам, а на карту этот владелец '
          + 'оплату не принимает, поэтому его в списке нет. Выберите сотрудника, только если клиент перевёл деньги ему напрямую.';
  return {
    options: {
      ownerReport: null,
      invoiceId: context.orderId,
      remainingKopecks: context.amountKopecks,
      defaultRecipientKey: frozen ? manualCardRecipientKey(frozen) : originalKey,
      contractVersion: context.contractVersion ?? null,
      routeRevision: context.routeRevision ?? null,
      candidates: listed.map(candidate => paymentWindowCandidate(candidate, originalKey, true)),
      history: [],
      notice,
      initialRecipientKey: frozen ? manualCardRecipientKey(frozen) : null
    },
    others: frozen ? [] : candidates
      .filter(candidate => !own.includes(candidate))
      .map(candidate => ({
        candidate: paymentWindowCandidate(candidate, originalKey, false),
        previousPerformer: false,
        teamMember: true
      }))
  };
}

/**
 * «оплатили» of an ordinary order. It is the «Отметить оплату» window of a common invoice itself:
 * this component only gives it the recipients of the order and saves the mark through the order's API.
 */
@Component({
  selector: 'app-manager-manual-card-payment-modal',
  imports: [CommonManualPaymentAttributionModalComponent],
  template: `
    <app-common-manual-payment-attribution-modal
      [source]="source"
      [kicker]="'Заказ №' + order.id"
      [invoiceTitle]="order.companyTitle || ''"
      [guide]="guide"
      [splitAllowed]="false"
      (closed)="closed.emit()"
      (settled)="settle($event)"
    />
  `
})
export class ManagerManualCardPaymentModalComponent {
  private readonly managerApi = inject(ManagerApi);

  @Input({ required: true }) order!: OrderCardItem;

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly completed = new EventEmitter<ManagerManualCardPaymentCompleted>();

  readonly guide = ORDER_PAYMENT_MARK_GUIDE;
  /** The recipients as the server returned them last: the mark is sent by their exact keys. */
  private context: ManualCardPaymentContext | null = null;
  private others: CommonManualPaymentOtherRecipient[] = [];

  readonly source: PaymentMarkSource = {
    ownerApprovalConsequence: 'Поступление владельцу подтверждает сам владелец: ему придёт сообщение с кнопкой в рабочую группу '
      + 'менеджера в Телеграме. Заказ станет оплаченным после его подтверждения; до этого ничего не закрывается.',
    options: () => this.managerApi.getManualCardPaymentContext(this.order.id).pipe(map(context => this.accept(context))),
    otherRecipients: () => of({ recipients: this.others }),
    confirm: (request, transfer) => defer(() => from(this.confirm(request, transfer))),
    transferLine: (orderId, recipientProfileId) => this.managerApi.getManualCardPaymentContext(orderId).pipe(map(other => {
      if (other.recipientSelectionFrozen || !(other.candidates ?? []).some(candidate => candidate.recipientProfileId === recipientProfileId)) {
        throw new Error('По этому заказу выбранный получатель недоступен или уже зарегистрирован перевод.');
      }
      return { orderId, amountKopecks: other.amountKopecks };
    }))
  };

  settle(outcome: unknown): void {
    this.completed.emit(outcome as ManagerManualCardPaymentCompleted);
  }

  private accept(context: ManualCardPaymentContext): CommonManualPaymentOptions {
    const candidates = context.candidates ?? [];
    if (!candidates.length) {
      throw new Error('Сервер не вернул доступных получателей оплаты.');
    }
    if (candidates.some(candidate => !manualCardRecipientKey(candidate))) {
      throw new Error('Сервер вернул получателя без безопасного ключа маршрута. Оплата не изменена.');
    }
    const normalized = { ...context, candidates };
    if (context.recipientSelectionFrozen) {
      if (!manualCardPaymentSelectionRecipient(normalized)) {
        throw new Error('Ранее выбранный получатель отсутствует в списке доступных получателей. Повтор оплаты заблокирован.');
      }
      const preparedReason = context.preparedReason ?? '';
      if (!preparedReason.trim() || preparedReason.length > 500 || (context.preparedReceiptUrl ?? '').length > 1024) {
        throw new Error('Сохранённые данные повторной оплаты повреждены. Повтор оплаты заблокирован.');
      }
    }
    const view = manualCardPaymentWindowOptions(normalized);
    this.context = normalized;
    this.others = view.others;
    return view.options;
  }

  private async confirm(
    request: CommonManualPaymentAttributionRequest,
    transfer: PaymentMarkTransfer | null
  ): Promise<ManagerManualCardPaymentCompleted> {
    const context = this.context;
    const line = request.attributions[0];
    const chosen = context?.candidates.find(candidate => manualCardRecipientKey(candidate) === line?.recipientKey) ?? null;
    const submission = context ? manualCardPaymentSubmission(context, chosen, request.reason, request.receiptUrl) : null;
    if (!context || !submission || request.attributions.length !== 1) {
      throw new Error('Выбранный получатель больше недоступен. Закройте окно и откройте его заново.');
    }
    const payload: ManualCardPaymentConfirmationRequest = {
      reason: submission.reason,
      receiptUrl: submission.receiptUrl,
      recipientKey: manualCardRecipientKey(submission.recipient),
      recipientType: submission.recipient.recipientType,
      recipientProfileId: submission.recipient.recipientProfileId ?? null
    };
    let result: ManagerManualCardPaymentResult;
    if (transfer?.lines.length) {
      const group = prepareManualTransfer(
        [{ orderId: context.orderId, amountKopecks: context.amountKopecks }, ...transfer.lines as ManualTransferLine[]],
        transfer.reference, transfer.totalRubles, submission.recipient.recipientProfileId, submission.reason, submission.receiptUrl);
      const results = await firstValueFrom(this.managerApi.recordTransfer(group));
      const pending = results.filter(item => item.status !== 'COMPLETED');
      if (pending.length) {
        throw new Error('Перевод зарегистрирован. Требуется сверка заказов ' + pending.map(item => '№' + item.orderId).join(', '));
      }
      const current = results.find(item => item.orderId === context.orderId);
      if (!current) {
        throw new Error('Проверьте результат распределения перевода.');
      }
      result = current;
    } else {
      result = await firstValueFrom(this.managerApi.confirmManualCardPayment(this.order.id, payload));
    }
    return { context, recipient: submission.recipient, result };
  }
}
