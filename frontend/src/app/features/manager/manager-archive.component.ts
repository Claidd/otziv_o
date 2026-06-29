import { DecimalPipe } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  ArchiveOrderDetailsPayload,
  ArchiveOrderDetailItem,
  ArchiveOrderListItem,
  ArchiveOrderMode,
  ArchivePaymentCheckItem,
  ArchiveReviewItem,
  ArchiveRestoreResult,
  ArchiveZpItem,
  ManagerApi,
  ManagerPage,
  OrderCardItem
} from '../../core/manager.api';
import { AuthService } from '../../core/auth.service';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { apiErrorMessage } from '../../shared/api-error-message';
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { orderReviewCopyText, reviewCheckPath } from '../../shared/order-review-copy-text';
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

type LiveArchiveStatusAction = {
  label: string;
  status: string;
};

const LIVE_ARCHIVE_STATUS_ACTIONS: LiveArchiveStatusAction[] = [
  { label: 'новый', status: 'Новый' },
  { label: 'коррекция', status: 'Коррекция' },
  { label: 'на проверке', status: 'На проверке' }
];

const RESTORE_STATUS_OPTIONS = ['Новый', 'Коррекция', 'На проверке'] as const;

const EMPTY_ARCHIVE_PAGE: ManagerPage<ArchiveOrderListItem> = {
  content: [],
  number: 0,
  size: 10,
  totalElements: 0,
  totalPages: 0,
  first: true,
  last: true
};

type ArchiveRouteState = {
  mode: ArchiveOrderMode;
  keyword: string;
  pageNumber: number;
  pageSize: number;
  sortDirection: 'desc' | 'asc';
  archiveOrderId: number | null;
};

@Component({
  selector: 'app-manager-archive',
  imports: [AdminLayoutComponent, DecimalPipe, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './manager-archive.component.html',
  styleUrl: './manager-archive.component.scss'
})
export class ManagerArchiveComponent implements OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly managerApi = inject(ManagerApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly toastService = inject(ToastService);
  private searchDebounceId: ReturnType<typeof setTimeout> | null = null;
  private loadingArchiveOrderId: number | null = null;
  private lastListQueryKey = '';
  private readonly routeSubscription: Subscription;

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
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly restoreOrder = signal<ArchiveOrderListItem | null>(null);
  readonly restoreDetails = signal<ArchiveOrderDetailsPayload | null>(null);
  readonly restoreResult = signal<ArchiveRestoreResult | null>(null);
  readonly restoreError = signal<string | null>(null);
  readonly restoreLoading = signal(false);
  readonly restoring = signal(false);
  readonly restoreTargetStatus = signal('Архив');
  readonly activeArchiveOrderId = signal<number | null>(null);
  readonly liveStatusMutationKey = signal<string | null>(null);
  readonly recoveryTaskMutationKey = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly liveStatusActions = LIVE_ARCHIVE_STATUS_ACTIONS;
  readonly restoreStatuses = RESTORE_STATUS_OPTIONS;

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
  readonly sortTitle = computed(() => this.sortDirection() === 'desc'
    ? 'Сначала новые архивные'
    : 'Сначала старые архивные'
  );

  constructor() {
    this.routeSubscription = this.route.queryParamMap.subscribe((params) => this.applyRouteState(params));
  }

  ngOnDestroy(): void {
    this.clearSearchDebounce();
    this.routeSubscription.unsubscribe();
  }

  setMode(mode: ArchiveOrderMode): void {
    if (this.mode() === mode) {
      return;
    }

    this.navigateArchiveState({ mode, pageNumber: 0, archiveOrderId: null });
  }

  search(): void {
    this.clearSearchDebounce();
    this.navigateArchiveState({ keyword: this.keyword(), pageNumber: 0, archiveOrderId: null });
  }

  updateKeyword(value: string): void {
    this.keyword.set(value);
    this.clearSearchDebounce();
    this.searchDebounceId = setTimeout(() => {
      this.navigateArchiveState({ keyword: this.keyword(), pageNumber: 0, archiveOrderId: null }, true);
      this.searchDebounceId = null;
    }, 450);
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  changePageSize(value: string | number): void {
    this.navigateArchiveState({ pageSize: Number(value), pageNumber: 0, archiveOrderId: null });
  }

  previousPage(): void {
    if (this.ordersPage().first) {
      return;
    }

    this.navigateArchiveState({ pageNumber: Math.max(0, this.pageNumber() - 1), archiveOrderId: null });
  }

  nextPage(): void {
    if (this.ordersPage().last) {
      return;
    }

    this.navigateArchiveState({ pageNumber: this.pageNumber() + 1, archiveOrderId: null });
  }

  refresh(): void {
    this.loadOrders();
  }

  toggleSortDirection(): void {
    this.navigateArchiveState({
      sortDirection: this.sortDirection() === 'desc' ? 'asc' : 'desc',
      pageNumber: 0,
      archiveOrderId: null
    });
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

  trackLiveStatusAction(_index: number, action: LiveArchiveStatusAction): string {
    return action.status;
  }

  trackDetail(_index: number, detail: ArchiveOrderDetailItem): string {
    return detail.id;
  }

  trackReview(_index: number, review: ArchiveReviewItem): number {
    return review.id;
  }

  trackPayment(_index: number, payment: ArchivePaymentCheckItem): number {
    return payment.id;
  }

  trackZp(_index: number, zp: ArchiveZpItem): number {
    return zp.id;
  }

  orderDetailsLink(order: ArchiveOrderListItem): Array<string | number> | null {
    return order.source === 'live' && order.companyId
      ? ['/orders', order.companyId, order.id]
      : null;
  }

  canRestore(order: ArchiveOrderListItem): boolean {
    if (order.source !== 'archive' || order.restoredAt) {
      return false;
    }

    return order.status !== 'Оплачено' || this.canManagePaidRestore();
  }

  canOpenArchiveDetails(order: ArchiveOrderListItem): boolean {
    return order.source === 'archive';
  }

  paidRestoreRestricted(order: ArchiveOrderListItem): boolean {
    return order.source === 'archive' && order.status === 'Оплачено' && !this.canManagePaidRestore();
  }

  canSeeArchiveFinance(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  canChangeLiveStatus(order: ArchiveOrderListItem): boolean {
    return order.source === 'live' && order.status === 'Архив';
  }

  isLiveStatusMutating(order: ArchiveOrderListItem, action: LiveArchiveStatusAction): boolean {
    return this.liveStatusMutationKey() === this.liveStatusKey(order, action.status);
  }

  archiveOrderChatUrl(order: ArchiveOrderListItem): string {
    return order.companyUrlChat || `tel:${order.companyTelephone ?? ''}`;
  }

  archiveOrderTitle(order: ArchiveOrderListItem): string {
    return `${order.companyTitle || 'Без компании'} - ${order.filialTitle || 'Без филиала'}`;
  }

  archiveOrderFilialUrl(order: ArchiveOrderListItem): string {
    return (order.filialUrl ?? '').trim();
  }

  archiveOrderReviewUrl(order: ArchiveOrderListItem, details?: ArchiveOrderDetailsPayload | null): string {
    const orderDetailsId = this.archiveOrderDetailsId(order, details);
    return orderDetailsId ? reviewCheckPath(orderDetailsId) : '';
  }

  hasArchiveReviewLink(order: ArchiveOrderListItem, details?: ArchiveOrderDetailsPayload | null): boolean {
    return Boolean(this.archiveOrderDetailsId(order, details));
  }

  async copyArchiveReviewText(order: ArchiveOrderListItem, details?: ArchiveOrderDetailsPayload | null): Promise<void> {
    const orderDetailsId = this.archiveOrderDetailsId(order, details);
    if (!orderDetailsId) {
      this.toastService.error('Не скопировано', 'У заказа нет детали для ссылки на проверку отзывов');
      return;
    }

    await this.copyText(
      orderReviewCopyText(this.archiveOrderForReviewText(order, orderDetailsId), []),
      `archive-review-${order.id}`,
      'Текст проверки скопирован'
    );
  }

  changeLiveStatus(order: ArchiveOrderListItem, action: LiveArchiveStatusAction): void {
    if (!this.canChangeLiveStatus(order) || this.liveStatusMutationKey()) {
      return;
    }

    this.liveStatusMutationKey.set(this.liveStatusKey(order, action.status));
    this.managerApi.updateOrderStatus(order.id, action.status).subscribe({
      next: () => {
        this.liveStatusMutationKey.set(null);
        this.toastService.success('Заказ вернулся в работу', `#${order.id}: ${action.status}`);
        this.loadOrders();
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Не удалось изменить статус заказа');
        this.liveStatusMutationKey.set(null);
        this.toastService.error('Статус не изменен', message);
      }
    });
  }

  openRestore(order: ArchiveOrderListItem): void {
    if (!this.canOpenArchiveDetails(order)) {
      return;
    }

    this.openArchiveDetails(order.id, order);
    this.navigateArchiveState({ archiveOrderId: order.id });
  }

  closeRestore(): void {
    if (this.restoring()) {
      return;
    }

    if (this.activeArchiveOrderId()) {
      this.navigateArchiveState({ archiveOrderId: null }, true);
    }
    this.clearRestoreState();
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

  createArchiveRecoveryTask(order: ArchiveOrderListItem, review: ArchiveReviewItem): void {
    if (!order?.id || !review?.id || this.recoveryTaskMutationKey()) {
      return;
    }

    const key = this.archiveRecoveryKey(order, review);
    this.recoveryTaskMutationKey.set(key);
    this.restoreError.set(null);

    this.managerApi.createArchiveReviewRecoveryTask(order.id, review.id).subscribe({
      next: (details) => {
        this.restoreOrder.set(details.order);
        this.restoreDetails.set(details);
        this.recoveryTaskMutationKey.set(null);
        this.toastService.success('Задача создана', `Отзыв #${review.id} добавлен в восстановление`);
      },
      error: (err) => {
        const message = apiErrorMessage(err, 'Не удалось создать задачу восстановления');
        this.restoreError.set(message);
        this.recoveryTaskMutationKey.set(null);
        this.toastService.error('Задача не создана', message);
      }
    });
  }

  isArchiveRecoveryTaskCreating(order: ArchiveOrderListItem, review: ArchiveReviewItem): boolean {
    return this.recoveryTaskMutationKey() === this.archiveRecoveryKey(order, review);
  }

  canCreateArchiveRecoveryTask(review: ArchiveReviewItem): boolean {
    return !!review.id && !!(review.text || '').trim();
  }

  restoreStatusOptions(_order: ArchiveOrderListItem): string[] {
    return [...this.restoreStatuses];
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
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection()
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

  private liveStatusKey(order: ArchiveOrderListItem, status: string): string {
    return `archive-live-${order.id}-${status}`;
  }

  private archiveRecoveryKey(order: ArchiveOrderListItem, review: ArchiveReviewItem): string {
    return `archive-recovery-${order.id}-${review.id}`;
  }

  private archiveOrderDetailsId(order: ArchiveOrderListItem, details?: ArchiveOrderDetailsPayload | null): string {
    return order.orderDetailsId
      || details?.details[0]?.id
      || details?.reviews.find((review) => review.orderDetailsId)?.orderDetailsId
      || '';
  }

  private archiveOrderForReviewText(order: ArchiveOrderListItem, orderDetailsId: string): OrderCardItem {
    return {
      id: order.id,
      companyId: order.companyId ?? 0,
      orderDetailsId,
      companyTitle: order.companyTitle,
      filialTitle: order.filialTitle,
      status: order.status,
      sum: order.sum,
      amount: order.amount,
      counter: order.counter,
      companyUrlChat: order.companyUrlChat,
      companyTelephone: order.companyTelephone,
      firstOrderForCompany: true
    };
  }

  private async copyText(text: string, copiedKey: string, toast: string): Promise<void> {
    const value = text.trim();
    if (!value) {
      return;
    }

    if (await copyTextToClipboard(value)) {
      this.copied.set(copiedKey);
      this.toastService.success('Скопировано', toast);
      window.setTimeout(() => {
        if (this.copied() === copiedKey) {
          this.copied.set(null);
        }
      }, 1200);
      return;
    }

    this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
  }

  private applyRouteState(params: ParamMap): void {
    const nextState = this.readRouteState(params);
    const listKey = this.listQueryKey(nextState);

    this.mode.set(nextState.mode);
    this.keyword.set(nextState.keyword);
    this.pageNumber.set(nextState.pageNumber);
    this.pageSize.set(nextState.pageSize);
    this.sortDirection.set(nextState.sortDirection);
    this.activeArchiveOrderId.set(nextState.archiveOrderId);

    if (listKey !== this.lastListQueryKey) {
      this.lastListQueryKey = listKey;
      this.loadOrders();
    }

    if (nextState.archiveOrderId) {
      this.openArchiveDetails(nextState.archiveOrderId, this.orders().find((order) => order.id === nextState.archiveOrderId));
    } else if (!this.restoring()) {
      this.clearRestoreState();
    }
  }

  private openArchiveDetails(orderId: number, fallbackOrder?: ArchiveOrderListItem): void {
    if (
      this.restoreOrder()?.id === orderId
      && (this.restoreLoading() || this.restoreDetails() || this.restoreError())
    ) {
      return;
    }

    this.activeArchiveOrderId.set(orderId);
    this.loadingArchiveOrderId = orderId;
    this.restoreOrder.set(fallbackOrder ?? null);
    this.restoreDetails.set(null);
    this.restoreResult.set(null);
    this.restoreError.set(null);
    this.restoreTargetStatus.set(this.restoreStatuses[0]);
    this.restoreLoading.set(true);

    this.managerApi.getArchiveOrder(orderId).subscribe({
      next: (details) => {
        if (this.loadingArchiveOrderId !== orderId) {
          return;
        }
        this.restoreOrder.set(details.order);
        this.restoreDetails.set(details);
        this.restoreLoading.set(false);
      },
      error: (err) => {
        if (this.loadingArchiveOrderId !== orderId) {
          return;
        }
        const message = apiErrorMessage(err, 'Не удалось загрузить состав архивного заказа');
        this.restoreError.set(message);
        this.restoreLoading.set(false);
        this.toastService.error('Восстановление недоступно', message);
      }
    });
  }

  private clearRestoreState(): void {
    this.loadingArchiveOrderId = null;
    this.activeArchiveOrderId.set(null);
    this.restoreOrder.set(null);
    this.restoreDetails.set(null);
    this.restoreResult.set(null);
    this.restoreError.set(null);
    this.restoreLoading.set(false);
  }

  private navigateArchiveState(patch: Partial<ArchiveRouteState>, replaceUrl = false): void {
    const state: ArchiveRouteState = {
      mode: this.mode(),
      keyword: this.keyword(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection(),
      archiveOrderId: this.activeArchiveOrderId(),
      ...patch
    };

    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: this.routeQueryParams(state),
      replaceUrl
    });
  }

  private readRouteState(params: ParamMap): ArchiveRouteState {
    return {
      mode: this.normalizeMode(params.get('mode')),
      keyword: params.get('keyword')?.trim() ?? '',
      pageNumber: this.readNonNegativeInt(params.get('pageNumber'), 0),
      pageSize: this.readPageSize(params.get('pageSize')),
      sortDirection: this.readSortDirection(params.get('sortDirection')),
      archiveOrderId: this.readPositiveInt(params.get('archiveOrderId'))
    };
  }

  private routeQueryParams(state: ArchiveRouteState): Record<string, string | number> {
    const queryParams: Record<string, string | number> = {};
    if (state.mode !== 'all') {
      queryParams['mode'] = state.mode;
    }
    if (state.keyword.trim()) {
      queryParams['keyword'] = state.keyword.trim();
    }
    if (state.pageNumber > 0) {
      queryParams['pageNumber'] = state.pageNumber;
    }
    if (state.pageSize !== 10) {
      queryParams['pageSize'] = state.pageSize;
    }
    if (state.sortDirection !== 'desc') {
      queryParams['sortDirection'] = state.sortDirection;
    }
    if (state.archiveOrderId) {
      queryParams['archiveOrderId'] = state.archiveOrderId;
    }
    return queryParams;
  }

  private listQueryKey(state: ArchiveRouteState): string {
    return [state.mode, state.keyword, state.pageNumber, state.pageSize, state.sortDirection].join('|');
  }

  private normalizeMode(mode: string | null): ArchiveOrderMode {
    return mode === 'archive' || mode === 'paid' || mode === 'all' ? mode : 'all';
  }

  private readPageSize(value: string | null): number {
    const parsed = this.readNonNegativeInt(value, 10);
    return this.pageSizeOptions.includes(parsed) ? parsed : 10;
  }

  private readSortDirection(value: string | null): 'desc' | 'asc' {
    return value === 'asc' ? 'asc' : 'desc';
  }

  private readNonNegativeInt(value: string | null, fallback: number): number {
    if (!value) {
      return fallback;
    }
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback;
  }

  private readPositiveInt(value: string | null): number | null {
    const parsed = this.readNonNegativeInt(value, 0);
    return parsed > 0 ? parsed : null;
  }

  private canManagePaidRestore(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
  }

  private clearSearchDebounce(): void {
    if (!this.searchDebounceId) {
      return;
    }

    clearTimeout(this.searchDebounceId);
    this.searchDebounceId = null;
  }
}
