import { computed, signal } from '@angular/core';
import type {
  CompanyCardItem,
  CompanyOrderCreatePayload,
  CompanyOrderCreateRequest,
  CompanyOrderCreateResult,
  ManagerApi,
  OrderCardItem,
  OrderEditPayload,
  OrderUpdateRequest
} from '../../core/manager.api';
import type { ToastService } from '../../shared/toast.service';
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
>;

type ManagerBoardOrderToast = Pick<ToastService, 'success' | 'error'>;

export type ManagerBoardOrderFacadeDeps = {
  managerApi: ManagerBoardOrderApi;
  toastService: ManagerBoardOrderToast;
  loadBoard: () => void;
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

    this.createOrderPayload.set(null);
    this.createOrderDraft.set(null);
    this.createOrderError.set(null);
  }

  handleCreateOrderDraftChange(change: ManagerCreateOrderDraftChange): void {
    this.createOrderDraft.update((draft) => draft ? { ...draft, [change.field]: change.value } : draft);
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
    if (this.orderLoading() || this.orderSaving() || this.orderDeleting()) {
      return;
    }

    this.editOrder.set(null);
    this.orderDraft.set(null);
    this.orderError.set(null);
  }

  handleOrderEditDraftChange(change: ManagerOrderEditDraftChange): void {
    this.orderDraft.update((draft) => draft ? { ...draft, [change.field]: change.value } : draft);
  }

  saveOrderEdit(): void {
    const order = this.editOrder();
    const draft = this.orderDraft();

    if (!order || !draft) {
      return;
    }

    this.orderSaving.set(true);
    this.orderError.set(null);

    this.deps.managerApi.updateOrder(order.id, draft).subscribe({
      next: () => {
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

    if (!order || this.orderDeleting()) {
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

  private applyOrderEditPayload(payload: OrderEditPayload): void {
    this.editOrder.set(payload);
    this.orderDraft.set(managerOrderEditDraft(payload));
  }

  private applyCompanyOrderCreatePayload(payload: CompanyOrderCreatePayload): void {
    this.createOrderPayload.set(payload);
    this.createOrderDraft.set(managerCreateOrderDraft(payload));
  }
}
