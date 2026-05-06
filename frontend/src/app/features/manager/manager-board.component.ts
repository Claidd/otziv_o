import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth.service';
import { CompanyCreateResult, CompanyCreateSource } from '../../core/company-create.api';
import { MetricSnapshotApi } from '../../core/metric-snapshot.api';
import {
  CompanyCardItem,
  ManagerApi,
  ManagerBoard,
  ManagerMetric,
  ManagerOverdueOrders,
  ManagerOverdueStatus,
  ManagerOption,
  ManagerPage,
  ManagerSection,
  OrderCardItem
} from '../../core/manager.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { CompanyCreateModalComponent } from '../../shared/company-create-modal.component';
import { LoadErrorCardComponent } from '../../shared/load-error-card.component';
import { PersonalRemindersComponent } from '../../shared/personal-reminders.component';
import { phoneDigits } from '../../shared/phone-format';
import { ToastService } from '../../shared/toast.service';
import {
  DEFAULT_MANAGER_COMPANY_STATUSES,
  DEFAULT_MANAGER_ORDER_STATUSES,
  EMPTY_MANAGER_COMPANY_PAGE,
  EMPTY_MANAGER_ORDER_PAGE,
  MANAGER_COMPANY_ACTIONS,
  MANAGER_HISTORY_STATE_KEY,
  MANAGER_MOBILE_NAV_LINKS,
  MANAGER_ORDER_ACTIONS,
  MANAGER_PAGE_SIZE_OPTIONS,
  MANAGER_SECTIONS,
  ManagerHistoryView,
  MobileNavLink,
  PromoItem,
  SelectedCompany,
  StatusAction,
  managerBoardTitle,
  managerErrorMessage,
  managerLayoutTitle,
  managerOrderActions,
  managerOrderReviewCopyText,
  managerPayableOrderSum,
  managerPromoItems,
  managerStatusOptionLabel,
  trackManagerCompany,
  trackManagerMetric,
  trackManagerOrder,
  trackManagerStatus
} from './manager-board.config';
import { ManagerBoardActionFacade } from './manager-board-action.facade';
import { ManagerCompanyCardComponent } from './manager-company-card.component';
import type {
  ManagerCompanyEditDraftChange,
  ManagerCompanyFilialUpdateRequest
} from './manager-company-edit-modal.component';
import { ManagerCompanyEditModalComponent } from './manager-company-edit-modal.component';
import { ManagerBoardCompanyFacade } from './manager-board-company.facade';
import {
  managerReadHistoryView,
  managerReadQueryView,
  managerViewQueryParams,
  managerWithHistoryState
} from './manager-board.history';
import { ManagerBoardOrderFacade } from './manager-board-order.facade';
import { ManagerOrderCardComponent } from './manager-order-card.component';
import type { ManagerOrderEditDraftChange } from './manager-order-edit-modal.component';
import { ManagerOrderEditModalComponent } from './manager-order-edit-modal.component';
import type { ManagerCreateOrderDraftChange } from './manager-order-create-modal.component';
import { ManagerOrderCreateModalComponent } from './manager-order-create-modal.component';

type CompanyCreateContext = {
  source: CompanyCreateSource;
  leadId: number | null;
};

@Component({
  selector: 'app-manager-board',
  imports: [
    AdminLayoutComponent,
    CompanyCreateModalComponent,
    FormsModule,
    LoadErrorCardComponent,
    ManagerCompanyCardComponent,
    ManagerCompanyEditModalComponent,
    ManagerOrderCardComponent,
    ManagerOrderEditModalComponent,
    ManagerOrderCreateModalComponent,
    PersonalRemindersComponent,
    RouterLink
  ],
  templateUrl: './manager-board.component.html',
  styleUrl: './manager-board.component.scss'
})
export class ManagerBoardComponent {
  private readonly historyStateKey = MANAGER_HISTORY_STATE_KEY;
  private readonly managerApi = inject(ManagerApi);
  private readonly metricSnapshotApi = inject(MetricSnapshotApi);
  private readonly toastService = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  private readonly emptyCompanyPage = EMPTY_MANAGER_COMPANY_PAGE;
  private readonly emptyOrderPage = EMPTY_MANAGER_ORDER_PAGE;
  private readonly overdueAlertStorageKeyPrefix = 'otziv-manager-overdue-alert:v2';

  readonly sections = MANAGER_SECTIONS;
  readonly companyActions = MANAGER_COMPANY_ACTIONS;
  readonly allOrderActions = MANAGER_ORDER_ACTIONS;
  readonly pageSizeOptions = MANAGER_PAGE_SIZE_OPTIONS;
  readonly legacyWorkersUrl = '/worker';
  readonly mobileNavLinks: MobileNavLink[] = MANAGER_MOBILE_NAV_LINKS;

  readonly board = signal<ManagerBoard | null>(null);
  readonly activeSection = signal<ManagerSection>('companies');
  readonly companyStatus = signal('Все');
  readonly orderStatus = signal('Все');
  readonly keyword = signal('');
  readonly pageNumber = signal(0);
  readonly pageSize = signal(10);
  readonly sortDirection = signal<'desc' | 'asc'>('desc');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly mobileMenuOpen = signal(false);
  readonly selectedCompany = signal<SelectedCompany | null>(null);
  readonly companyCreateContext = signal<CompanyCreateContext | null>(null);
  readonly overdueOrders = signal<ManagerOverdueOrders | null>(null);
  readonly overdueModalOpen = signal(false);

  private readonly companyFacade = new ManagerBoardCompanyFacade({
    managerApi: this.managerApi,
    toastService: this.toastService,
    loadBoard: () => this.loadBoard(),
    patchBoard: (updater) => this.patchBoard(updater),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback)
  });
  private readonly orderFacade = new ManagerBoardOrderFacade({
    managerApi: this.managerApi,
    toastService: this.toastService,
    loadBoard: () => this.loadBoard(),
    patchBoard: (updater) => this.patchBoard(updater),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback),
    openCreatedCompanyOrders: (result) => this.openCreatedCompanyOrders(result.companyId, result.companyTitle)
  });
  private readonly actionFacade = new ManagerBoardActionFacade({
    managerApi: this.managerApi,
    toastService: this.toastService,
    mutationKey: this.mutationKey,
    loadBoard: () => this.loadBoard(),
    patchBoard: (updater) => this.patchBoard(updater),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback)
  });
  readonly editCompany = this.companyFacade.editCompany;
  readonly editDraft = this.companyFacade.editDraft;
  readonly editLoading = this.companyFacade.editLoading;
  readonly editSaving = this.companyFacade.editSaving;
  readonly editError = this.companyFacade.editError;
  readonly editDeleteKey = this.companyFacade.editDeleteKey;
  readonly editOrder = this.orderFacade.editOrder;
  readonly orderDraft = this.orderFacade.orderDraft;
  readonly orderLoading = this.orderFacade.orderLoading;
  readonly orderSaving = this.orderFacade.orderSaving;
  readonly orderError = this.orderFacade.orderError;
  readonly orderDeleting = this.orderFacade.orderDeleting;
  readonly createOrderPayload = this.orderFacade.createOrderPayload;
  readonly createOrderDraft = this.orderFacade.createOrderDraft;
  readonly createOrderLoading = this.orderFacade.createOrderLoading;
  readonly createOrderSaving = this.orderFacade.createOrderSaving;
  readonly createOrderError = this.orderFacade.createOrderError;
  readonly selectedCreateOrderProduct = this.orderFacade.selectedCreateOrderProduct;
  readonly createOrderTotal = this.orderFacade.createOrderTotal;

  readonly currentCompanies = computed(() => this.board()?.companies.content ?? []);
  readonly currentOrders = computed(() => this.board()?.orders.content ?? []);
  readonly currentPage = computed<ManagerPage<CompanyCardItem | OrderCardItem>>(() => {
    if (this.activeSection() === 'companies') {
      return this.board()?.companies ?? this.emptyCompanyPage;
    }

    return this.board()?.orders ?? this.emptyOrderPage;
  });
  readonly activeStatus = computed(() => {
    return this.activeSection() === 'companies' ? this.companyStatus() : this.orderStatus();
  });
  readonly companyStatusOptions = computed(() => {
    return this.board()?.companyStatuses ?? DEFAULT_MANAGER_COMPANY_STATUSES;
  });
  readonly orderStatusOptions = computed(() => {
    return this.board()?.orderStatuses ?? DEFAULT_MANAGER_ORDER_STATUSES;
  });
  readonly statusOptions = computed(() => {
    return this.activeSection() === 'companies' ? this.companyStatusOptions() : this.orderStatusOptions();
  });
  readonly sortTitle = computed(() => this.sortDirection() === 'desc'
    ? 'Сначала давно без изменений'
    : 'Сначала недавно измененные'
  );
  readonly title = computed(() => {
    return managerBoardTitle(this.activeSection(), this.activeStatus(), this.selectedCompany());
  });
  readonly layoutTitle = computed(() => {
    return managerLayoutTitle(this.activeSection(), this.activeStatus(), this.selectedCompany());
  });
  readonly promoItems = computed<PromoItem[]>(() => {
    return managerPromoItems(this.activeSection(), this.board()?.promoTexts ?? []);
  });
  readonly metrics = computed(() => {
    return (this.board()?.metrics ?? []).filter((metric) => metric.section === this.activeSection());
  });

  constructor() {
    const queryView = managerReadQueryView(this.route.snapshot.queryParamMap);
    const restoredView = managerReadHistoryView(window.history.state, this.historyStateKey);
    if (queryView) {
      this.applyHistoryView(queryView);
      this.replaceCurrentHistoryState();
    } else if (restoredView) {
      this.applyHistoryView(restoredView);
    } else {
      this.replaceCurrentHistoryState();
    }

    this.loadBoard();
    this.loadDailyOverdueReminder();
  }

  @HostListener('window:popstate', ['$event'])
  restoreHistoryState(event: PopStateEvent): void {
    const view = managerReadHistoryView(event.state, this.historyStateKey);

    if (!view) {
      return;
    }

    this.applyHistoryView(view);
    this.loadBoard();
  }

  loadBoard(): void {
    this.loading.set(true);
    this.error.set(null);

    this.managerApi.getBoard({
      section: this.activeSection(),
      status: this.activeStatus(),
      keyword: this.keyword(),
      companyId: this.activeSection() === 'orders' ? this.selectedCompany()?.id : undefined,
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection()
    }).subscribe({
      next: (board) => {
        this.board.set(board);
        this.loading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить раздел менеджера');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Менеджер не загрузился', message);
      }
    });
  }

  setSection(section: ManagerSection): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set(section);
    this.selectedCompany.set(null);
    this.pageNumber.set(0);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(this.findMetric(section, section === 'companies' ? this.companyStatus() : this.orderStatus()));
  }

  setStatus(status: string): void {
    this.replaceCurrentHistoryState();
    const section = this.activeSection();
    if (this.activeSection() === 'companies') {
      this.companyStatus.set(status);
    } else {
      this.orderStatus.set(status);
    }

    this.selectedCompany.set(null);
    this.pageNumber.set(0);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(this.findMetric(section, status));
  }

  handleTopMenu(value: string): void {
    if (!value) {
      return;
    }

    if (value === 'leads') {
      void this.router.navigate(['/leads']);
      return;
    }

    if (value.startsWith('href:')) {
      window.location.href = value.slice(5);
      return;
    }

    if (value.startsWith('route:')) {
      this.mobileMenuOpen.set(false);
      void this.router.navigateByUrl(value.slice(6));
      return;
    }

    const [section, status = 'Все'] = value.split(':');

    if (section === 'companies') {
      this.replaceCurrentHistoryState();
      this.activeSection.set('companies');
      this.companyStatus.set(status);
      this.selectedCompany.set(null);
      this.pageNumber.set(0);
      this.mobileMenuOpen.set(false);
      this.pushCurrentHistoryState();
      this.loadBoardAfterMetricSeen(this.findMetric('companies', status));
      return;
    }

    if (section === 'orders') {
      this.replaceCurrentHistoryState();
      this.activeSection.set('orders');
      this.orderStatus.set(status);
      this.selectedCompany.set(null);
      this.pageNumber.set(0);
      this.mobileMenuOpen.set(false);
      this.pushCurrentHistoryState();
      this.loadBoardAfterMetricSeen(this.findMetric('orders', status));
    }
  }

  openMetric(metric: ManagerMetric): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set(metric.section);
    this.selectedCompany.set(null);

    if (metric.section === 'companies') {
      this.companyStatus.set(metric.status);
    } else {
      this.orderStatus.set(metric.status);
    }

    this.pageNumber.set(0);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(metric);
  }

  statusOptionLabel(section: ManagerSection, status: string): string {
    const count = this.metricValue(section, status);
    return managerStatusOptionLabel(status, count);
  }

  search(): void {
    this.pageNumber.set(0);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  changePageSize(value: string | number): void {
    this.pageSize.set(Number(value));
    this.pageNumber.set(0);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  toggleSortDirection(): void {
    this.sortDirection.update((direction) => direction === 'desc' ? 'asc' : 'desc');
    this.pageNumber.set(0);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  previousPage(): void {
    if (this.currentPage().first) {
      return;
    }

    this.pageNumber.update((page) => Math.max(page - 1, 0));
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  nextPage(): void {
    if (this.currentPage().last) {
      return;
    }

    this.pageNumber.update((page) => page + 1);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }

  async copyPromo(item: PromoItem): Promise<void> {
    await this.copyText(item.text, item.label, `${item.label} скопирован`);
  }

  async copyPhone(phone?: string): Promise<void> {
    await this.copyText(phoneDigits(phone), 'телефон', 'Телефон скопирован');
  }

  async copyOrderText(order: OrderCardItem, kind: 'review' | 'payment'): Promise<void> {
    if (kind === 'review') {
      await this.copyText(
        managerOrderReviewCopyText(order, this.board()?.promoTexts ?? []),
        `review-${order.id}`,
        'Текст проверки скопирован'
      );
      return;
    }

    const sum = this.payableOrderSum(order);
    await this.copyText(
      `${order.managerPayText ?? ''} К оплате: ${sum} руб. ${order.companyTitle} ${order.filialTitle ?? ''}`.trim(),
      `payment-${order.id}`,
      'Текст счета скопирован'
    );
  }

  payableOrderSum(order: OrderCardItem): number {
    return managerPayableOrderSum(order);
  }

  openCompanyOrders(company: CompanyCardItem): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set('orders');
    this.orderStatus.set('Все');
    this.selectedCompany.set({
      id: company.id,
      title: company.title || `Компания #${company.id}`
    });
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoard();
  }

  openCompanyEdit(company: CompanyCardItem): void {
    this.companyFacade.openCompanyEdit(company);
  }

  openManualCompanyCreate(): void {
    this.companyCreateContext.set({ source: 'manual', leadId: null });
  }

  closeCompanyCreate(): void {
    this.companyCreateContext.set(null);
  }

  handleCompanyCreated(result: CompanyCreateResult): void {
    this.closeCompanyCreate();
    this.toastService.success('Компания создана', `${result.title} добавлена в работу`);
    this.replaceCurrentHistoryState();
    this.activeSection.set('companies');
    this.companyStatus.set('Новая');
    this.selectedCompany.set(null);
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoard();
  }

  closeCompanyEdit(): void {
    this.companyFacade.closeCompanyEdit();
  }

  handleCompanyEditDraftChange(change: ManagerCompanyEditDraftChange): void {
    this.companyFacade.handleCompanyEditDraftChange(change);
  }

  changeCompanyCategory(categoryId: number | null): void {
    this.companyFacade.changeCompanyCategory(categoryId);
  }

  saveCompanyEdit(): void {
    this.companyFacade.saveCompanyEdit();
  }

  deleteCompanyWorker(worker: ManagerOption): void {
    this.companyFacade.deleteCompanyWorker(worker);
  }

  deleteCompanyFilial(filialId: number, title: string): void {
    this.companyFacade.deleteCompanyFilial(filialId, title);
  }

  updateCompanyFilial(request: ManagerCompanyFilialUpdateRequest): void {
    this.companyFacade.updateCompanyFilial(request);
  }

  openCompanyOrderCreate(company: CompanyCardItem): void {
    this.orderFacade.openCompanyOrderCreate(company);
  }

  closeCompanyOrderCreate(): void {
    this.orderFacade.closeCompanyOrderCreate();
  }

  handleCreateOrderDraftChange(change: ManagerCreateOrderDraftChange): void {
    this.orderFacade.handleCreateOrderDraftChange(change);
  }

  createCompanyOrder(): void {
    this.orderFacade.createCompanyOrder();
  }

  openOrderEdit(order: OrderCardItem): void {
    this.orderFacade.openOrderEdit(order);
  }

  closeOrderEdit(): void {
    this.orderFacade.closeOrderEdit();
  }

  handleOrderEditDraftChange(change: ManagerOrderEditDraftChange): void {
    this.orderFacade.handleOrderEditDraftChange(change);
  }

  saveOrderEdit(): void {
    this.orderFacade.saveOrderEdit();
  }

  deleteOrderEdit(): void {
    this.orderFacade.deleteOrderEdit();
  }

  updateCompanyStatus(company: CompanyCardItem, action: StatusAction): void {
    this.actionFacade.updateCompanyStatus(company, action);
  }

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    this.actionFacade.updateOrderStatus(order, action);
  }

  toggleOrderClientWaiting(order: OrderCardItem): void {
    this.actionFacade.toggleOrderClientWaiting(order);
  }

  closeOverdueModal(): void {
    this.overdueModalOpen.set(false);
  }

  openOverdueStatus(status: string): void {
    this.closeOverdueModal();
    this.replaceCurrentHistoryState();
    this.activeSection.set('orders');
    this.orderStatus.set(status || 'Все');
    this.selectedCompany.set(null);
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(this.findMetric('orders', this.orderStatus()));
  }

  saveCompanyCardNote(company: CompanyCardItem, value: string): void {
    this.actionFacade.saveCompanyCardNote(company, value);
  }

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    this.actionFacade.saveOrderCompanyNote(order, value);
  }

  saveOrderCardNote(order: OrderCardItem, value: string): void {
    this.actionFacade.saveOrderCardNote(order, value);
  }

  orderActions(order: OrderCardItem): StatusAction[] {
    return managerOrderActions(order, Boolean(this.selectedCompany()));
  }

  trackStatus(_index: number, status: string): string {
    return trackManagerStatus(_index, status);
  }

  trackCompany(_index: number, company: CompanyCardItem): number {
    return trackManagerCompany(_index, company);
  }

  trackOrder(_index: number, order: OrderCardItem): number {
    return trackManagerOrder(_index, order);
  }

  trackMetric(_index: number, metric: ManagerMetric): string {
    return trackManagerMetric(_index, metric);
  }

  trackOverdueStatus(_index: number, status: ManagerOverdueStatus): string {
    return status.status;
  }

  overdueMaxDays(summary: ManagerOverdueOrders): number {
    return summary.statuses.reduce((max, status) => Math.max(max, status.maxDays), 0);
  }

  private metricValue(section: ManagerSection, status: string): number | null {
    const metric = this.board()?.metrics.find((item) => item.section === section && item.status === status);
    return metric?.value ?? null;
  }

  private findMetric(section: ManagerSection, status: string): ManagerMetric | undefined {
    return this.board()?.metrics.find((item) => item.section === section && item.status === status);
  }

  private loadBoardAfterMetricSeen(metric?: ManagerMetric): void {
    if (!metric) {
      this.loadBoard();
      return;
    }

    this.metricSnapshotApi.markSeen({
      page: 'manager',
      section: metric.section,
      status: metric.status,
      value: metric.value
    }).subscribe({
      next: () => {
        this.clearMetricDelta(metric);
        this.loadBoard();
      },
      error: () => this.loadBoard()
    });
  }

  private clearMetricDelta(metric: ManagerMetric): void {
    this.patchBoard((board) => ({
      ...board,
      metrics: board.metrics.map((item) => item.section === metric.section && item.status === metric.status
        ? { ...item, delta: 0 }
        : item
      )
    }));
  }

  private captureHistoryView(): ManagerHistoryView {
    return {
      activeSection: this.activeSection(),
      companyStatus: this.companyStatus(),
      orderStatus: this.orderStatus(),
      keyword: this.keyword(),
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection(),
      selectedCompany: this.selectedCompany()
    };
  }

  private applyHistoryView(view: ManagerHistoryView): void {
    this.activeSection.set(view.activeSection);
    this.companyStatus.set(view.companyStatus);
    this.orderStatus.set(view.orderStatus);
    this.keyword.set(view.keyword);
    this.pageNumber.set(view.pageNumber);
    this.pageSize.set(view.pageSize);
    this.sortDirection.set(view.sortDirection);
    this.selectedCompany.set(view.selectedCompany);
    this.mobileMenuOpen.set(false);
  }

  private replaceCurrentHistoryState(): void {
    const view = this.captureHistoryView();
    const state = managerWithHistoryState(window.history.state, view, this.historyStateKey);
    window.history.replaceState(state, document.title, this.managerUrlForView(view));
  }

  private pushCurrentHistoryState(): void {
    const view = this.captureHistoryView();
    const state = managerWithHistoryState(window.history.state, view, this.historyStateKey);
    window.history.pushState(state, document.title, this.managerUrlForView(view));
  }

  private managerUrlForView(view: ManagerHistoryView): string {
    return this.router.serializeUrl(this.router.createUrlTree(['/manager'], {
      queryParams: managerViewQueryParams(view)
    }));
  }

  private async copyText(text: string, copiedKey: string, toast: string): Promise<void> {
    const value = text.trim();

    if (!value) {
      return;
    }

    try {
      await navigator.clipboard.writeText(value);
      this.copied.set(copiedKey);
      this.toastService.success('Скопировано', toast);
      window.setTimeout(() => {
        if (this.copied() === copiedKey) {
          this.copied.set(null);
        }
      }, 1200);
    } catch {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    }
  }

  private openCreatedCompanyOrders(companyId: number, companyTitle: string): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set('orders');
    this.orderStatus.set('Все');
    this.selectedCompany.set({
      id: companyId,
      title: companyTitle || `Компания #${companyId}`
    });
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoard();
  }

  private patchBoard(updater: (board: ManagerBoard) => ManagerBoard): void {
    this.board.update((board) => board ? updater(board) : board);
  }

  private errorMessage(err: unknown, fallback: string): string {
    return managerErrorMessage(err, fallback);
  }

  private loadDailyOverdueReminder(): void {
    const today = this.localDateKey();
    const storageKey = this.overdueAlertStorageKey();

    if (this.readStoredDate(storageKey) === today) {
      return;
    }

    this.managerApi.getOverdueOrders().subscribe({
      next: (summary) => {
        this.writeStoredDate(storageKey, today);
        const normalizedSummary = {
          ...summary,
          statuses: summary.statuses ?? []
        };

        if (normalizedSummary.total > 0) {
          this.overdueOrders.set(normalizedSummary);
          this.overdueModalOpen.set(true);
        }
      },
      error: () => {
        // The reminder is helpful, but the board itself should not fail because of it.
      }
    });
  }

  private overdueAlertStorageKey(): string {
    const token = this.auth.tokenParsed() as { preferred_username?: string; sub?: string } | undefined;
    const userKey = token?.preferred_username || token?.sub || 'user';
    return `${this.overdueAlertStorageKeyPrefix}:${userKey}`;
  }

  private localDateKey(date = new Date()): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private readStoredDate(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  }

  private writeStoredDate(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // Storage can be blocked in private mode; the reminder will simply try again later.
    }
  }
}
