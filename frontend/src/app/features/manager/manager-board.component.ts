import { DecimalPipe } from '@angular/common';
import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { appEnvironment } from '../../core/app-environment';
import {
  CompanyOrderCreatePayload,
  CompanyOrderCreateRequest,
  CompanyEditPayload,
  CompanyUpdateRequest,
  CompanyCardItem,
  ManagerApi,
  ManagerBoard,
  ManagerMetric,
  ManagerOption,
  ManagerPage,
  ManagerSection,
  OrderCardItem,
  OrderEditPayload,
  OrderProductOption,
  OrderUpdateRequest
} from '../../core/manager.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { ToastService } from '../../shared/toast.service';

type SectionTab = {
  key: ManagerSection;
  label: string;
  icon: string;
};

type PromoItem = {
  label: string;
  text: string;
};

type StatusAction = {
  label: string;
  status: string;
  icon: string;
};

type MobileNavLink = {
  label: string;
  routerLink?: string;
  href?: string;
};

type SelectedCompany = {
  id: number;
  title: string;
};

type ManagerHistoryView = {
  activeSection: ManagerSection;
  companyStatus: string;
  orderStatus: string;
  keyword: string;
  pageNumber: number;
  pageSize: number;
  sortDirection: 'desc' | 'asc';
  selectedCompany: SelectedCompany | null;
};

@Component({
  selector: 'app-manager-board',
  imports: [AdminLayoutComponent, DecimalPipe, FormsModule, RouterLink],
  templateUrl: './manager-board.component.html',
  styleUrl: './manager-board.component.scss'
})
export class ManagerBoardComponent {
  private readonly historyStateKey = 'otzivManagerView';
  private readonly preferredCreateOrderProduct = 'отзыв2гис+';
  private readonly managerApi = inject(ManagerApi);
  private readonly toastService = inject(ToastService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly emptyCompanyPage: ManagerPage<CompanyCardItem> = {
    content: [],
    number: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true
  };
  private readonly emptyOrderPage: ManagerPage<OrderCardItem> = {
    content: [],
    number: 0,
    size: 10,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true
  };

  readonly sections: SectionTab[] = [
    { key: 'companies', label: 'Компании', icon: 'business' },
    { key: 'orders', label: 'Заказы', icon: 'inventory_2' }
  ];

  readonly companyActions: StatusAction[] = [
    { label: 'предложил', status: 'Ожидание', icon: 'outgoing_mail' },
    { label: 'разослал', status: 'К рассылке', icon: 'campaign' },
    { label: 'на стоп', status: 'На стопе', icon: 'pause_circle' },
    { label: 'бан', status: 'Бан', icon: 'block' }
  ];
  readonly allOrderActions: StatusAction[] = [
    { label: 'на проверку', status: 'На проверке', icon: 'manage_search' },
    { label: 'коррекция', status: 'Коррекция', icon: 'build_circle' },
    { label: 'одобрено', status: 'Публикация', icon: 'task_alt' },
    { label: 'архив', status: 'Архив', icon: 'archive' },
    { label: 'опублик.', status: 'Опубликовано', icon: 'published_with_changes' },
    { label: 'счет', status: 'Выставлен счет', icon: 'receipt_long' },
    { label: 'напомнить', status: 'Напоминание', icon: 'notifications_active' },
    { label: 'не опл.', status: 'Не оплачено', icon: 'money_off' },
    { label: 'оплатили', status: 'Оплачено', icon: 'payments' }
  ];

  readonly pageSizeOptions = [5, 10, 15];
  readonly legacyLeadsUrl = this.legacyUrl('/lead');
  readonly legacyNewCompanyUrl = this.legacyUrl('/companies/new_company');
  readonly legacyCategoriesUrl = this.legacyUrl('/categories');
  readonly legacyWorkersUrl = '/worker';
  readonly mobileNavLinks: MobileNavLink[] = [
    { label: 'Главная', routerLink: '/' },
    { label: 'Лиды', routerLink: '/leads' },
    { label: 'Оператор', routerLink: '/operator' },
    { label: 'Маркетолог', href: this.legacyUrl('/admin/analyse') },
    { label: 'Менеджер', routerLink: '/manager' },
    { label: 'Специалист', href: '/worker' },
    { label: 'Личный кабинет', routerLink: '/' }
  ];

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

  readonly currentCompanies = computed(() => this.board()?.companies.content ?? []);
  readonly currentOrders = computed(() => this.board()?.orders.content ?? []);
  readonly selectedCreateOrderProduct = computed(() => {
    const payload = this.createOrderPayload();
    const draft = this.createOrderDraft();
    return payload?.products.find((product) => product.id === draft?.productId) ?? null;
  });
  readonly createOrderTotal = computed(() => {
    const product = this.selectedCreateOrderProduct();
    const amount = this.createOrderDraft()?.amount ?? 0;
    return (product?.price ?? 0) * amount;
  });
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
    return this.board()?.companyStatuses ?? ['Все', 'Новая', 'В работе', 'Новый заказ', 'К рассылке', 'Ожидание', 'На стопе', 'Бан'];
  });
  readonly orderStatusOptions = computed(() => {
    return this.board()?.orderStatuses ?? [
      'Все',
      'Новый',
      'В проверку',
      'На проверке',
      'Коррекция',
      'Публикация',
      'Опубликовано',
      'Выставлен счет',
      'Напоминание',
      'Не оплачено',
      'Архив',
      'Оплачено'
    ];
  });
  readonly statusOptions = computed(() => {
    return this.activeSection() === 'companies' ? this.companyStatusOptions() : this.orderStatusOptions();
  });
  readonly sortTitle = computed(() => this.sortDirection() === 'desc'
    ? 'Сначала давно без изменений'
    : 'Сначала недавно измененные'
  );
  readonly title = computed(() => {
    const selectedCompany = this.selectedCompany();
    if (this.activeSection() === 'orders' && selectedCompany) {
      return `Заказы - ${selectedCompany.title}`;
    }

    const section = this.activeSection() === 'companies' ? 'Компании' : 'Заказы';
    const status = this.activeStatus();
    return status === 'Все' ? section : status;
  });
  readonly layoutTitle = computed(() => {
    const selectedCompany = this.selectedCompany();
    if (this.activeSection() === 'orders' && selectedCompany) {
      return `Заказы - ${selectedCompany.title}`;
    }

    const section = this.activeSection() === 'companies' ? 'Компании' : 'Заказы';
    return `${section} - ${this.activeStatus()}`;
  });
  readonly promoItems = computed<PromoItem[]>(() => {
    const texts = this.board()?.promoTexts ?? [];

    if (this.activeSection() === 'orders') {
      return [
        { label: 'пояснение', text: texts[10] ?? '' },
        { label: 'напоминание', text: texts[5] ?? '' },
        { label: 'угроза', text: texts[6] ?? '' }
      ];
    }

    return [
      { label: 'предложение', text: texts[0] ?? '' },
      { label: 'пояснение', text: texts[10] ?? '' },
      { label: 'рассылка', text: texts[9] ?? '' }
    ];
  });
  readonly metrics = computed(() => {
    return (this.board()?.metrics ?? []).filter((metric) => metric.section === this.activeSection());
  });

  constructor() {
    const queryView = this.readQueryView();
    const restoredView = this.readHistoryView(window.history.state);
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
    const view = this.readHistoryView(event.state);

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
    return count === null ? status : `${status}: ${count}`;
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
        ? this.absoluteAppUrl(this.reviewCheckPath(order.orderDetailsId))
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
    return order.totalSumWithBadReviews ?? order.sum ?? 0;
  }

  showBadReviewSummary(order: OrderCardItem): boolean {
    return order.status !== 'Оплачено' && (order.badReviewTasksTotal ?? 0) > 0;
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

  setCompanyEditField<K extends keyof CompanyUpdateRequest>(field: K, value: CompanyUpdateRequest[K]): void {
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

    const confirmed = window.confirm(`Убрать специалиста "${worker.label}" из компании?`);
    if (!confirmed) {
      return;
    }

    const key = `worker-${worker.id}`;
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

    const confirmed = window.confirm(`Удалить филиал "${title || filialId}"?`);
    if (!confirmed) {
      return;
    }

    const key = `filial-${filialId}`;
    this.editDeleteKey.set(key);
    this.editError.set(null);

    this.managerApi.deleteCompanyFilial(company.id, filialId).subscribe({
      next: (payload) => {
        this.editDeleteKey.set(null);
        this.applyCompanyEditPayload(payload);
        this.toastService.success('Филиал удален', title || `#${filialId}`);
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
    this.createOrderLoading.set(true);
    this.createOrderSaving.set(false);
    this.createOrderError.set(null);
    this.createOrderPayload.set(null);
    this.createOrderDraft.set(null);

    this.managerApi.getCompanyOrderCreate(company.id).subscribe({
      next: (payload) => {
        this.applyCompanyOrderCreatePayload(payload);
        this.createOrderLoading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось открыть создание заказа');
        this.createOrderLoading.set(false);
        this.createOrderError.set(message);
        this.toastService.error('Заказ не открыт', message);
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

  setCreateOrderField<K extends keyof CompanyOrderCreateRequest>(field: K, value: CompanyOrderCreateRequest[K]): void {
    this.createOrderDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  createCompanyOrder(): void {
    const payload = this.createOrderPayload();
    const draft = this.createOrderDraft();

    if (!payload || !draft) {
      return;
    }

    if (!draft.productId || !draft.amount || !draft.workerId || !draft.filialId) {
      this.createOrderError.set('Выберите продукт, количество, специалиста и филиал');
      return;
    }

    this.createOrderSaving.set(true);
    this.createOrderError.set(null);

    this.managerApi.createCompanyOrder(payload.companyId, draft).subscribe({
      next: (result) => {
        this.createOrderSaving.set(false);
        this.closeCompanyOrderCreate();
        this.toastService.success('Заказ создан', `${result.companyTitle}: ${result.productTitle} x ${result.amount}`);
        this.openCreatedCompanyOrders(result.companyId, result.companyTitle);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось создать заказ');
        this.createOrderSaving.set(false);
        this.createOrderError.set(message);
        this.toastService.error('Заказ не создан', message);
      }
    });
  }

  openOrderEdit(order: OrderCardItem): void {
    this.orderLoading.set(true);
    this.orderError.set(null);
    this.orderDeleting.set(false);

    this.managerApi.getOrderEdit(order.id).subscribe({
      next: (payload) => {
        this.applyOrderEditPayload(payload);
        this.orderLoading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось открыть редактирование заказа');
        this.orderLoading.set(false);
        this.orderError.set(message);
        this.toastService.error('Заказ не открыт', message);
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

  setOrderEditField<K extends keyof OrderUpdateRequest>(field: K, value: OrderUpdateRequest[K]): void {
    this.orderDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  saveOrderEdit(): void {
    const order = this.editOrder();
    const draft = this.orderDraft();

    if (!order || !draft) {
      return;
    }

    this.orderSaving.set(true);
    this.orderError.set(null);

    this.managerApi.updateOrder(order.id, draft).subscribe({
      next: () => {
        this.orderSaving.set(false);
        this.closeOrderEdit();
        this.toastService.success('Заказ сохранен', `Изменения по заказу #${order.id} применены`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить заказ');
        this.orderError.set(message);
        this.orderSaving.set(false);
        this.toastService.error('Заказ не сохранен', message);
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

    this.managerApi.deleteOrder(order.id).subscribe({
      next: () => {
        this.orderDeleting.set(false);
        this.closeOrderEdit();
        this.toastService.success('Заказ удален', `Заказ #${order.id} удален`);
        this.loadBoard();
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить заказ');
        this.orderDeleting.set(false);
        this.orderError.set(message);
        this.toastService.error('Заказ не удален', message);
      }
    });
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

  orderActions(order: OrderCardItem): StatusAction[] {
    if (this.selectedCompany()) {
      return this.allOrderActions;
    }

    const status = order.status;
    const actions: StatusAction[] = [];

    if (status === 'В проверку' || status === 'Архив') {
      actions.push({ label: 'на проверку', status: 'На проверке', icon: 'manage_search' });
    }

    if (status === 'На проверке') {
      actions.push({ label: 'коррекция', status: 'Коррекция', icon: 'build_circle' });
      actions.push({ label: 'архив', status: 'Архив', icon: 'archive' });
    }

    if (status === 'На проверке' || status === 'Коррекция' || status === 'Архив') {
      actions.push({ label: 'одобрено', status: 'Публикация', icon: 'task_alt' });
    }

    if (status === 'Публикация') {
      actions.push({ label: 'опублик.', status: 'Опубликовано', icon: 'published_with_changes' });
    }

    if (status === 'Опубликовано') {
      actions.push({ label: 'счет', status: 'Выставлен счет', icon: 'receipt_long' });
    }

    if (status === 'Выставлен счет') {
      actions.push({ label: 'напомнить', status: 'Напоминание', icon: 'notifications_active' });
    }

    if (status === 'Выставлен счет' || status === 'Напоминание') {
      actions.push({ label: 'не опл.', status: 'Не оплачено', icon: 'money_off' });
    }

    if (status === 'Выставлен счет' || status === 'Напоминание' || status === 'Не оплачено') {
      actions.push({ label: 'оплатили', status: 'Оплачено', icon: 'payments' });
    }

    return actions;
  }

  isMutating(key: string): boolean {
    return this.mutationKey() === key;
  }

  companyChatUrl(company: CompanyCardItem): string {
    return company.urlChat || `tel:${company.telephone ?? ''}`;
  }

  orderChatUrl(order: OrderCardItem): string {
    return order.companyUrlChat || `tel:${order.companyTelephone ?? ''}`;
  }

  companyFilialUrl(company: CompanyCardItem): string {
    return company.urlFilial || this.legacyUrl(`/companies/editCompany/${company.id}`);
  }

  companyOrderUrl(company: CompanyCardItem): string {
    return this.legacyUrl(`/ordersCompany/${company.id}`);
  }

  filialEditUrl(filialId: number): string {
    return this.legacyUrl(`/filial/edit/${filialId}`);
  }

  orderDetailsUrl(order: OrderCardItem): string {
    return this.legacyUrl(`/ordersCompany/ordersDetails/${order.companyId}/${order.id}`);
  }

  orderInfoUrl(order: OrderCardItem): string {
    return this.legacyUrl(`/ordersDetails/${order.companyId}/${order.id}`);
  }

  orderReviewUrl(order: OrderCardItem): string {
    return order.orderDetailsId ? this.reviewCheckPath(order.orderDetailsId) : '';
  }

  progress(order: OrderCardItem): number {
    if (!order.amount || !order.counter) {
      return 0;
    }

    return Math.max(0, Math.min(100, Math.round((order.counter / order.amount) * 100)));
  }

  optionLabel(option: ManagerOption): string {
    return option.label || `ID ${option.id}`;
  }

  trackSection(_index: number, section: SectionTab): ManagerSection {
    return section.key;
  }

  trackStatus(_index: number, status: string): string {
    return status;
  }

  trackCompany(_index: number, company: CompanyCardItem): number {
    return company.id;
  }

  trackOrder(_index: number, order: OrderCardItem): number {
    return order.id;
  }

  trackMetric(_index: number, metric: ManagerMetric): string {
    return `${metric.section}-${metric.status}`;
  }

  trackAction(_index: number, action: StatusAction): string {
    return action.status;
  }

  trackOption(_index: number, option: ManagerOption): number {
    return option.id;
  }

  trackProduct(_index: number, product: { id: number }): number {
    return product.id;
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
    const state = this.withManagerHistoryState(this.captureHistoryView());
    window.history.replaceState(state, document.title, window.location.href);
  }

  private pushCurrentHistoryState(): void {
    const state = this.withManagerHistoryState(this.captureHistoryView());
    window.history.pushState(state, document.title, window.location.href);
  }

  private withManagerHistoryState(view: ManagerHistoryView): Record<string, unknown> {
    const existingState = window.history.state;
    const baseState = existingState && typeof existingState === 'object' ? existingState : {};

    return {
      ...baseState,
      [this.historyStateKey]: view
    };
  }

  private readHistoryView(state: unknown): ManagerHistoryView | null {
    if (!state || typeof state !== 'object' || !(this.historyStateKey in state)) {
      return null;
    }

    const view = (state as Record<string, unknown>)[this.historyStateKey];
    if (!view || typeof view !== 'object') {
      return null;
    }

    const raw = view as Partial<ManagerHistoryView>;
    const activeSection: ManagerSection = raw.activeSection === 'orders' ? 'orders' : 'companies';
    const selectedCompany = this.normalizeSelectedCompany(raw.selectedCompany);

    return {
      activeSection,
      companyStatus: typeof raw.companyStatus === 'string' ? raw.companyStatus : 'Все',
      orderStatus: typeof raw.orderStatus === 'string' ? raw.orderStatus : 'Все',
      keyword: typeof raw.keyword === 'string' ? raw.keyword : '',
      pageNumber: typeof raw.pageNumber === 'number' ? Math.max(raw.pageNumber, 0) : 0,
      pageSize: typeof raw.pageSize === 'number' ? raw.pageSize : 10,
      sortDirection: raw.sortDirection === 'asc' ? 'asc' : 'desc',
      selectedCompany: activeSection === 'orders' ? selectedCompany : null
    };
  }

  private readQueryView(): ManagerHistoryView | null {
    const params = this.route.snapshot.queryParamMap;
    const section = params.get('section');
    if (section !== 'orders' && section !== 'companies') {
      return null;
    }

    if (section === 'companies') {
      return {
        activeSection: 'companies',
        companyStatus: params.get('status')?.trim() || 'Все',
        orderStatus: 'Все',
        keyword: params.get('keyword')?.trim() || '',
        pageNumber: Math.max(Number(params.get('pageNumber')) || 0, 0),
        pageSize: Number(params.get('pageSize')) || 10,
        sortDirection: params.get('sortDirection') === 'asc' ? 'asc' : 'desc',
        selectedCompany: null
      };
    }

    const companyId = Number(params.get('companyId'));
    const selectedCompany = Number.isFinite(companyId) && companyId > 0
      ? {
          id: companyId,
          title: params.get('companyTitle')?.trim() || `Компания #${companyId}`
        }
      : null;

    return {
      activeSection: 'orders',
      companyStatus: 'Все',
      orderStatus: params.get('status')?.trim() || 'Все',
      keyword: params.get('keyword')?.trim() || '',
      pageNumber: Math.max(Number(params.get('pageNumber')) || 0, 0),
      pageSize: Number(params.get('pageSize')) || 10,
      sortDirection: params.get('sortDirection') === 'asc' ? 'asc' : 'desc',
      selectedCompany
    };
  }

  private normalizeSelectedCompany(value: unknown): SelectedCompany | null {
    if (!value || typeof value !== 'object') {
      return null;
    }

    const selected = value as Partial<SelectedCompany>;
    if (typeof selected.id !== 'number') {
      return null;
    }

    return {
      id: selected.id,
      title: typeof selected.title === 'string' && selected.title.trim()
        ? selected.title
        : `Компания #${selected.id}`
    };
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

  private legacyUrl(path: string): string {
    return `${appEnvironment.legacyBaseUrl}${path}`;
  }

  private reviewCheckPath(orderDetailsId: string): string {
    return `/review/editReviews/${orderDetailsId}`;
  }

  private absoluteAppUrl(path: string): string {
    return new URL(path, window.location.origin).toString();
  }

  private applyCompanyEditPayload(payload: CompanyEditPayload): void {
    this.editCompany.set(payload);
    this.editDraft.set({
      title: payload.title,
      urlChat: payload.urlChat,
      telephone: payload.telephone,
      city: payload.city,
      email: payload.email,
      categoryId: payload.category?.id ?? null,
      subCategoryId: payload.subCategory?.id ?? null,
      statusId: payload.status?.id ?? null,
      managerId: payload.manager?.id ?? null,
      commentsCompany: payload.commentsCompany,
      active: payload.active,
      newWorkerId: null,
      newFilialCityId: null,
      newFilialTitle: '',
      newFilialUrl: ''
    });
  }

  private applyOrderEditPayload(payload: OrderEditPayload): void {
    this.editOrder.set(payload);
    this.orderDraft.set({
      filialId: payload.filial?.id ?? null,
      workerId: payload.worker?.id ?? null,
      managerId: payload.manager?.id ?? null,
      counter: payload.counter ?? 0,
      orderComments: payload.orderComments,
      commentsCompany: payload.commentsCompany,
      complete: payload.complete
    });
  }

  private applyCompanyOrderCreatePayload(payload: CompanyOrderCreatePayload): void {
    const defaultProductId = this.preferredOrderProduct(payload.products)?.id
      ?? payload.defaultProductId
      ?? payload.products[0]?.id
      ?? null;
    const defaultAmount = payload.amounts.includes(5)
      ? 5
      : payload.defaultAmount ?? payload.amounts[0] ?? 5;

    this.createOrderPayload.set(payload);
    this.createOrderDraft.set({
      productId: defaultProductId,
      amount: defaultAmount,
      workerId: payload.defaultWorkerId ?? payload.workers[0]?.id ?? null,
      filialId: payload.defaultFilialId ?? payload.filials[0]?.id ?? null
    });
  }

  private preferredOrderProduct(products: OrderProductOption[]): OrderProductOption | undefined {
    return products.find((product) => this.normalizeProductLabel(product.label) === this.preferredCreateOrderProduct);
  }

  private normalizeProductLabel(label: string): string {
    return label
      .toLocaleLowerCase('ru-RU')
      .replace(/ё/g, 'е')
      .replace(/\s+/g, '');
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
    if (err && typeof err === 'object' && 'error' in err) {
      const body = (err as { error?: { message?: string } | string }).error;

      if (typeof body === 'string' && body.trim()) {
        return body;
      }

      if (body && typeof body === 'object' && 'message' in body && body.message) {
        return body.message;
      }
    }

    if (err && typeof err === 'object' && 'message' in err) {
      return String((err as { message?: string }).message ?? fallback);
    }

    return fallback;
  }
}
