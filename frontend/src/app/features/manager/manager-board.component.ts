import { Component, HostListener, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { CompanyDeepReportLaunchService } from '../../core/company-deep-report-launch.service';
import { CompanyCreateResult, CompanyCreateSource } from '../../core/company-create.api';
import { CommonBillingApi, type CommonInvoiceDetailsResponse } from '../../core/common-billing.api';
import { MetricSnapshotApi } from '../../core/metric-snapshot.api';
import { PaymentsApi } from '../../core/payments.api';
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
import { copyTextToClipboard } from '../../shared/clipboard-copy';
import { CompanyCreateModalComponent } from '../../shared/company-create-modal.component';
import { GamificationMeCardComponent } from '../../shared/gamification-me-card.component';
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
  ManagerChatBotInviteKind,
  ManagerHistoryView,
  MobileNavLink,
  PromoItem,
  SelectedCompany,
  StatusAction,
  managerBoardTitle,
  managerChatBotInviteKind,
  managerChatBotInviteUrl,
  managerErrorMessage,
  managerLayoutTitle,
  managerOrderActions,
  managerOrderReviewCopyText,
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
  ManagerCompanyBillingDraftChange,
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

type ChatBotLinkPlatform = Exclude<ManagerChatBotInviteKind, null>;

type ChatBotLinkPoll = {
  startedAt: number;
  platform: ChatBotLinkPlatform;
};

@Component({
  selector: 'app-manager-board',
  imports: [
    AdminLayoutComponent,
    CompanyCreateModalComponent,
    FormsModule,
    GamificationMeCardComponent,
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
export class ManagerBoardComponent implements OnDestroy {
  private readonly historyStateKey = MANAGER_HISTORY_STATE_KEY;
  private readonly managerApi = inject(ManagerApi);
  private readonly paymentsApi = inject(PaymentsApi);
  private readonly commonBillingApi = inject(CommonBillingApi);
  private readonly metricSnapshotApi = inject(MetricSnapshotApi);
  private readonly toastService = inject(ToastService);
  private readonly companyDeepReportLaunch = inject(CompanyDeepReportLaunchService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly auth = inject(AuthService);
  private readonly emptyCompanyPage = EMPTY_MANAGER_COMPANY_PAGE;
  private readonly emptyOrderPage = EMPTY_MANAGER_ORDER_PAGE;
  private readonly overdueAlertStorageKeyPrefix = 'otziv-manager-overdue-alert:v2';
  private readonly chatBotLinkPollDelayMs = 8000;
  private readonly chatBotLinkPollTimeoutMs = 90000;
  private readonly searchDelayMs = 500;
  private readonly chatBotLinkPolls = new Map<number, ChatBotLinkPoll>();
  private readonly chatBotLinkPollTimers = new Map<number, number>();
  private readonly paymentCopyCache = new Map<number, string>();
  private chatBotLinkRefreshInFlight = false;
  private searchTimer: number | null = null;

  readonly sections = MANAGER_SECTIONS;
  readonly companyActions = MANAGER_COMPANY_ACTIONS;
  readonly allOrderActions = MANAGER_ORDER_ACTIONS;
  readonly pageSizeOptions = MANAGER_PAGE_SIZE_OPTIONS;
  readonly workersRoute = '/worker';
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
  readonly selectedManagerId = signal<number | null>(null);
  readonly selectedControl = signal<string | null>(null);
  readonly companyCreateContext = signal<CompanyCreateContext | null>(null);
  readonly overdueOrders = signal<ManagerOverdueOrders | null>(null);
  readonly overdueModalOpen = signal(false);

  private readonly companyFacade = new ManagerBoardCompanyFacade({
    managerApi: this.managerApi,
    commonBillingApi: this.commonBillingApi,
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
  readonly companyBillingAccounts = this.companyFacade.billingAccounts;
  readonly companyBillingSelectedAccountId = this.companyFacade.billingSelectedAccountId;
  readonly companyBillingDraft = this.companyFacade.billingDraft;
  readonly companyBillingLoading = this.companyFacade.billingLoading;
  readonly companyBillingError = this.companyFacade.billingError;
  readonly companyBillingMutating = this.companyFacade.billingMutating;
  readonly companyBillingSearch = this.companyFacade.billingCompanySearch;
  readonly companyBillingSearchResults = this.companyFacade.billingCompanySearchResults;
  readonly companyBillingSearchLoading = this.companyFacade.billingCompanySearchLoading;
  readonly companyBillingSearchError = this.companyFacade.billingCompanySearchError;
  readonly editOrder = this.orderFacade.editOrder;
  readonly orderDraft = this.orderFacade.orderDraft;
  readonly orderLoading = this.orderFacade.orderLoading;
  readonly orderSaving = this.orderFacade.orderSaving;
  readonly orderError = this.orderFacade.orderError;
  readonly orderDeleting = this.orderFacade.orderDeleting;
  readonly orderCancelingPayment = this.orderFacade.orderCancelingPayment;
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
    const routeSection = this.routeManagerSection();
    const queryView = managerReadQueryView(this.route.snapshot.queryParamMap, routeSection);
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

  ngOnDestroy(): void {
    this.clearSearchTimer();
    for (const timer of this.chatBotLinkPollTimers.values()) {
      window.clearTimeout(timer);
    }
    this.chatBotLinkPollTimers.clear();
    this.chatBotLinkPolls.clear();
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

  @HostListener('window:focus')
  refreshPendingChatBotLinks(): void {
    if (this.chatBotLinkPolls.size > 0) {
      this.reloadBoardForChatBotLinks();
    }
  }

  loadBoard(): void {
    this.loading.set(true);
    this.error.set(null);

    this.managerApi.getBoard({
      section: this.activeSection(),
      status: this.activeStatus(),
      keyword: this.keyword(),
      companyId: this.activeSection() === 'orders' ? this.selectedCompany()?.id : undefined,
      managerId: this.activeSection() === 'orders' ? this.selectedManagerId() : null,
      control: this.activeSection() === 'orders' ? this.selectedControl() : null,
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
      this.orderStatus.set(metric.status === 'Восстановления готовы' ? 'Все' : metric.status);
    }

    this.pageNumber.set(0);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(metric);
  }

  statusOptionLabel(section: ManagerSection, status: string): string {
    const metric = this.findMetric(section, status);
    return managerStatusOptionLabel(status, metric?.value ?? null, metric?.delta ?? 0);
  }

  search(): void {
    this.clearSearchTimer();
    this.pageNumber.set(0);
    this.replaceCurrentHistoryState();
    this.loadBoard();
  }

  onKeywordChange(value: string): void {
    this.keyword.set(value);
    this.scheduleSearch();
  }

  clearSearch(): void {
    this.keyword.set('');
    this.search();
  }

  private scheduleSearch(): void {
    this.clearSearchTimer();
    this.searchTimer = window.setTimeout(() => {
      this.searchTimer = null;
      this.search();
    }, this.searchDelayMs);
  }

  private clearSearchTimer(): void {
    if (this.searchTimer === null) {
      return;
    }
    window.clearTimeout(this.searchTimer);
    this.searchTimer = null;
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
    if (order.commonInvoice) {
      const url = order.commonInvoicePublicUrl || '';
      await this.copyText(
        url,
        `${kind}-${order.id}`,
        kind === 'payment' ? 'Ссылка общего счета скопирована' : 'Ссылка состава скопирована'
      );
      return;
    }

    if (kind === 'review') {
      await this.copyText(
        managerOrderReviewCopyText(order, this.board()?.promoTexts ?? []),
        `review-${order.id}`,
        'Текст проверки скопирован'
      );
      return;
    }

    const cachedPaymentText = this.paymentCopyCache.get(order.id);
    if (cachedPaymentText) {
      await this.copyText(cachedPaymentText, `payment-${order.id}`, 'Текст счета скопирован');
      return;
    }

    try {
      const response = await firstValueFrom(this.paymentsApi.createOrderPaymentLink(order.id));
      const paymentText = response.copyText || response.url;
      if (paymentText) {
        this.paymentCopyCache.set(order.id, paymentText);
      }
      await this.copyText(
        paymentText,
        `payment-${order.id}`,
        'Текст счета скопирован',
        'Счет создан. Если iPhone не дал скопировать, нажмите "счет" еще раз.'
      );
    } catch (err) {
      const message = this.errorMessage(err, 'Не удалось создать ссылку на оплату');
      this.toastService.error('Счет не создан', message);
    }
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

  openAllOrders(): void {
    this.replaceCurrentHistoryState();
    this.activeSection.set('orders');
    this.orderStatus.set('Все');
    this.selectedCompany.set(null);
    this.keyword.set('');
    this.pageNumber.set(0);
    this.mobileMenuOpen.set(false);
    this.pushCurrentHistoryState();
    this.loadBoardAfterMetricSeen(this.findMetric('orders', 'Все'));
  }

  openCompanyEdit(company: CompanyCardItem): void {
    this.companyFacade.openCompanyEdit(company);
  }

  handleChatBotInviteOpened(company: CompanyCardItem): void {
    this.startChatBotLinkPoll(company.id, company.title, company);
  }

  handleOrderChatBotInviteOpened(order: OrderCardItem): void {
    if (!order.companyId) {
      return;
    }

    this.startChatBotLinkPoll(order.companyId, order.companyTitle, order);
  }

  private startChatBotLinkPoll(companyId: number, title: string | null | undefined, item: CompanyCardItem | OrderCardItem): void {
    if (!this.itemNeedsChatBot(item)) {
      return;
    }

    const platform = managerChatBotInviteKind(item);
    if (!platform) {
      return;
    }

    const alreadyWaiting = this.chatBotLinkPolls.has(companyId);
    this.chatBotLinkPolls.set(companyId, { startedAt: Date.now(), platform });
    this.scheduleChatBotLinkRefresh(companyId, this.chatBotLinkPollDelayMs);

    if (!alreadyWaiting) {
      this.showChatBotInviteToast(platform, managerChatBotInviteUrl(item), title || `Компания #${companyId}`);
    }
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
    this.companyDeepReportLaunch.handleCompanyCreated(result);
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

  selectCompanyBillingAccount(accountId: number | null): void {
    this.companyFacade.selectCompanyBillingAccount(accountId);
  }

  handleCompanyBillingDraftChange(change: ManagerCompanyBillingDraftChange): void {
    this.companyFacade.handleCompanyBillingDraftChange(change);
  }

  createCompanyBillingAccount(): void {
    this.companyFacade.createCompanyBillingAccount();
  }

  saveCompanyBillingAccount(): void {
    this.companyFacade.saveCompanyBillingAccount();
  }

  connectCurrentCompanyToBillingAccount(accountId: number): void {
    this.companyFacade.connectCurrentCompanyToBillingAccount(accountId);
  }

  removeCompanyFromBillingAccount(companyId: number): void {
    this.companyFacade.removeCompanyFromBillingAccount(companyId);
  }

  onCompanyBillingSearchChange(value: string): void {
    this.companyFacade.onBillingCompanySearchChange(value);
  }

  addCompanyToBillingAccount(company: CompanyCardItem): void {
    this.companyFacade.addCompanyToBillingAccount(company);
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
    if (order.commonInvoice) {
      void this.router.navigateByUrl(`/manager/common-billing?invoiceId=${order.commonInvoiceId ?? Math.abs(order.id)}`);
      return;
    }

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

  cancelOrderPayment(): void {
    this.orderFacade.cancelOrderPayment();
  }

  updateCompanyStatus(company: CompanyCardItem, action: StatusAction): void {
    this.actionFacade.updateCompanyStatus(company, action);
  }

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    if (order.commonInvoice) {
      this.updateCommonInvoiceStatus(order, action);
      return;
    }

    this.actionFacade.updateOrderStatus(order, action);
  }

  private updateCommonInvoiceStatus(order: OrderCardItem, action: StatusAction): void {
    const invoiceId = order.commonInvoiceId ?? Math.abs(order.id);
    const key = `order-${order.id}-${action.status}`;
    this.mutationKey.set(key);

    const request = (() => {
      switch (action.status) {
        case 'Выставлен счет':
          return this.commonBillingApi.sendInvoice(invoiceId);
        case 'Напоминание':
          return this.commonBillingApi.remind(invoiceId);
        case 'Не оплачено':
          return this.commonBillingApi.markUnpaid(invoiceId);
        case 'Бан':
          return this.commonBillingApi.markBan(invoiceId);
        case 'Оплачено':
          return this.commonBillingApi.markPaid(invoiceId);
        default:
          return null;
      }
    })();

    if (!request) {
      this.mutationKey.set(null);
      return;
    }

    request.subscribe({
      next: (details) => {
        this.mutationKey.set(null);
        this.toastService.success(
          'Общий счет обновлен',
          `${order.companyTitle}: ${this.commonInvoiceStatusLabel(details, action.status)}`
        );
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Общий счет не обновлен', this.errorMessage(err, 'Не удалось изменить общий счет'));
      }
    });
  }

  private commonInvoiceStatusLabel(details: CommonInvoiceDetailsResponse, fallback: string): string {
    switch (details.summary?.status) {
      case 'COLLECTING':
        return 'Ожидает общего счета';
      case 'READY':
        return 'Опубликовано';
      case 'INVOICED':
        return 'Выставлен счет';
      case 'REMINDER':
      case 'PARTIALLY_PAID':
        return 'Напоминание';
      case 'NEEDS_ATTENTION':
        return 'Требует внимания';
      case 'PAID':
        return 'Оплачено';
      case 'UNPAID':
        return 'Не оплачено';
      case 'BAN':
        return 'Бан';
      default:
        return fallback;
    }
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
    return managerOrderActions(order, Boolean(this.selectedCompany()), this.canForceBan());
  }

  private canForceBan(): boolean {
    return this.auth.hasAnyRealmRole(['ADMIN', 'OWNER']);
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
      selectedCompany: this.selectedCompany(),
      managerId: this.selectedManagerId(),
      control: this.selectedControl()
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
    this.selectedManagerId.set(view.managerId);
    this.selectedControl.set(view.control);
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
    return this.router.serializeUrl(this.router.createUrlTree([this.managerRouteForSection(view.activeSection)], {
      queryParams: managerViewQueryParams(view)
    }));
  }

  private routeManagerSection(): ManagerSection | null {
    const section = this.route.snapshot.data['managerSection'];
    return section === 'orders' || section === 'companies' ? section : null;
  }

  private managerRouteForSection(section: ManagerSection): string {
    return section === 'orders' ? '/orders' : '/companies';
  }

  private async copyText(
    text: string,
    copiedKey: string,
    toast: string,
    failureToast = 'Браузер не дал доступ к буферу обмена'
  ): Promise<boolean> {
    const value = text.trim();

    if (!value) {
      return false;
    }

    if (await copyTextToClipboard(value)) {
      this.copied.set(copiedKey);
      this.toastService.success('Скопировано', toast);
      window.setTimeout(() => {
        if (this.copied() === copiedKey) {
          this.copied.set(null);
        }
      }, 1200);
      return true;
    }

    this.toastService.error('Не скопировано', failureToast);
    return false;
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

  private reloadBoardForChatBotLinks(): void {
    if (this.chatBotLinkRefreshInFlight) {
      return;
    }

    this.chatBotLinkRefreshInFlight = true;
    this.managerApi.getBoard({
      section: this.activeSection(),
      status: this.activeStatus(),
      keyword: this.keyword(),
      companyId: this.activeSection() === 'orders' ? this.selectedCompany()?.id : undefined,
      managerId: this.activeSection() === 'orders' ? this.selectedManagerId() : null,
      control: this.activeSection() === 'orders' ? this.selectedControl() : null,
      pageNumber: this.pageNumber(),
      pageSize: this.pageSize(),
      sortDirection: this.sortDirection()
    }).subscribe({
      next: (board) => {
        this.board.set(board);
        this.chatBotLinkRefreshInFlight = false;
        this.updateChatBotLinkPolls(board);
      },
      error: () => {
        this.chatBotLinkRefreshInFlight = false;
        this.reschedulePendingChatBotLinkPolls();
      }
    });
  }

  private updateChatBotLinkPolls(board: ManagerBoard): void {
    const now = Date.now();

    for (const [companyId, poll] of this.chatBotLinkPolls.entries()) {
      const item = this.visibleChatBotItem(board, companyId);
      if (item && !this.itemNeedsChatBot(item)) {
        this.clearChatBotLinkPoll(companyId);
        this.toastService.success(this.chatBotLinkedTitle(poll.platform), this.chatBotItemTitle(item, companyId));
        continue;
      }

      if (now - poll.startedAt >= this.chatBotLinkPollTimeoutMs) {
        this.clearChatBotLinkPoll(companyId);
        continue;
      }

      this.scheduleChatBotLinkRefresh(companyId, this.chatBotLinkPollDelayMs);
    }
  }

  private reschedulePendingChatBotLinkPolls(): void {
    for (const companyId of this.chatBotLinkPolls.keys()) {
      this.scheduleChatBotLinkRefresh(companyId, this.chatBotLinkPollDelayMs);
    }
  }

  private scheduleChatBotLinkRefresh(companyId: number, delayMs: number): void {
    const existingTimer = this.chatBotLinkPollTimers.get(companyId);
    if (existingTimer) {
      window.clearTimeout(existingTimer);
    }

    const timer = window.setTimeout(() => {
      this.chatBotLinkPollTimers.delete(companyId);
      if (this.chatBotLinkPolls.has(companyId)) {
        this.reloadBoardForChatBotLinks();
      }
    }, delayMs);
    this.chatBotLinkPollTimers.set(companyId, timer);
  }

  private clearChatBotLinkPoll(companyId: number): void {
    const timer = this.chatBotLinkPollTimers.get(companyId);
    if (timer) {
      window.clearTimeout(timer);
    }

    this.chatBotLinkPollTimers.delete(companyId);
    this.chatBotLinkPolls.delete(companyId);
  }

  private itemNeedsChatBot(item: CompanyCardItem | OrderCardItem): boolean {
    return Boolean((item.telegramBotInviteUrl ?? '').trim() || (item.maxBotInviteUrl ?? '').trim());
  }

  private visibleChatBotItem(board: ManagerBoard, companyId: number): CompanyCardItem | OrderCardItem | undefined {
    return (board.companies.content ?? []).find((company) => company.id === companyId)
      ?? (board.orders.content ?? []).find((order) => order.companyId === companyId);
  }

  private chatBotItemTitle(item: CompanyCardItem | OrderCardItem, companyId: number): string {
    if ('title' in item) {
      return item.title || `Компания #${companyId}`;
    }

    return item.companyTitle || `Компания #${companyId}`;
  }

  private chatBotLinkedTitle(platform: ChatBotLinkPlatform): string {
    return platform === 'max' ? 'MAX-группа привязана' : 'Telegram-группа привязана';
  }

  private showChatBotInviteToast(platform: ChatBotLinkPlatform, inviteUrl: string, title: string): void {
    if (platform === 'max') {
      const startCommand = this.maxStartCommand(inviteUrl);
      const webStartUrl = this.maxWebStartUrl(inviteUrl);
      this.toastService.warning(
        'MAX: привязка группы',
        'Сначала попробуйте основную MAX-ссылку. Если кнопка запуска не сработала, откройте этот же запуск через MAX Web или скопируйте /start.',
        [
          ...(startCommand ? [{
            label: 'Скопировать /start',
            callback: () => void this.copyText(startCommand, 'max-start', 'Команда MAX скопирована')
          }] : []),
          ...(webStartUrl ? [{
            label: 'Открыть через MAX Web',
            href: webStartUrl
          }] : []),
          {
            label: 'Скопировать ссылку',
            callback: () => void this.copyText(inviteUrl, 'max-link', 'Ссылка MAX скопирована')
          }
        ]
      );
      return;
    }

    this.toastService.info(
      'Жду привязку Telegram',
      `После выбора группы карточка "${title}" обновится сама, текущая страница останется на месте.`
    );
  }

  private maxStartCommand(inviteUrl: string): string {
    try {
      const url = new URL(inviteUrl);
      const payload = (url.searchParams.get('start') ?? '').trim();
      return payload ? `/start ${payload}` : '';
    } catch {
      return '';
    }
  }

  private maxWebStartUrl(inviteUrl: string): string {
    try {
      const url = new URL(inviteUrl);
      url.protocol = 'https:';
      url.hostname = 'web.max.ru';
      return url.toString();
    } catch {
      return '';
    }
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
