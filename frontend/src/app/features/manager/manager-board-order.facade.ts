import { computed, signal } from '@angular/core';
import type {
  CompanyCardItem,
  CompanyOrderCreatePayload,
  CompanyOrderCreateRequest,
  CompanyOrderCreateResult,
  ManagerBoard,
  ManagerApi,
  OrderCardItem,
  OrderEditPayload,
  PaymentRouteChangeContext,
  PaymentRouteChangeTarget,
  OrderUpdateRequest
} from '../../core/manager.api';
import type { ToastService } from '../../shared/toast.service';
import type { PaymentRecipientChoice, PaymentRecipientOption } from '../../shared/payment-recipient-picker.component';
import {
  readSessionDraft,
  removeSessionDraft,
  writeSessionDraft
} from '../../shared/session-draft-storage';
import { describeFieldDraftConflicts, fieldDraftOf, restoreFieldDraft } from '../../shared/field-draft';
import {
  managerCreateOrderDraft,
  managerCreateOrderTotal,
  managerCreateOrderValidationError,
  managerOrderEditDraft,
  managerSelectedCreateOrderProduct
} from './manager-board.order.helpers';
import type { ManagerCreateOrderDraftChange } from './manager-order-create-modal.component';
import type { ManagerOrderEditDraftChange } from './manager-order-edit-modal.component';

type ManagerBoardOrderApi = Pick<
  ManagerApi,
  | 'getCompanyOrderCreate'
  | 'createCompanyOrder'
  | 'getOrderEdit'
  | 'updateOrder'
  | 'deleteOrder'
  | 'cancelOrderPayment'
  | 'getPaymentRouteChangeContext'
  | 'getPaymentRouteRecipients'
  | 'changePaymentRoute'
  | 'markPaperInvoiceIssued'
>;

type ManagerBoardOrderToast = Pick<ToastService, 'success' | 'error'> & Partial<Pick<ToastService, 'warning'>>;

const ORDER_EDIT_FIELD_LABELS: Partial<Record<keyof OrderUpdateRequest, string>> = {
  filialId: 'Филиал',
  workerId: 'Специалист',
  managerId: 'Менеджер',
  counter: 'Опубликовано',
  orderComments: 'Комментарий заказа',
  commentsCompany: 'Комментарий компании',
  complete: 'Отметка о выполнении',
  removePreviousWorkerFromCompany: 'Удалить прежнего специалиста из компании'
};

export type ManagerBoardOrderFacadeDeps = {
  openPaperInvoice?: (target: { kind: 'ORDER'; id: number }, fallback: () => void, refresh: () => void) => void;
  managerApi: ManagerBoardOrderApi;
  toastService: ManagerBoardOrderToast;
  loadBoard: () => void;
  patchBoard?: (updater: (board: ManagerBoard) => ManagerBoard) => void;
  errorMessage: (err: unknown, fallback: string) => string;
  openCreatedCompanyOrders: (result: CompanyOrderCreateResult) => void;
};

export class ManagerBoardOrderFacade {
  readonly editOrder = signal<OrderEditPayload | null>(null);
  readonly orderDraft = signal<OrderUpdateRequest | null>(null);
  readonly orderLoading = signal(false);
  readonly orderSaving = signal(false);
  readonly orderError = signal<string | null>(null);
  readonly orderDeleting = signal(false);
  readonly paymentRouteContext = signal<PaymentRouteChangeContext | null>(null);
  readonly paymentRouteContextLoading = signal(false);
  readonly paymentRouteChanging = signal(false);
  /** The «Выдать номер карты» window of the order: what it was opened with, the people and the last refusal. */
  readonly recipientPicker = signal<{
    orderId: number;
    target: PaymentRouteChangeTarget;
    paymentLinkId: number;
    currentRecipient: string;
    autoHint: string;
  } | null>(null);
  readonly recipientPickerOptions = signal<PaymentRecipientOption[] | null>(null);
  readonly recipientPickerAmount = signal(0);
  readonly recipientPickerLoading = signal(false);
  readonly recipientPickerError = signal('');
  readonly recipientPickerSubmitError = signal('');
  readonly orderCancelingPayment = signal(false);
  readonly createOrderPayload = signal<CompanyOrderCreatePayload | null>(null);
  readonly createOrderDraft = signal<CompanyOrderCreateRequest | null>(null);
  readonly createOrderLoading = signal(false);
  readonly createOrderSaving = signal(false);
  readonly createOrderError = signal<string | null>(null);

  readonly selectedCreateOrderProduct = computed(() => {
    return managerSelectedCreateOrderProduct(this.createOrderPayload(), this.createOrderDraft());
  });
  readonly createOrderTotal = computed(() => {
    return managerCreateOrderTotal(this.createOrderPayload(), this.createOrderDraft());
  });

  constructor(private readonly deps: ManagerBoardOrderFacadeDeps) {}

  openCompanyOrderCreate(company: CompanyCardItem): void {
    this.createOrderLoading.set(true);
    this.createOrderSaving.set(false);
    this.createOrderError.set(null);
    this.createOrderPayload.set(null);
    this.createOrderDraft.set(null);

    this.deps.managerApi.getCompanyOrderCreate(company.id).subscribe({
      next: (payload) => {
        this.applyCompanyOrderCreatePayload(payload);
        this.createOrderLoading.set(false);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось открыть создание заказа');
        this.createOrderLoading.set(false);
        this.createOrderError.set(message);
        this.deps.toastService.error('Заказ не открыт', message);
      }
    });
  }

  closeCompanyOrderCreate(): void {
    if (this.createOrderLoading() || this.createOrderSaving()) {
      return;
    }

    this.removeCreateOrderSessionDraft();
    this.createOrderPayload.set(null);
    this.createOrderDraft.set(null);
    this.createOrderError.set(null);
  }

  handleCreateOrderDraftChange(change: ManagerCreateOrderDraftChange): void {
    this.createOrderDraft.update((draft) => draft ? { ...draft, [change.field]: change.value } : draft);
    this.writeCreateOrderSessionDraft();
  }

  createCompanyOrder(): void {
    const payload = this.createOrderPayload();
    const draft = this.createOrderDraft();

    if (!payload || !draft) {
      return;
    }

    const validationError = managerCreateOrderValidationError(draft);
    if (validationError) {
      this.createOrderError.set(validationError);
      return;
    }

    this.createOrderSaving.set(true);
    this.createOrderError.set(null);

    this.deps.managerApi.createCompanyOrder(payload.companyId, draft).subscribe({
      next: (result) => {
        this.createOrderSaving.set(false);
        this.closeCompanyOrderCreate();
        this.deps.toastService.success('Заказ создан', `${result.companyTitle}: ${result.productTitle} x ${result.amount}`);
        this.deps.openCreatedCompanyOrders(result);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось создать заказ');
        this.createOrderSaving.set(false);
        this.createOrderError.set(message);
        this.deps.toastService.error('Заказ не создан', message);
      }
    });
  }

  openOrderEdit(order: OrderCardItem): void {
    this.orderLoading.set(true);
    this.paymentRouteContext.set(null);
    this.paymentRouteContextLoading.set(false);
    this.paymentRouteChanging.set(false);
    this.orderError.set(null);
    this.orderDeleting.set(false);
    this.orderCancelingPayment.set(false);

    this.deps.managerApi.getOrderEdit(order.id).subscribe({
      next: (payload) => {
        this.applyOrderEditPayload(payload);
        this.orderLoading.set(false);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось открыть редактирование заказа');
        this.orderLoading.set(false);
        this.orderError.set(message);
        this.deps.toastService.error('Заказ не открыт', message);
      }
    });
  }

  closeOrderEdit(): void {
    if (this.orderLoading() || this.orderSaving() || this.orderDeleting()
        || this.orderCancelingPayment() || this.paymentRouteChanging()) {
      return;
    }

    this.removeOrderEditSessionDraft();
    this.editOrder.set(null);
    this.orderDraft.set(null);
    this.orderError.set(null);
    this.paymentRouteContext.set(null);
  }

  handleOrderEditDraftChange(change: ManagerOrderEditDraftChange): void {
    this.orderDraft.update((draft) => draft ? { ...draft, [change.field]: change.value } : draft);
    this.writeOrderEditSessionDraft();
  }

  saveOrderEdit(): void {
    const order = this.editOrder();
    const draft = this.orderDraft();

    if (!order || !draft || this.orderCancelingPayment()) {
      return;
    }

    this.orderSaving.set(true);
    this.orderError.set(null);

    this.deps.managerApi.updateOrder(order.id, draft).subscribe({
      next: (payload) => {
        this.applyOrderCardPatch(payload);
        this.orderSaving.set(false);
        this.closeOrderEdit();
        this.deps.toastService.success('Заказ сохранен', `Изменения по заказу #${order.id} применены`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось сохранить заказ');
        this.orderError.set(message);
        this.orderSaving.set(false);
        this.deps.toastService.error('Заказ не сохранен', message);
      }
    });
  }

  deleteOrderEdit(): void {
    const order = this.editOrder();

    if (!order || this.orderDeleting() || this.orderCancelingPayment()) {
      return;
    }

    const confirmed = window.confirm(
      `Удалить заказ #${order.id}?\n\n` +
      'Если заказ входит в неотправленный общий счёт, он будет исключён из него: ' +
      'сумма счёта пересчитается, неиспользованный резерв освободится.\n' +
      'Заказ и его отзывы будут удалены. Для отмены нажмите «Отмена».'
    );
    if (!confirmed) {
      return;
    }

    this.orderDeleting.set(true);
    this.orderError.set(null);

    this.deps.managerApi.deleteOrder(order.id).subscribe({
      next: () => {
        this.orderDeleting.set(false);
        this.closeOrderEdit();
        this.deps.toastService.success('Заказ удален', `Заказ #${order.id} удален`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось удалить заказ');
        this.orderDeleting.set(false);
        this.orderError.set(message);
        this.deps.toastService.error('Заказ не удален', message);
      }
    });
  }

  cancelOrderPayment(): void {
    const order = this.editOrder();

    if (!order || this.orderCancelingPayment()) {
      return;
    }

    const confirmed = window.confirm(
      `Отменить оплату заказа #${order.id}? Начисления и чек будут деактивированы, следующий заказ отменен, заказ вернется в "Напоминание".`
    );
    if (!confirmed) {
      return;
    }

    this.orderCancelingPayment.set(true);
    this.orderError.set(null);

    this.deps.managerApi.cancelOrderPayment(order.id).subscribe({
      next: (payload) => {
        this.applyOrderCardPatch(payload);
        this.applyOrderEditPayload(payload);
        this.orderCancelingPayment.set(false);
        this.deps.toastService.success('Оплата отменена', `Заказ #${order.id} возвращен в Напоминание`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось отменить оплату');
        this.orderCancelingPayment.set(false);
        this.orderError.set(message);
        this.deps.toastService.error('Оплата не отменена', message);
      }
    });
  }
  openPaymentRouteChange(): void {
    const order = this.editOrder();
    if (!order || this.paymentRouteContextLoading() || this.paymentRouteChanging()) {
      return;
    }
    this.paymentRouteContextLoading.set(true);
    this.orderError.set(null);
    this.deps.managerApi.getPaymentRouteChangeContext(order.id).subscribe({
      next: (context) => {
        this.paymentRouteContext.set(context);
        this.paymentRouteContextLoading.set(false);
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось проверить текущий способ оплаты');
        this.paymentRouteContextLoading.set(false);
        this.orderError.set(message);
        this.deps.toastService.error('Способ оплаты не открыт', message);
      }
    });
  }

  closePaymentRouteChange(): void {
    if (!this.paymentRouteChanging()) {
      this.paymentRouteContext.set(null);
    }
  }

  changePaymentRoute(target: PaymentRouteChangeTarget, legacyPaper = false): void {
    const order = this.editOrder();
    if (order && target === 'OWNER_PAPER_INVOICE' && !legacyPaper && this.deps.openPaperInvoice) {
      this.deps.openPaperInvoice({ kind: 'ORDER', id: order.id }, () => this.changePaymentRoute(target, true), this.deps.loadBoard);
      return;
    }
    const context = this.paymentRouteContext();
    if (!order || !context || (!context.canChange && !(target !== 'OWNER_TBANK' && context.bankRecipientChangeAvailable)) || this.paymentRouteChanging()) {
      return;
    }
    if ((target === 'EMPLOYEE_REQUISITES' || target === 'DIFFERENT_RECIPIENT')
      && context.paymentLinkId != null && !context.paperInvoiceIssued) {
      // A card number for an issued invoice is given from the window: the system's own choice
      // or a person the manager names.
      this.recipientPickerSubmitError.set('');
      this.recipientPickerOptions.set(null);
      this.recipientPickerAmount.set(0);
      this.recipientPicker.set({
        orderId: order.id,
        target,
        paymentLinkId: context.paymentLinkId,
        currentRecipient: context.currentRecipient?.trim() ?? '',
        autoHint: target === 'DIFFERENT_RECIPIENT'
          ? 'Система выдаст номер другого получателя — платёжного задания или менеджера, если у него хватает резерва. '
            + 'Нынешний получатель исключается. Если не подойдёт никто, счёт не изменится.'
          : 'Система возьмёт платёжное задание, затем специалиста заказа, затем менеджера — первого, у кого хватает резерва. '
            + 'Если не подойдёт никто, счёт не изменится и ссылка останется действующей.'
      });
      this.loadRecipientPickerOptions();
      return;
    }
    const targetTitle = target === 'EMPLOYEE_REQUISITES'
      ? 'номер карты вместо ссылки (получатель может остаться прежним)'
      : target === 'DIFFERENT_RECIPIENT' ? 'номер карты другого получателя'
      : target === 'OWNER_TBANK'
        ? 'ссылку банка владельца'
        : 'счёт и акт Точки';
    if ((target !== 'DIFFERENT_RECIPIENT' || context.paperInvoiceIssued) && !window.confirm(
      (context.paperInvoiceIssued ? 'Подтвердите, что выписка проверена, денег нет и клиент уведомлён об отзыве бумажного счёта. ' : '') + `Клиент ещё не оплатил по прежним реквизитам? Старый счёт будет закрыт, клиент сразу получит ${targetTitle}.`
    )) {
      return;
    }

    this.paymentRouteChanging.set(true);
    this.orderError.set(null);
    this.deps.managerApi.changePaymentRoute(order.id, {
      expectedPaymentLinkId: context.paymentLinkId,
      target,
      confirmedUnpaid: true,
        ...(context.paperInvoiceIssued ? { paperInvoiceWithdrawn: true } : {}),
      expectedTargetPaymentProfileId: target === 'OWNER_TBANK'
        ? context.expectedTargetPaymentProfileId ?? null
        : null
    }).subscribe({
      next: (response) => {
        this.paymentRouteChanging.set(false);
        this.paymentRouteContext.set(null);
        this.deps.toastService.success('Способ оплаты изменен',
          response.clientNotificationScheduled
            ? `Клиенту отправляется новый способ оплаты по заказу #${order.id}`
            : `Режим сохранён для следующей отправки счёта по заказу #${order.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось изменить способ оплаты');
        this.paymentRouteChanging.set(false);
        if (err?.status === 423) {
          this.deps.toastService.success('Запрос на смену принят', message);
        } else {
          this.orderError.set(message);
          this.deps.toastService.error('Способ оплаты не изменен', message);
        }
        this.openPaymentRouteChange();
      }
    });
  }
  loadRecipientPickerOptions(): void {
    const picker = this.recipientPicker();
    if (!picker) {
      return;
    }
    this.recipientPickerLoading.set(true);
    this.recipientPickerError.set('');
    this.deps.managerApi.getPaymentRouteRecipients(picker.orderId).subscribe({
      next: (response) => {
        if (this.recipientPicker()?.orderId !== picker.orderId) {
          return;
        }
        this.recipientPickerOptions.set(response.recipients ?? []);
        this.recipientPickerAmount.set(response.amountKopecks ?? 0);
        this.recipientPickerLoading.set(false);
      },
      error: (err) => {
        if (this.recipientPicker()?.orderId !== picker.orderId) {
          return;
        }
        this.recipientPickerError.set(this.deps.errorMessage(err, 'Не удалось загрузить сотрудников'));
        this.recipientPickerLoading.set(false);
      }
    });
  }

  closeRecipientPicker(): void {
    if (this.paymentRouteChanging()) {
      return;
    }
    this.recipientPicker.set(null);
    this.recipientPickerOptions.set(null);
    this.recipientPickerSubmitError.set('');
  }

  /** Issues the card number: the system's own choice, or the person the manager named. */
  submitRecipientChoice(choice: PaymentRecipientChoice): void {
    const picker = this.recipientPicker();
    if (!picker || this.paymentRouteChanging()) {
      return;
    }
    this.paymentRouteChanging.set(true);
    this.recipientPickerSubmitError.set('');
    this.orderError.set(null);
    this.deps.managerApi.changePaymentRoute(picker.orderId, {
      expectedPaymentLinkId: picker.paymentLinkId,
      target: picker.target,
      confirmedUnpaid: true,
      expectedTargetPaymentProfileId: null,
      ...(choice.profileId != null ? { recipientProfileId: choice.profileId } : {})
    }).subscribe({
      next: () => {
        this.paymentRouteChanging.set(false);
        this.closeRecipientPicker();
        this.paymentRouteContext.set(null);
        this.deps.toastService.success('Способ оплаты изменен',
          `Клиенту отправляется новый счёт с номером карты по заказу #${picker.orderId}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось выдать номер карты');
        this.paymentRouteChanging.set(false);
        if (err?.status === 423) {
          // The bank has to close the old link first; the request is saved and will finish by itself.
          this.closeRecipientPicker();
          this.deps.toastService.success('Запрос на смену принят', message);
          this.openPaymentRouteChange();
          return;
        }
        // The window stays open with the refusal, so another person can be chosen at once.
        this.recipientPickerSubmitError.set(message);
      }
    });
  }

  openPaperInvoice(): void {
    const order=this.editOrder();
    if(order && !this.orderSaving() && !this.paymentRouteChanging()) this.deps.openPaperInvoice?.({kind:'ORDER',id:order.id},()=>this.deps.toastService.error('Бумажный счёт недоступен','Владелец ещё не включил выпуск документов Точки'),this.deps.loadBoard);
  }

  markPaperInvoiceIssued(): void {
    const order = this.editOrder();
    const context = this.paymentRouteContext();
    if (!order || context?.configuredMode !== 'OWNER_PAPER_INVOICE'
      || context.paperInvoiceIssued || context.paymentLinkId == null || this.paymentRouteChanging()) {
      return;
    }
    if (!window.confirm('Подтвердить, что бумажный счёт уже отправлен клиенту? После этого начнутся напоминания.')) {
      return;
    }
    this.paymentRouteChanging.set(true);
    this.deps.managerApi.markPaperInvoiceIssued(order.id).subscribe({
      next: () => {
        this.paymentRouteChanging.set(false);
        this.deps.toastService.success('Бумажный счёт отмечен как отправленный');
        this.openPaymentRouteChange();
      },
      error: (err) => {
        const message = this.deps.errorMessage(err, 'Не удалось отметить отправку бумажного счёта');
        this.paymentRouteChanging.set(false);
        this.orderError.set(message);
        this.deps.toastService.error('Отметка не сохранена', message);
      }
    });
  }


  private applyOrderEditPayload(payload: OrderEditPayload): void {
    // Only fields the user changed are restored over fresh server data, so a
    // reload never reverts what other users saved in the untouched fields.
    const { draft, conflicts } = restoreFieldDraft(
      managerOrderEditDraft(payload),
      readSessionDraft<unknown>(this.orderEditSessionDraftKey(payload.id))
    );
    this.editOrder.set(payload);
    this.orderDraft.set(draft);
    this.writeOrderEditSessionDraft();
    if (conflicts.length > 0) {
      this.deps.toastService.warning?.(
        'Заказ изменил другой пользователь',
        `После начала вашего редактирования изменены поля: ${describeFieldDraftConflicts(conflicts, ORDER_EDIT_FIELD_LABELS)}. `
          + 'В форме оставлены ваши несохранённые значения — проверьте их перед сохранением.',
        // An action keeps the warning on screen until the user closes it.
        { label: 'Понятно', callback: () => undefined }
      );
    }
  }

  private applyCompanyOrderCreatePayload(payload: CompanyOrderCreatePayload): void {
    const sourceDraft = managerCreateOrderDraft(payload);
    const storedDraft = readSessionDraft<CompanyOrderCreateRequest>(this.createOrderSessionDraftKey(payload.companyId));
    this.createOrderPayload.set(payload);
    this.createOrderDraft.set(storedDraft ?? sourceDraft);
    this.writeCreateOrderSessionDraft();
  }

  private writeOrderEditSessionDraft(): void {
    const order = this.editOrder();
    const draft = this.orderDraft();
    if (!order || !draft) {
      return;
    }

    const key = this.orderEditSessionDraftKey(order.id);
    const changes = fieldDraftOf(managerOrderEditDraft(order), draft);
    if (!changes) {
      removeSessionDraft(key);
      return;
    }

    writeSessionDraft(key, changes);
  }

  private writeCreateOrderSessionDraft(): void {
    const payload = this.createOrderPayload();
    const draft = this.createOrderDraft();
    if (!payload || !draft) {
      return;
    }

    const key = this.createOrderSessionDraftKey(payload.companyId);
    if (!this.isCreateOrderDraftChanged(payload, draft)) {
      removeSessionDraft(key);
      return;
    }

    writeSessionDraft(key, draft);
  }

  private removeOrderEditSessionDraft(orderId = this.editOrder()?.id): void {
    if (orderId) {
      removeSessionDraft(this.orderEditSessionDraftKey(orderId));
    }
  }

  private removeCreateOrderSessionDraft(companyId = this.createOrderPayload()?.companyId): void {
    if (companyId) {
      removeSessionDraft(this.createOrderSessionDraftKey(companyId));
    }
  }

  private orderEditSessionDraftKey(orderId: number): string {
    return `manager-order-edit:${orderId}`;
  }

  private createOrderSessionDraftKey(companyId: number): string {
    return `manager-order-create:${companyId}`;
  }

  private isCreateOrderDraftChanged(
    payload: CompanyOrderCreatePayload,
    draft: CompanyOrderCreateRequest
  ): boolean {
    const source = managerCreateOrderDraft(payload);
    return (Object.keys(source) as (keyof CompanyOrderCreateRequest)[]).some((field) => source[field] !== draft[field]);
  }

  private applyOrderCardPatch(payload: OrderEditPayload): void {
    this.deps.patchBoard?.((board) => ({
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.map((order) => order.id === payload.id
          ? {
              ...order,
              filialTitle: payload.filial?.label ?? order.filialTitle,
              status: payload.status,
              sum: payload.sum,
              amount: payload.amount,
              counter: payload.counter,
              orderComments: payload.orderComments,
              companyComments: payload.commentsCompany,
              workerUserFio: payload.worker?.label ?? order.workerUserFio,
              changed: payload.changed,
              payDay: payload.payDay
            }
          : order
        )
      }
    }));
  }
}
