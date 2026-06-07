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
  OrderUpdateRequest
} from '../../core/manager.api';
import type { ToastService } from '../../shared/toast.service';
import {
  readSessionDraft,
  removeSessionDraft,
  writeSessionDraft
} from '../../shared/session-draft-storage';
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
>;

type ManagerBoardOrderToast = Pick<ToastService, 'success' | 'error'>;

export type ManagerBoardOrderFacadeDeps = {
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
    if (this.orderLoading() || this.orderSaving() || this.orderDeleting() || this.orderCancelingPayment()) {
      return;
    }

    this.removeOrderEditSessionDraft();
    this.editOrder.set(null);
    this.orderDraft.set(null);
    this.orderError.set(null);
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

    const confirmed = window.confirm(`Удалить заказ #${order.id}?`);
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
      `Отменить оплату заказа #${order.id}? ЗП и чек будут деактивированы, следующий заказ отменен, заказ вернется в "Напоминание".`
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

  private applyOrderEditPayload(payload: OrderEditPayload): void {
    const sourceDraft = managerOrderEditDraft(payload);
    const storedDraft = readSessionDraft<OrderUpdateRequest>(this.orderEditSessionDraftKey(payload.id));
    this.editOrder.set(payload);
    this.orderDraft.set(storedDraft ?? sourceDraft);
    this.writeOrderEditSessionDraft();
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
    if (!this.isOrderEditDraftChanged(order, draft)) {
      removeSessionDraft(key);
      return;
    }

    writeSessionDraft(key, draft);
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

  private isOrderEditDraftChanged(order: OrderEditPayload, draft: OrderUpdateRequest): boolean {
    const source = managerOrderEditDraft(order);
    return (Object.keys(source) as (keyof OrderUpdateRequest)[]).some((field) => source[field] !== draft[field]);
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
