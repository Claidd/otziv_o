import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  CompanyEditPayload,
  CompanyUpdateRequest,
  CompanyCardItem,
  ManagerApi,
  ManagerBoard,
  ManagerMetric,
  ManagerOption,
  ManagerPage,
  ManagerSection,
  OrderCardItem
} from '../../core/manager.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
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
  managerAbsoluteAppUrl,
  managerBoardTitle,
  managerErrorMessage,
  managerLayoutTitle,
  managerLegacyUrl,
  managerOrderActions,
  managerPayableOrderSum,
  managerPromoItems,
  managerReviewCheckPath,
  managerStatusOptionLabel,
  trackManagerCompany,
  trackManagerMetric,
  trackManagerOrder,
  trackManagerStatus
} from './manager-board.config';
import { ManagerCompanyCardComponent } from './manager-company-card.component';
import type { ManagerCompanyEditDraftChange } from './manager-company-edit-modal.component';
import { ManagerCompanyEditModalComponent } from './manager-company-edit-modal.component';
import {
  managerCompanyEditDraft,
  managerCompanyFilialDeletedLabel,
  managerCompanyFilialDeleteConfirm,
  managerCompanyFilialDeleteKey,
  managerCompanyWorkerDeleteConfirm,
  managerCompanyWorkerDeleteKey
} from './manager-board.company.helpers';
import {
  managerReadHistoryView,
  managerReadQueryView,
  managerWithHistoryState
} from './manager-board.history';
import { ManagerBoardOrderFacade } from './manager-board-order.facade';
import { ManagerOrderCardComponent } from './manager-order-card.component';
import type { ManagerOrderEditDraftChange } from './manager-order-edit-modal.component';
import { ManagerOrderEditModalComponent } from './manager-order-edit-modal.component';
import type { ManagerCreateOrderDraftChange } from './manager-order-create-modal.component';
import { ManagerOrderCreateModalComponent } from './manager-order-create-modal.component';

@Component({
  selector: 'app-manager-board',
  imports: [
    AdminLayoutComponent,
    FormsModule,
    ManagerCompanyCardComponent,
    ManagerCompanyEditModalComponent,
    ManagerOrderCardComponent,
    ManagerOrderEditModalComponent,
    ManagerOrderCreateModalComponent,
    RouterLink
  ],
  templateUrl: './manager-board.component.html',
  styleUrl: './manager-board.component.scss'
})
export class ManagerBoardComponent {
  private readonly historyStateKey = MANAGER_HISTORY_STATE_KEY;
  private readonly managerApi = inject(ManagerApi);
  private readonly toastService = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly emptyCompanyPage = EMPTY_MANAGER_COMPANY_PAGE;
  private readonly emptyOrderPage = EMPTY_MANAGER_ORDER_PAGE;

  readonly sections = MANAGER_SECTIONS;
  readonly companyActions = MANAGER_COMPANY_ACTIONS;
  readonly allOrderActions = MANAGER_ORDER_ACTIONS;
  readonly pageSizeOptions = MANAGER_PAGE_SIZE_OPTIONS;
  readonly legacyLeadsUrl = managerLegacyUrl('/lead');
  readonly legacyNewCompanyUrl = managerLegacyUrl('/companies/new_company');
  readonly legacyCategoriesUrl = managerLegacyUrl('/categories');
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
  readonly editCompany = signal<CompanyEditPayload | null>(null);
  readonly editDraft = signal<CompanyUpdateRequest | null>(null);
  readonly editLoading = signal(false);
  readonly editSaving = signal(false);
  readonly editError = signal<string | null>(null);
  readonly editDeleteKey = signal<string | null>(null);

  private readonly orderFacade = new ManagerBoardOrderFacade({
    managerApi: this.managerApi,
    toastService: this.toastService,
    loadBoard: () => this.loadBoard(),
    errorMessage: (err, fallback) => this.errorMessage(err, fallback),
    openCreatedCompanyOrders: (result) => this.openCreatedCompanyOrders(result.companyId, result.companyTitle)
  });
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
    this.loadBoard();
  }

  setStatus(status: string): void {
    this.replaceCurrentHistoryState();
    if (this.activeSection() === 'companies') {
      this.companyStatus.set(status);
    } else {
      this.orderStatus.set(status);
    }

    this.selectedCompany.set(null);
    this.pageNumber.set(0);
    this.pushCurrentHistoryState();
    this.loadBoard();
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

    const [section, status = 'Все'] = value.split(':');

    if (section === 'companies') {
      this.replaceCurrentHistoryState();
      this.activeSection.set('companies');
      this.companyStatus.set(status);
      this.selectedCompany.set(null);
      this.pageNumber.set(0);
      this.mobileMenuOpen.set(false);
      this.pushCurrentHistoryState();
      this.loadBoard();
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
      this.loadBoard();
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
    this.loadBoard();
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
    await this.copyText(phone ?? '', 'телефон', 'Телефон скопирован');
  }

  async copyOrderText(order: OrderCardItem, kind: 'review' | 'payment'): Promise<void> {
    if (kind === 'review') {
      const url = order.orderDetailsId
        ? managerAbsoluteAppUrl(managerReviewCheckPath(order.orderDetailsId))
        : '';
      await this.copyText(
        `Ссылка на проверку отзывов: ${url}`,
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
    this.editLoading.set(true);
    this.editError.set(null);
    this.editDeleteKey.set(null);

    this.managerApi.getCompanyEdit(company.id).subscribe({
      next: (payload) => {
        this.applyCompanyEditPayload(payload);
        this.editLoading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось открыть редактирование компании');
        this.editLoading.set(false);
        this.editError.set(message);
        this.toastService.error('Компания не открыта', message);
      }
    });
  }

  closeCompanyEdit(): void {
    if (this.editLoading() || this.editSaving() || this.editDeleteKey()) {
      return;
    }

    this.editCompany.set(null);
    this.editDraft.set(null);
    this.editError.set(null);
  }

  handleCompanyEditDraftChange(change: ManagerCompanyEditDraftChange): void {
    this.setCompanyEditField(change.field, change.value);
  }

  private setCompanyEditField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
    this.editDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  changeCompanyCategory(categoryId: number | null): void {
    this.setCompanyEditField('categoryId', categoryId);
    this.setCompanyEditField('subCategoryId', null);

    if (!categoryId) {
      return;
    }

    this.managerApi.getCompanySubcategories(categoryId).subscribe({
      next: (subCategories) => {
        this.editCompany.update((company) => company ? { ...company, subCategories } : company);
        this.editDraft.update((draft) => draft ? { ...draft, subCategoryId: subCategories[0]?.id ?? null } : draft);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить подкатегории');
        this.editError.set(message);
        this.toastService.error('Подкатегории не загружены', message);
      }
    });
  }

  saveCompanyEdit(): void {
    const company = this.editCompany();
    const draft = this.editDraft();

    if (!company || !draft) {
      return;
    }

    this.editSaving.set(true);
    this.editError.set(null);

    this.managerApi.updateCompany(company.id, draft).subscribe({
      next: () => {
        this.editSaving.set(false);
        this.closeCompanyEdit();
        this.toastService.success('Компания сохранена', `Изменения по компании #${company.id} применены`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить компанию');
        this.editError.set(message);
        this.editSaving.set(false);
        this.toastService.error('Компания не сохранена', message);
      }
    });
  }

  deleteCompanyWorker(worker: ManagerOption): void {
    const company = this.editCompany();

    if (!company || this.editDeleteKey()) {
      return;
    }

    const confirmed = window.confirm(managerCompanyWorkerDeleteConfirm(worker));
    if (!confirmed) {
      return;
    }

    const key = managerCompanyWorkerDeleteKey(worker);
    this.editDeleteKey.set(key);
    this.editError.set(null);

    this.managerApi.deleteCompanyWorker(company.id, worker.id).subscribe({
      next: (payload) => {
        this.editDeleteKey.set(null);
        this.applyCompanyEditPayload(payload);
        this.toastService.success('Специалист удален', worker.label);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить специалиста');
        this.editDeleteKey.set(null);
        this.editError.set(message);
        this.toastService.error('Специалист не удален', message);
      }
    });
  }

  deleteCompanyFilial(filialId: number, title: string): void {
    const company = this.editCompany();

    if (!company || this.editDeleteKey()) {
      return;
    }

    const confirmed = window.confirm(managerCompanyFilialDeleteConfirm(filialId, title));
    if (!confirmed) {
      return;
    }

    const key = managerCompanyFilialDeleteKey(filialId);
    this.editDeleteKey.set(key);
    this.editError.set(null);

    this.managerApi.deleteCompanyFilial(company.id, filialId).subscribe({
      next: (payload) => {
        this.editDeleteKey.set(null);
        this.applyCompanyEditPayload(payload);
        this.toastService.success('Филиал удален', managerCompanyFilialDeletedLabel(filialId, title));
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить филиал');
        this.editDeleteKey.set(null);
        this.editError.set(message);
        this.toastService.error('Филиал не удален', message);
      }
    });
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
    const key = `company-${company.id}-${action.status}`;
    this.mutationKey.set(key);

    this.managerApi.updateCompanyStatus(company.id, action.status).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Статус изменен', `${company.title}: ${action.status}`);
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Статус не изменен', this.errorMessage(err, 'Не удалось изменить статус компании'));
      }
    });
  }

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    const key = `order-${order.id}-${action.status}`;
    this.mutationKey.set(key);

    this.managerApi.updateOrderStatus(order.id, action.status).subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Статус изменен', `${order.companyTitle}: ${action.status}`);
        this.loadBoard();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Статус не изменен', this.errorMessage(err, 'Не удалось изменить статус заказа'));
      }
    });
  }

  saveCompanyCardNote(company: CompanyCardItem, value: string): void {
    this.managerApi.updateCompanyNote(company.id, value).subscribe({
      next: () => {
        this.toastService.success('Заметка компании сохранена', company.title || `Компания #${company.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.toastService.error('Заметка не сохранена', this.errorMessage(err, 'Не удалось сохранить заметку компании'));
      }
    });
  }

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    this.managerApi.updateOrderCompanyNote(order.id, value).subscribe({
      next: () => {
        this.toastService.success('Заметка компании сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.toastService.error('Заметка не сохранена', this.errorMessage(err, 'Не удалось сохранить заметку компании'));
      }
    });
  }

  saveOrderCardNote(order: OrderCardItem, value: string): void {
    this.managerApi.updateOrderNote(order.id, value).subscribe({
      next: () => {
        this.toastService.success('Заметка заказа сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.loadBoard();
      },
      error: (err) => {
        this.toastService.error('Заметка не сохранена', this.errorMessage(err, 'Не удалось сохранить заметку заказа'));
      }
    });
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

  private metricValue(section: ManagerSection, status: string): number | null {
    const metric = this.board()?.metrics.find((item) => item.section === section && item.status === status);
    return metric?.value ?? null;
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
    const state = managerWithHistoryState(window.history.state, this.captureHistoryView(), this.historyStateKey);
    window.history.replaceState(state, document.title, window.location.href);
  }

  private pushCurrentHistoryState(): void {
    const state = managerWithHistoryState(window.history.state, this.captureHistoryView(), this.historyStateKey);
    window.history.pushState(state, document.title, window.location.href);
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

  private applyCompanyEditPayload(payload: CompanyEditPayload): void {
    this.editCompany.set(payload);
    this.editDraft.set(managerCompanyEditDraft(payload));
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

  private errorMessage(err: unknown, fallback: string): string {
    return managerErrorMessage(err, fallback);
  }
}
