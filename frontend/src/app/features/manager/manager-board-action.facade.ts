import type { WritableSignal } from '@angular/core';
import type { CompanyCardItem, ManagerApi, ManagerBoard, OrderCardItem } from '../../core/manager.api';
import type { ToastService } from '../../shared/toast.service';
import type { StatusAction } from './manager-board.config';

type ManagerBoardActionApi = Pick<
  ManagerApi,
  | 'updateCompanyStatus'
  | 'updateOrderStatus'
  | 'confirmManualCardPayment'
  | 'updateOrderClientWaiting'
  | 'updateCompanyNote'
  | 'updateOrderCompanyNote'
  | 'updateOrderNote'
>;

type ManagerBoardActionToast = Pick<ToastService, 'success' | 'error'>;

export type ManagerBoardActionFacadeDeps = {
  managerApi: ManagerBoardActionApi;
  toastService: ManagerBoardActionToast;
  mutationKey: WritableSignal<string | null>;
  loadBoard: () => void;
  patchBoard?: (updater: (board: ManagerBoard) => ManagerBoard) => void;
  errorMessage: (err: unknown, fallback: string) => string;
  canOverrideActiveBankPayment: () => boolean;
};

export class ManagerBoardActionFacade {
  private static readonly ACTIVE_BANK_PAYMENT_CONFLICT =
    'У заказа есть незавершенный T-Bank/СБП платеж. Проверьте его в журнале перед ручным закрытием.';

  constructor(private readonly deps: ManagerBoardActionFacadeDeps) {}

  updateCompanyStatus(company: CompanyCardItem, action: StatusAction): void {
    const key = `company-${company.id}-${action.status}`;
    this.deps.mutationKey.set(key);

    this.deps.managerApi.updateCompanyStatus(company.id, action.status).subscribe({
      next: () => {
        this.patchCompany(company.id, { status: action.status });
        this.deps.mutationKey.set(null);
        this.deps.toastService.success('Статус изменен', `${company.title}: ${action.status}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Статус не изменен', this.deps.errorMessage(err, 'Не удалось изменить статус компании'));
      }
    });
  }

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    const key = `order-${order.id}-${action.status}`;
    this.deps.mutationKey.set(key);

    this.deps.managerApi.updateOrderStatus(order.id, action.status).subscribe({
      next: () => {
        this.patchOrder(order.id, { status: action.status });
        this.deps.mutationKey.set(null);
        this.deps.toastService.success('Статус изменен', `${order.companyTitle}: ${action.status}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        if (this.canFallbackToManualCardPayment(order, action, err)) {
          this.confirmManualCardPayment(order, key);
          return;
        }
        this.deps.mutationKey.set(null);
        this.deps.toastService.error('Статус не изменен', this.deps.errorMessage(err, 'Не удалось изменить статус заказа'));
      }
    });
  }

  private confirmManualCardPayment(order: OrderCardItem, mutationKey: string): void {
    const enteredReason = window.prompt(
      `Почему заказ #${order.id} отмечается оплаченным вручную?`,
      ''
    );
    if (enteredReason === null) {
      this.deps.mutationKey.set(null);
      return;
    }
    const reason = enteredReason.trim();
    if (!reason) {
      this.deps.mutationKey.set(null);
      this.deps.toastService.error('Оплата не отмечена', 'Укажите короткую причину ручной оплаты');
      return;
    }
    if (reason.length > 500) {
      this.deps.mutationKey.set(null);
      this.deps.toastService.error('Оплата не отмечена', 'Причина не должна превышать 500 символов');
      return;
    }

    this.deps.mutationKey.set(mutationKey);
    this.deps.managerApi.confirmManualCardPayment(order.id, {
      reason
    }).subscribe({
      next: () => {
        this.patchOrder(order.id, { status: 'Оплачено', waitingForClient: false });
        this.deps.mutationKey.set(null);
        this.deps.toastService.success(
          'Оплата отмечена',
          `${order.companyTitle}: T-Bank ссылка закрыта, владельцам отправлена причина`
        );
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error(
          'Оплата не отмечена',
          this.deps.errorMessage(err, 'Не удалось безопасно подтвердить перевод на карту')
        );
      }
    });
  }

  private canFallbackToManualCardPayment(order: OrderCardItem, action: StatusAction, err: unknown): boolean {
    if (action.status !== 'Оплачено'
      || order.commonInvoice
      || !this.deps.canOverrideActiveBankPayment()) {
      return false;
    }
    if (!err || typeof err !== 'object' || !('status' in err) || err.status !== 409 || !('error' in err)) {
      return false;
    }
    const payload = err.error;
    const message = typeof payload === 'string'
      ? payload
      : payload && typeof payload === 'object'
        ? ('message' in payload && typeof payload.message === 'string'
          ? payload.message
          : 'detail' in payload && typeof payload.detail === 'string'
            ? payload.detail
            : '')
        : '';
    return message.trim() === ManagerBoardActionFacade.ACTIVE_BANK_PAYMENT_CONFLICT;
  }

  toggleOrderClientWaiting(order: OrderCardItem): void {
    const waitingForClient = !order.waitingForClient;
    const key = `order-${order.id}-client-waiting`;
    this.deps.mutationKey.set(key);

    this.deps.managerApi.updateOrderClientWaiting(order.id, waitingForClient).subscribe({
      next: () => {
        this.patchOrder(order.id, { waitingForClient });
        this.deps.mutationKey.set(null);
        this.deps.toastService.success(
          waitingForClient ? 'Ждет клиента' : 'Вернули в работу',
          order.companyTitle || `Заказ #${order.id}`
        );
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.mutationKey.set(null);
        this.deps.toastService.error(
          'Ожидание не изменено',
          this.deps.errorMessage(err, 'Не удалось изменить ожидание клиента')
        );
      }
    });
  }

  saveCompanyCardNote(company: CompanyCardItem, value: string): void {
    this.deps.managerApi.updateCompanyNote(company.id, value).subscribe({
      next: () => {
        this.patchCompany(company.id, { commentsCompany: value });
        this.deps.toastService.success('Заметка компании сохранена', company.title || `Компания #${company.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.toastService.error('Заметка не сохранена', this.deps.errorMessage(err, 'Не удалось сохранить заметку компании'));
      }
    });
  }

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    this.deps.managerApi.updateOrderCompanyNote(order.id, value).subscribe({
      next: () => {
        this.patchCompany(order.companyId, { commentsCompany: value });
        this.patchOrdersForCompany(order.companyId, { companyComments: value });
        this.deps.toastService.success('Заметка компании сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.toastService.error('Заметка не сохранена', this.deps.errorMessage(err, 'Не удалось сохранить заметку компании'));
      }
    });
  }

  saveOrderCardNote(order: OrderCardItem, value: string): void {
    this.deps.managerApi.updateOrderNote(order.id, value).subscribe({
      next: () => {
        this.patchOrder(order.id, { orderComments: value });
        this.deps.toastService.success('Заметка заказа сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.deps.loadBoard();
      },
      error: (err) => {
        this.deps.toastService.error('Заметка не сохранена', this.deps.errorMessage(err, 'Не удалось сохранить заметку заказа'));
      }
    });
  }

  private patchCompany(companyId: number, patch: Partial<CompanyCardItem>): void {
    this.deps.patchBoard?.((board) => ({
      ...board,
      companies: {
        ...board.companies,
        content: board.companies.content.map((company) => company.id === companyId
          ? { ...company, ...patch }
          : company
        )
      }
    }));
  }

  private patchOrder(orderId: number, patch: Partial<OrderCardItem>): void {
    this.deps.patchBoard?.((board) => ({
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.map((order) => order.id === orderId
          ? { ...order, ...patch }
          : order
        )
      }
    }));
  }

  private patchOrdersForCompany(companyId: number, patch: Partial<OrderCardItem>): void {
    this.deps.patchBoard?.((board) => ({
      ...board,
      orders: {
        ...board.orders,
        content: board.orders.content.map((order) => order.companyId === companyId
          ? { ...order, ...patch }
          : order
        )
      }
    }));
  }
}
