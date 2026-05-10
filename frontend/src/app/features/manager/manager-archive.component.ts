import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ArchiveOrderDetailsPayload,
  ArchiveOrderListItem,
  ArchiveOrderMode,
  ArchiveRestoreResult,
  ManagerApi,
  ManagerPage
} from '../../core/manager.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { ToastService } from '../../shared/toast.service';

type ArchiveModeTab = {
  key: ArchiveOrderMode;
  label: string;
  icon: string;
};

type ArchiveSideMetric = {
  label: string;
  value: number | string;
  icon: string;
  tone: 'blue' | 'green' | 'teal' | 'yellow' | 'gray';
};

const EMPTY_ARCHIVE_PAGE: ManagerPage<ArchiveOrderListItem> = {
  content: [],
  number: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
};

@Component({
  selector: 'app-manager-archive',
  imports: [AdminLayoutComponent, DecimalPipe, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './manager-archive.component.html',
  styleUrl: './manager-archive.component.scss'
})
export class ManagerArchiveComponent {
  private readonly managerApi = inject(ManagerApi);
  private readonly toastService = inject(ToastService);

  readonly modeTabs: ArchiveModeTab[] = [
    { key: 'all', label: 'Все закрытые', icon: 'inventory_2' },
    { key: 'archive', label: 'Архив', icon: 'archive' },
    { key: 'paid', label: 'Оплачено', icon: 'payments' }
  ];
  readonly pageSizeOptions = [10, 20, 50];
  readonly ordersPage = signal<ManagerPage<ArchiveOrderListItem>>(EMPTY_ARCHIVE_PAGE);
  readonly mode = signal<ArchiveOrderMode>('all');
  readonly keyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly restoreOrder = signal<ArchiveOrderListItem | null>(null);
  readonly restoreDetails = signal<ArchiveOrderDetailsPayload | null>(null);
  readonly restoreResult = signal<ArchiveRestoreResult | null>(null);
  readonly restoreError = signal<string | null>(null);
  readonly restoreLoading = signal(false);
  readonly restoring = signal(false);
  readonly restoreTargetStatus = signal('Архив');

  readonly orders = computed(() => this.ordersPage().content ?? []);
  readonly rightMetrics = computed<ArchiveSideMetric[]>(() => {
    const orders = this.orders();
    const total = this.ordersPage().totalElements;
    return [
      { label: 'Закрытые', value: total, icon: 'inventory_2', tone: 'blue' },
      { label: 'На странице', value: orders.length, icon: 'view_module', tone: 'green' },
      { label: 'Live', value: orders.filter((order) => order.source === 'live').length, icon: 'database', tone: 'teal' },
      { label: 'В archive_*', value: orders.filter((order) => order.source === 'archive').length, icon: 'archive', tone: 'gray' },
      { label: 'Оплачено', value: orders.filter((order) => order.status === 'Оплачено').length, icon: 'payments', tone: 'yellow' }
    ];
  });

  constructor() {
    this.loadOrders();
  }

  setMode(mode: ArchiveOrderMode): void {
    if (this.mode() === mode) {
      return;
    }

    this.mode.set(mode);
    this.pageNumber.set(0);
    this.loadOrders();
  }

  search(): void {
    this.pageNumber.set(0);
    this.loadOrders();
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  changePageSize(value: string | number): void {
    this.pageSize.set(Number(value));
    this.pageNumber.set(0);
    this.loadOrders();
  }

  previousPage(): void {
    if (this.ordersPage().first) {
      return;
    }

    this.pageNumber.update((page) => Math.max(0, page - 1));
    this.loadOrders();
  }

  nextPage(): void {
    if (this.ordersPage().last) {
      return;
    }

    this.pageNumber.update((page) => page + 1);
    this.loadOrders();
  }

  refresh(): void {
    this.loadOrders();
  }

  trackMode(_index: number, tab: ArchiveModeTab): ArchiveOrderMode {
    return tab.key;
  }

  trackOrder(_index: number, order: ArchiveOrderListItem): number {
    return order.id;
  }

  trackMetric(_index: number, metric: ArchiveSideMetric): string {
    return metric.label;
  }

  orderDetailsLink(order: ArchiveOrderListItem): Array<string | number> | null {
    return order.source === 'live' && order.companyId
      ? ['/manager/orders', order.companyId, order.id]
      : null;
  }

  canRestore(order: ArchiveOrderListItem): boolean {
    return order.source === 'archive' && !order.restoredAt;
  }

  openRestore(order: ArchiveOrderListItem): void {
    if (!this.canRestore(order)) {
      return;
    }

    this.restoreOrder.set(order);
    this.restoreDetails.set(null);
    this.restoreResult.set(null);
    this.restoreError.set(null);
    this.restoreTargetStatus.set(order.status || 'Архив');
    this.restoreLoading.set(true);

    this.managerApi.getArchiveOrder(order.id).subscribe({
      next: (details) => {
        this.restoreDetails.set(details);
        this.restoreLoading.set(false);
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Не удалось загрузить состав архивного заказа');
        this.restoreError.set(message);
        this.restoreLoading.set(false);
        this.toastService.error('Восстановление недоступно', message);
      }
    });
  }

  closeRestore(): void {
    if (this.restoring()) {
      return;
    }

    this.restoreOrder.set(null);
    this.restoreDetails.set(null);
    this.restoreResult.set(null);
    this.restoreError.set(null);
  }

  confirmRestore(): void {
    const order = this.restoreOrder();
    if (!order || this.restoreLoading() || this.restoring()) {
      return;
    }

    this.restoring.set(true);
    this.restoreError.set(null);

    this.managerApi.restoreArchiveOrder(order.id, this.restoreTargetStatus()).subscribe({
      next: (result) => {
        this.restoreResult.set(result);
        this.restoring.set(false);
        this.toastService.success('Заказ восстановлен', `#${result.orderId} вернулся в live со статусом ${result.targetStatus}`);
        this.loadOrders();
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Не удалось восстановить архивный заказ');
        this.restoreError.set(message);
        this.restoring.set(false);
        this.toastService.error('Восстановление не выполнено', message);
      }
    });
  }

  restoreStatusOptions(order: ArchiveOrderListItem): string[] {
    return [order.status, 'Архив', 'Оплачено', 'Новый', 'Не оплачено', 'В проверку']
      .filter((status): status is string => Boolean(status && status.trim()))
      .filter((status, index, statuses) => statuses.indexOf(status) === index);
  }

  restoreRowsTotal(details: ArchiveOrderDetailsPayload | null): number {
    if (!details) {
      return 0;
    }

    return 1
      + details.details.length
      + details.reviews.length
      + details.badReviewTasks.length
      + details.nextOrderRequests.length
      + details.zp.length
      + details.paymentChecks.length;
  }

  restorePaymentTotal(details: ArchiveOrderDetailsPayload | null): number {
    return details?.paymentChecks.reduce((sum, item) => sum + (item.sum || 0), 0) ?? 0;
  }

  restoreZpTotal(details: ArchiveOrderDetailsPayload | null): number {
    return details?.zp.reduce((sum, item) => sum + (item.sum || 0), 0) ?? 0;
  }

  orderAgeDays(order: ArchiveOrderListItem): number {
    const value = order.changed || order.payDay || order.created;
    if (!value) {
      return 0;
    }
    const changed = new Date(value);
    if (Number.isNaN(changed.getTime())) {
      return 0;
    }
    const today = new Date();
    const msPerDay = 24 * 60 * 60 * 1000;
    return Math.max(0, Math.floor((today.getTime() - changed.getTime()) / msPerDay));
  }

  isArchiveSource(order: ArchiveOrderListItem): boolean {
    return order.source === 'archive';
  }

  private loadOrders(): void {
    this.loading.set(true);
    this.error.set(null);

    this.managerApi.getArchiveOrders({
      keyword: this.keyword(),
      mode: this.mode(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize()
    }).subscribe({
      next: (page) => {
        this.ordersPage.set(page);
        this.loading.set(false);
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Архив заказов не загрузился');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Архив не загрузился', message);
      }
    });
  }
}
