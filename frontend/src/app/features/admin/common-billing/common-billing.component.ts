import { DatePipe } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  CommonBillingAccountResponse,
  CommonBillingApi,
  CommonInvoiceDetailsResponse,
  CommonInvoiceOrderResponse,
  CommonInvoiceSummaryResponse
} from '../../../core/common-billing.api';
import { CompanyCardItem, ManagerApi, OrderCardItem } from '../../../core/manager.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorDetail } from '../../../shared/api-error-message';
import { copyTextToClipboard } from '../../../shared/clipboard-copy';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';
import { ManagerBoardOrderFacade } from '../../manager/manager-board-order.facade';
import { ManagerOrderCardComponent } from '../../manager/manager-order-card.component';
import type { ManagerOrderEditDraftChange } from '../../manager/manager-order-edit-modal.component';
import { ManagerOrderEditModalComponent } from '../../manager/manager-order-edit-modal.component';
import {
  StatusAction,
  managerErrorMessage,
  managerOrderActions,
  managerOrderReviewCopyText
} from '../../manager/manager-board.config';

type AccountDraft = {
  name: string;
  enabled: boolean;
  autoRepeatOrders: boolean;
  managerId: string;
  invoiceCompanyId: string;
};

type DraftCompany = {
  id: number;
  title: string;
  city?: string;
  status?: string;
};

@Component({
  selector: 'app-common-billing',
  imports: [
    AdminLayoutComponent,
    DatePipe,
    FormsModule,
    LoadErrorCardComponent,
    ManagerOrderCardComponent,
    ManagerOrderEditModalComponent,
    RouterLink
  ],
  templateUrl: './common-billing.component.html',
  styleUrl: './common-billing.component.scss'
})
export class CommonBillingComponent implements OnDestroy {
  private readonly commonBillingApi = inject(CommonBillingApi);
  private readonly managerApi = inject(ManagerApi);
  private readonly toastService = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly routeSubscription: Subscription;
  private companySearchTimer: number | null = null;
  private companySearchRun = 0;

  readonly accounts = signal<CommonBillingAccountResponse[]>([]);
  readonly selectedAccountId = signal<number | null>(null);
  readonly invoiceDetails = signal<CommonInvoiceDetailsResponse | null>(null);
  readonly loading = signal(false);
  readonly invoiceLoading = signal(false);
  readonly error = signal('');
  readonly mutating = signal('');
  readonly copied = signal('');
  readonly companySearch = signal('');
  readonly companySearchResults = signal<CompanyCardItem[]>([]);
  readonly companySearchLoading = signal(false);
  readonly companySearchError = signal('');
  readonly draftCompanies = signal<DraftCompany[]>([]);
  readonly draft = signal<AccountDraft>({
    name: '',
    enabled: true,
    autoRepeatOrders: true,
    managerId: '',
    invoiceCompanyId: ''
  });
  readonly managerInvoiceDetailMode = this.router.url.startsWith('/manager/common-billing');
  private readonly orderFacade = new ManagerBoardOrderFacade({
    managerApi: this.managerApi,
    toastService: this.toastService,
    loadBoard: () => this.loadSelectedInvoice(),
    errorMessage: (err, fallback) => apiErrorDetail(err, fallback),
    openCreatedCompanyOrders: () => undefined
  });
  readonly editOrder = this.orderFacade.editOrder;
  readonly orderDraft = this.orderFacade.orderDraft;
  readonly orderLoading = this.orderFacade.orderLoading;
  readonly orderSaving = this.orderFacade.orderSaving;
  readonly orderError = this.orderFacade.orderError;
  readonly orderDeleting = this.orderFacade.orderDeleting;
  readonly orderCancelingPayment = this.orderFacade.orderCancelingPayment;

  readonly selectedAccount = computed(() => {
    const id = this.selectedAccountId();
    return this.accounts().find((account) => account.id === id) ?? null;
  });
  readonly currentInvoice = computed(() => this.invoiceDetails()?.summary ?? this.selectedAccount()?.currentInvoice ?? null);
  readonly invoiceOrders = computed(() => this.invoiceDetails()?.orders ?? []);
  readonly invoiceOrderCards = computed(() => this.invoiceDetails()?.orderCards ?? []);
  readonly invoiceNeedsAttention = computed(() => this.currentInvoice()?.status === 'NEEDS_ATTENTION');
  readonly reviewApprovalCount = computed(() => this.invoiceOrders()
    .filter(order => order.orderStatus === 'В проверку' || order.orderStatus === 'На проверке')
    .length);
  readonly canApproveReviewOrders = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(invoice && invoice.status !== 'NEEDS_ATTENTION' && this.reviewApprovalCount() > 0);
  });
  readonly attentionError = computed(() => (this.currentInvoice()?.lastError ?? '').trim().toLowerCase());
  readonly attentionHasLatePayment = computed(() => {
    const error = this.attentionError();
    return error.startsWith('late_tbank_payment') || error.startsWith('late_payment_');
  });
  readonly attentionHasFinalCancelFailure = computed(() => this.attentionError().startsWith('payment_cancel_failed_final'));
  readonly attentionHasPaymentInitCheck = computed(() => {
    const error = this.attentionError();
    return error.startsWith('payment_init_stale')
      || error.startsWith('payment_init_conflict')
      || error.startsWith('payment_init_exception');
  });
  readonly attentionRequiresManualCheck = computed(() => {
    return this.attentionHasLatePayment()
      || this.attentionHasPaymentInitCheck()
      || this.attentionHasFinalCancelFailure();
  });
  readonly invoiceProblemRaw = computed(() => (this.currentInvoice()?.lastError ?? '').trim());
  readonly invoicePaymentNotificationError = computed(() => (this.currentInvoice()?.paymentSuccessNotificationError ?? '').trim());
  readonly canResolveTechnicalTail = computed(() => {
    const invoice = this.currentInvoice();
    const error = this.invoiceProblemRaw().toLowerCase();
    return Boolean(
      invoice
        && invoice.status === 'DISABLED'
        && invoice.paidOrders >= invoice.totalOrders
        && (error.startsWith('disabled:') || error.startsWith('empty:') || error.startsWith('merged_into:'))
    );
  });
  readonly canResolvePaymentNotification = computed(() => Boolean(this.currentInvoice() && this.invoicePaymentNotificationError()));
  readonly invoiceProblemTitle = computed(() => {
    if (this.invoicePaymentNotificationError()) {
      return 'Ошибка уведомления об оплате';
    }
    if (this.invoiceProblemRaw()) {
      return 'Проблема общего счета';
    }
    return 'Проверьте общий счет';
  });
  readonly invoiceProblem = computed(() => {
    const invoice = this.currentInvoice();
    if (!invoice) {
      return '';
    }
    const error = this.invoiceProblemRaw();
    if (error) {
      return this.humanCommonInvoiceError(error);
    }
    const notificationError = this.invoicePaymentNotificationError();
    if (notificationError) {
      return this.humanPaymentNotificationError(notificationError);
    }
    switch (invoice.status) {
      case 'NEEDS_ATTENTION':
        return 'Счет требует ручного разбора.';
      case 'UNPAID':
        return 'Счет переведен в неоплаченные. Проверьте, что заказы обработаны корректно.';
      case 'BAN':
        return 'Счет в бане. Нужна проверка дальнейших действий по заказам.';
      case 'DISABLED':
        return 'Общий счет отключен. Проверьте, почему неоплаченные заказы остались в отключенной связке.';
      case 'READY':
        return 'Счет готов к отправке. Проверьте, не завис ли он без выставления клиенту.';
      case 'INVOICED':
      case 'REMINDER':
      case 'PARTIALLY_PAID':
        return 'Счет ожидает оплаты. Проверьте, не завис ли платеж или напоминание.';
      default:
        return '';
    }
  });
  readonly invoiceProblemSteps = computed(() => this.commonInvoiceProblemSteps());
  readonly invoiceProblemActionLabel = computed(() => {
    const notificationError = this.invoicePaymentNotificationError().toLowerCase();
    if (notificationError) {
      return 'После ручной отправки или исправления нажмите "Уведомление обработано".';
    }
    if (this.invoiceNeedsAttention()) {
      return 'После проверки используйте зеленое действие ниже или повторите обработку.';
    }
    if (this.canResolveTechnicalTail()) {
      return 'После сверки отключенной связи можно закрыть технический хвост.';
    }
    return '';
  });
  readonly invoiceProblemChatUrl = computed(() => {
    return this.invoiceOrderCards()
      .map((order) => (order.companyUrlChat ?? '').trim())
      .find((url) => Boolean(url)) ?? '';
  });
  readonly paymentNotificationCopyText = computed(() => this.buildPaymentNotificationText());
  readonly attentionRetryEnabled = computed(() => this.invoiceNeedsAttention() && !this.attentionRequiresManualCheck());
  readonly attentionResolveEnabled = computed(() => {
    return this.invoiceNeedsAttention()
      && !this.attentionRequiresManualCheck();
  });
  readonly readyForSending = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && invoice.totalOrders > 0
        && invoice.readyOrders >= invoice.totalOrders
        && ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status)
    );
  });
  readonly canMarkPaid = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && (
          ['READY', 'INVOICED', 'REMINDER', 'PARTIALLY_PAID', 'UNPAID'].includes(invoice.status)
          || (invoice.status === 'COLLECTING' && invoice.totalOrders > 0 && invoice.readyOrders >= invoice.totalOrders)
        )
    );
  });
  readonly canMarkUnpaid = computed(() => {
    const invoice = this.currentInvoice();
    return Boolean(
      invoice
        && invoice.totalOrders > 0
        && invoice.paidOrders < invoice.totalOrders
        && ['INVOICED', 'REMINDER', 'PARTIALLY_PAID'].includes(invoice.status)
    );
  });
  readonly canMarkBan = computed(() => this.currentInvoice()?.status === 'UNPAID');
  readonly metrics = computed(() => {
    const accounts = this.accounts();
    const invoices = accounts.map((account) => account.currentInvoice).filter(Boolean) as CommonInvoiceSummaryResponse[];
    return [
      { label: 'Плательщики', value: accounts.length, icon: 'account_tree' },
      { label: 'Включены', value: accounts.filter((account) => account.enabled).length, icon: 'toggle_on' },
      { label: 'Ожидают счет', value: invoices.filter((invoice) => invoice.status === 'READY' || invoice.status === 'COLLECTING').length, icon: 'pending_actions' },
      { label: 'К оплате', value: this.formatKopecks(invoices.reduce((sum, invoice) => sum + invoice.remainingKopecks, 0)), icon: 'payments' }
    ];
  });
  readonly pageTitle = computed(() => `Заказы - ${this.selectedAccountTitle()}`);

  constructor() {
    this.routeSubscription = this.route.queryParamMap.subscribe(() => this.load());
  }

  ngOnDestroy(): void {
    this.routeSubscription.unsubscribe();
    if (this.companySearchTimer != null) {
      window.clearTimeout(this.companySearchTimer);
    }
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');

    const requestedInvoiceId = this.requestedInvoiceId();
    if (requestedInvoiceId && this.currentInvoice()?.id !== requestedInvoiceId) {
      this.invoiceDetails.set(null);
      this.selectedAccountId.set(null);
    }
    if (this.managerInvoiceDetailMode) {
      this.accounts.set([]);
      this.selectedAccountId.set(null);
      if (!requestedInvoiceId) {
        this.invoiceDetails.set(null);
        this.error.set('Откройте общий счет из карточки заказа.');
        this.loading.set(false);
        return;
      }
      this.loadInvoiceByRequestedId(requestedInvoiceId);
      this.loading.set(false);
      return;
    }

    this.commonBillingApi.accounts().subscribe({
      next: (accounts) => {
        this.accounts.set(accounts ?? []);
        const requestedAccount = requestedInvoiceId
          ? this.accounts().find((account) => account.currentInvoice?.id === requestedInvoiceId)
          : null;
        const selectedStillExists = this.accounts().some((account) => account.id === this.selectedAccountId());
        if (requestedAccount) {
          this.selectedAccountId.set(requestedAccount.id);
        } else if (requestedInvoiceId) {
          this.loadInvoiceByRequestedId(requestedInvoiceId);
        } else if (!selectedStillExists) {
          this.selectedAccountId.set(this.accounts()[0]?.id ?? null);
        }
        this.applySelectedDraft();
        if (!requestedInvoiceId || requestedAccount) {
          this.loadSelectedInvoice();
        }
        this.loading.set(false);
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось загрузить общие счета');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Общие счета не загрузились', message);
      }
    });
  }

  selectAccount(account: CommonBillingAccountResponse): void {
    if (this.selectedAccountId() === account.id) {
      return;
    }
    this.selectedAccountId.set(account.id);
    this.invoiceDetails.set(null);
    this.applySelectedDraft();
    this.loadSelectedInvoice();
  }

  createAccount(): void {
    const draft = this.draft();
    if (!draft.name.trim() || this.mutating()) {
      return;
    }

    const existingAccount = this.accounts().find((account) =>
      account.enabled && this.normalizedName(account.name) === this.normalizedName(draft.name)
    );
    if (existingAccount) {
      this.selectedAccountId.set(existingAccount.id);
      this.applySelectedDraft();
      this.loadSelectedInvoice();
      this.toastService.success('Открыта существующая связь с таким названием');
      return;
    }

    this.mutating.set('create');
    this.commonBillingApi.createAccount({
      name: draft.name.trim(),
      enabled: draft.enabled,
      autoRepeatOrders: draft.autoRepeatOrders,
      managerId: this.optionalNumber(draft.managerId),
      invoiceCompanyId: this.optionalNumber(draft.invoiceCompanyId),
      companyIds: this.draftCompanies().map((company) => company.id)
    }).subscribe({
      next: (account) => {
        this.accounts.update((accounts) => [account, ...accounts.filter((item) => item.id !== account.id)]);
        this.selectedAccountId.set(account.id);
        this.applySelectedDraft();
        this.loadSelectedInvoice();
        this.mutating.set('');
        this.toastService.success('Общий плательщик создан');
      },
      error: (err) => this.failMutation(err, 'Не удалось создать общего плательщика')
    });
  }

  saveAccount(): void {
    const account = this.selectedAccount();
    const draft = this.draft();
    if (!account || !draft.name.trim() || this.mutating()) {
      return;
    }

    this.mutating.set(`save-${account.id}`);
    this.commonBillingApi.updateAccount(account.id, {
      name: draft.name.trim(),
      enabled: draft.enabled,
      autoRepeatOrders: draft.autoRepeatOrders,
      managerId: this.optionalNumber(draft.managerId),
      invoiceCompanyId: this.optionalNumber(draft.invoiceCompanyId),
      companyIds: this.enabledCompanyIds(account)
    }).subscribe({
      next: (updated) => {
        this.replaceAccount(updated);
        this.applySelectedDraft();
        this.mutating.set('');
        this.toastService.success('Настройки сохранены');
      },
      error: (err) => this.failMutation(err, 'Не удалось сохранить общего плательщика')
    });
  }

  onCompanySearchChange(value: string): void {
    this.companySearch.set(value);
    this.companySearchError.set('');
    if (this.companySearchTimer != null) {
      window.clearTimeout(this.companySearchTimer);
    }

    const query = value.trim();
    if (query.length < 2) {
      this.companySearchResults.set([]);
      this.companySearchLoading.set(false);
      return;
    }

    this.companySearchLoading.set(true);
    const run = ++this.companySearchRun;
    this.companySearchTimer = window.setTimeout(() => {
      this.managerApi.getBoard({
        section: 'companies',
        status: 'Все',
        keyword: query,
        pageNumber: 0,
        pageSize: 8,
        sortDirection: 'desc'
      }).subscribe({
        next: (board) => {
          if (run !== this.companySearchRun) {
            return;
          }
          this.companySearchResults.set(board.companies.content ?? []);
          this.companySearchLoading.set(false);
        },
        error: (err) => {
          if (run !== this.companySearchRun) {
            return;
          }
          this.companySearchResults.set([]);
          this.companySearchError.set(apiErrorDetail(err, 'Поиск компаний не сработал'));
          this.companySearchLoading.set(false);
        }
      });
    }, 260);
  }

  selectCompany(company: CompanyCardItem): void {
    if (this.companyAlreadySelected(company.id) || this.mutating()) {
      return;
    }
    const account = this.selectedAccount();
    if (account) {
      this.addCompany(company);
      return;
    }

    this.draftCompanies.update((companies) => [
      ...companies,
      {
        id: company.id,
        title: company.title,
        city: company.city,
        status: company.status
      }
    ]);
    this.clearCompanySearch();
  }

  removeDraftCompany(companyId: number): void {
    this.draftCompanies.update((companies) => companies.filter((company) => company.id !== companyId));
  }

  addCompany(company: CompanyCardItem): void {
    const account = this.selectedAccount();
    if (!account || !company?.id || this.mutating() || this.companyAlreadySelected(company.id)) {
      return;
    }

    this.mutating.set(`add-company-${company.id}`);
    this.commonBillingApi.addCompany(account.id, company.id).subscribe({
      next: (updated) => {
        this.replaceAccount(updated);
        this.clearCompanySearch();
        this.applySelectedDraft();
        this.mutating.set('');
        this.toastService.success('Компания добавлена в общий счет');
      },
      error: (err) => this.failMutation(err, 'Не удалось добавить компанию')
    });
  }

  removeCompany(companyId: number): void {
    const account = this.selectedAccount();
    if (!account || this.mutating()) {
      return;
    }

    const confirmed = window.confirm('Исключить компанию из будущих общих счетов?');
    if (!confirmed) {
      return;
    }
    const detachCurrent = window.confirm(
      'Отключить также неоплаченные заказы этой компании из текущего общего счета? Отмена оставит текущую пачку как есть.'
    );

    this.mutating.set(`remove-company-${companyId}`);
    this.commonBillingApi.removeCompany(account.id, companyId, detachCurrent).subscribe({
      next: (updated) => {
        this.replaceAccount(updated);
        this.applySelectedDraft();
        this.mutating.set('');
        this.toastService.success(detachCurrent ? 'Компания исключена, текущие неоплаченные позиции отключены' : 'Компания исключена из будущих счетов');
      },
      error: (err) => this.failMutation(err, 'Не удалось исключить компанию')
    });
  }

  sendInvoice(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating()) {
      return;
    }
    this.invoiceAction(invoice.id, 'send-invoice', () => this.commonBillingApi.sendInvoice(invoice.id), 'Общий счет отправлен');
  }

  markInvoicePaid(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canMarkPaid()) {
      return;
    }
    const confirmed = window.confirm('Отметить весь общий счет оплаченным? Все заказы внутри перейдут через штатную логику оплаты.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(invoice.id, 'mark-paid', () => this.commonBillingApi.markPaid(invoice.id), 'Общий счет закрыт оплатой');
  }

  markInvoiceUnpaid(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canMarkUnpaid()) {
      return;
    }
    const confirmed = window.confirm('Перевести неоплаченные заказы общего счета в "Не оплачено"? Это запустит штатную работу с плохими задачами.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(invoice.id, 'mark-unpaid', () => this.commonBillingApi.markUnpaid(invoice.id), 'Неоплаченные заказы обработаны');
  }

  markInvoiceBan(): void {
    const invoice = this.currentInvoice();
    if (!invoice || this.mutating() || !this.canMarkBan()) {
      return;
    }
    const confirmed = window.confirm('Перевести неоплаченные заказы общего счета в Бан?');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(invoice.id, 'mark-ban', () => this.commonBillingApi.markBan(invoice.id), 'Общий счет переведен в Бан');
  }

  retryAttention(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionRetryEnabled() || this.mutating()) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'retry-attention',
      () => this.commonBillingApi.retryAttention(invoice.id),
      'Повторная обработка запущена'
    );
  }

  resolveAttention(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionResolveEnabled() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Закрыть ручную проверку общего счета? Используйте это только после сверки оплаты и заказов.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'resolve-attention',
      () => this.commonBillingApi.resolveAttention(invoice.id),
      'Ручная проверка закрыта'
    );
  }

  resolveTechnicalTail(): void {
    const invoice = this.currentInvoice();
    if (!invoice || !this.canResolveTechnicalTail() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Закрыть технический хвост общего счета? Используйте это только если связь/перенос уже проверены.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'resolve-technical-tail',
      () => this.commonBillingApi.resolveTechnicalTail(invoice.id),
      'Технический хвост закрыт'
    );
  }

  resolvePaymentSuccessNotification(): void {
    const invoice = this.currentInvoice();
    if (!invoice || !this.canResolvePaymentNotification() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Закрыть ошибку уведомления об оплате? Используйте это только после ручной отправки уведомления клиенту или исправления причины.');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'resolve-payment-notification',
      () => this.commonBillingApi.resolvePaymentSuccessNotification(invoice.id),
      'Ошибка уведомления закрыта'
    );
  }

  applyLatePayment(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionHasLatePayment() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Распределить поздний T-Bank платеж по неоплаченным заказам общего счета?');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'apply-late-payment',
      () => this.commonBillingApi.applyLatePayment(invoice.id),
      'Поздний платеж распределен'
    );
  }

  confirmFinalPaymentCancelCheck(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionHasFinalCancelFailure() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Подтвердить, что старая T-Bank ссылка проверена вручную и больше не требует действий?');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'confirm-final-cancel-check',
      () => this.commonBillingApi.confirmFinalPaymentCancelCheck(invoice.id),
      'Проверка отмены закрыта'
    );
  }

  confirmPaymentInitCheck(): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status !== 'NEEDS_ATTENTION' || !this.attentionHasPaymentInitCheck() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm('Подтвердить, что создание T-Bank ссылки проверено вручную и больше не требует действий?');
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'confirm-payment-init-check',
      () => this.commonBillingApi.confirmPaymentInitCheck(invoice.id),
      'Проверка платежной ссылки закрыта'
    );
  }

  markOrderPaid(order: CommonInvoiceOrderResponse): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status === 'NEEDS_ATTENTION' || order.paid || this.mutating()) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      `mark-order-${order.orderId}`,
      () => this.commonBillingApi.markOrderPaid(invoice.id, order.orderId),
      `Заказ ${order.orderId} отмечен внутри счета`
    );
  }

  approveReviewOrders(): void {
    const invoice = this.currentInvoice();
    const count = this.reviewApprovalCount();
    if (!invoice || !this.canApproveReviewOrders() || this.mutating()) {
      return;
    }
    const confirmed = window.confirm(`Одобрить ${count} заказ(ов) в статусе "В проверку" или "На проверке"? Они перейдут в публикацию.`);
    if (!confirmed) {
      return;
    }
    this.invoiceAction(
      invoice.id,
      'approve-review-orders',
      () => this.commonBillingApi.approveReviewOrders(invoice.id),
      `Одобрено заказов: ${count}`
    );
  }

  detachOrder(order: CommonInvoiceOrderResponse): void {
    const invoice = this.currentInvoice();
    if (!invoice || invoice.status === 'NEEDS_ATTENTION' || !order.detachable || this.mutating()) {
      return;
    }

    const target = order.paid
      ? 'Заказ отмечен оплаченным внутри общего счета, поэтому будет закрыт отдельно как оплаченный.'
      : `Заказ вернется в статус "${order.originalOrderStatus || 'Опубликовано'}".`;
    const confirmed = window.confirm(`Отключить заказ №${order.orderId} от общего счета? ${target}`);
    if (!confirmed) {
      return;
    }

    this.invoiceAction(
      invoice.id,
      `detach-order-${order.orderId}`,
      () => this.commonBillingApi.detachOrder(invoice.id, order.orderId),
      `Заказ ${order.orderId} отключен от общего счета`
    );
  }

  async copyPublicUrl(): Promise<void> {
    const url = this.currentInvoice()?.publicUrl?.trim();
    if (!url) {
      return;
    }
    if (await copyTextToClipboard(url)) {
      this.copied.set('public-url');
      window.setTimeout(() => {
        if (this.copied() === 'public-url') {
          this.copied.set('');
        }
      }, 1600);
      this.toastService.success('Ссылка скопирована');
    } else {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    }
  }

  async copyPaymentNotificationText(): Promise<void> {
    await this.copyText(
      this.paymentNotificationCopyText(),
      'payment-notification-text',
      'Текст уведомления скопирован'
    );
  }

  statusLabel(status?: string | null): string {
    switch (status) {
      case 'COLLECTING':
        return 'Собирается';
      case 'READY':
        return 'Готов к счету';
      case 'INVOICED':
        return 'Выставлен';
      case 'REMINDER':
        return 'Напоминание';
      case 'PARTIALLY_PAID':
        return 'Частично оплачен';
      case 'NEEDS_ATTENTION':
        return 'Требует внимания';
      case 'PAID':
        return 'Оплачен';
      case 'UNPAID':
        return 'Не оплачен';
      case 'BAN':
        return 'Бан';
      case 'DISABLED':
        return 'Отключен';
      default:
        return 'Нет счета';
    }
  }

  statusClass(status?: string | null): string {
    if (status === 'PAID') {
      return 'status-pill paid';
    }
    if (status === 'READY' || status === 'INVOICED' || status === 'REMINDER') {
      return 'status-pill active';
    }
    if (status === 'PARTIALLY_PAID') {
      return 'status-pill partial';
    }
    if (status === 'UNPAID' || status === 'BAN' || status === 'DISABLED' || status === 'NEEDS_ATTENTION') {
      return 'status-pill danger';
    }
    return 'status-pill neutral';
  }

  formatKopecks(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format((value ?? 0) / 100)} ₽`;
  }

  selectedAccountTitle(): string {
    const account = this.selectedAccount();
    const invoice = this.currentInvoice();
    const title = (invoice?.title || invoice?.accountName || account?.invoiceCompanyTitle || account?.name || 'Общий счет').trim();
    return title.replace(/\s+-\s+общий счет.*$/i, '').trim() || title;
  }

  selectedAccountName(): string {
    return this.currentInvoice()?.accountName || this.selectedAccount()?.name || 'Общий счет';
  }

  accountsCountLabel(): string {
    const count = this.accounts().length;
    return `${count} ${this.pluralRu(count, 'связь', 'связи', 'связей')}`;
  }

  accountCompaniesLabel(account: CommonBillingAccountResponse): string {
    const count = account.companies?.length ?? 0;
    return `${count} ${this.pluralRu(count, 'компания', 'компании', 'компаний')}`;
  }

  trackAccount(_index: number, account: CommonBillingAccountResponse): number {
    return account.id;
  }

  trackCompany(_index: number, company: { companyId: number }): number {
    return company.companyId;
  }

  trackDraftCompany(_index: number, company: DraftCompany): number {
    return company.id;
  }

  trackCompanySearch(_index: number, company: CompanyCardItem): number {
    return company.id;
  }

  companyAlreadySelected(companyId: number): boolean {
    const account = this.selectedAccount();
    if (account) {
      return (account.companies ?? []).some((company) => company.companyId === companyId && company.enabled);
    }
    return this.draftCompanies().some((company) => company.id === companyId);
  }

  trackOrder(_index: number, order: CommonInvoiceOrderResponse): number {
    return order.orderId;
  }

  trackOrderCard(_index: number, order: OrderCardItem): number {
    return order.id;
  }

  invoiceOrderInfo(order: OrderCardItem): CommonInvoiceOrderResponse | null {
    return this.invoiceOrders().find((item) => item.orderId === order.id) ?? null;
  }

  orderCardActions(order: OrderCardItem): StatusAction[] {
    const disabledStatuses = new Set(['Выставлен счет', 'Напоминание', 'Не оплачено', 'Оплачено']);
    return managerOrderActions(order, false)
      .filter((action) => !disabledStatuses.has(action.status));
  }

  async copyPhone(phone?: string): Promise<void> {
    await this.copyText((phone ?? '').replace(/\D/g, ''), 'телефон', 'Телефон скопирован');
  }

  async copyOrderText(order: OrderCardItem, kind: 'review' | 'payment'): Promise<void> {
    if (kind === 'payment') {
      const url = this.currentInvoice()?.publicUrl?.trim();
      await this.copyText(
        url,
        `payment-${order.id}`,
        'Ссылка общего счета скопирована'
      );
      return;
    }
    await this.copyText(
      managerOrderReviewCopyText(order, []),
      `review-${order.id}`,
      'Текст проверки скопирован'
    );
  }

  openOrderDetails(order: OrderCardItem): void {
    void this.router.navigate(['/orders', order.companyId, order.id]);
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

  cancelOrderPayment(): void {
    this.orderFacade.cancelOrderPayment();
  }

  updateOrderStatus(order: OrderCardItem, action: StatusAction): void {
    if (['Выставлен счет', 'Напоминание', 'Не оплачено', 'Оплачено'].includes(action.status)) {
      this.toastService.error('Одиночное финансовое действие отключено', 'Этот заказ входит в общий счет');
      return;
    }
    const key = `order-${order.id}-${action.status}`;
    this.mutating.set(key);
    this.managerApi.updateOrderStatus(order.id, action.status).subscribe({
      next: () => {
        this.mutating.set('');
        this.toastService.success('Статус заказа изменен', `${order.companyTitle}: ${action.status}`);
        this.loadSelectedInvoice();
      },
      error: (err) => this.failMutation(err, managerErrorMessage(err, 'Не удалось изменить статус заказа'))
    });
  }

  saveOrderCompanyNote(order: OrderCardItem, value: string): void {
    this.managerApi.updateOrderCompanyNote(order.id, value).subscribe({
      next: () => {
        this.toastService.success('Заметка компании сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.loadSelectedInvoice();
      },
      error: (err) => this.toastService.error('Заметка не сохранена', apiErrorDetail(err, 'Не удалось сохранить заметку компании'))
    });
  }

  saveOrderCardNote(order: OrderCardItem, value: string): void {
    this.managerApi.updateOrderNote(order.id, value).subscribe({
      next: () => {
        this.toastService.success('Заметка заказа сохранена', order.companyTitle || `Заказ #${order.id}`);
        this.loadSelectedInvoice();
      },
      error: (err) => this.toastService.error('Заметка не сохранена', apiErrorDetail(err, 'Не удалось сохранить заметку заказа'))
    });
  }

  trackMetric(_index: number, metric: { label: string }): string {
    return metric.label;
  }

  private async copyText(value: string | undefined | null, copiedKey: string, successTitle: string): Promise<void> {
    const text = (value ?? '').trim();
    if (!text) {
      this.toastService.error('Не скопировано', 'Нет текста для копирования');
      return;
    }
    if (await copyTextToClipboard(text)) {
      this.copied.set(copiedKey);
      window.setTimeout(() => {
        if (this.copied() === copiedKey) {
          this.copied.set('');
        }
      }, 1600);
      this.toastService.success(successTitle);
    } else {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    }
  }

  private loadSelectedInvoice(): void {
    const invoiceId = this.selectedAccount()?.currentInvoice?.id;
    if (!invoiceId) {
      this.invoiceDetails.set(null);
      return;
    }
    this.invoiceLoading.set(true);
    this.commonBillingApi.invoice(invoiceId).subscribe({
      next: (details) => {
        this.invoiceDetails.set(details);
        this.invoiceLoading.set(false);
      },
      error: (err) => {
        this.invoiceLoading.set(false);
        const message = apiErrorDetail(err);
        this.error.set(message);
        this.toastService.error('Счет не загрузился', message);
      }
    });
  }

  private requestedInvoiceId(): number | null {
    return Number(this.route.snapshot.queryParamMap.get('invoiceId') ?? 0) || null;
  }

  private loadInvoiceByRequestedId(invoiceId: number): void {
    this.invoiceLoading.set(true);
    this.commonBillingApi.invoice(invoiceId).subscribe({
      next: (details) => {
        this.invoiceDetails.set(details);
        this.selectedAccountId.set(details.summary.accountId);
        this.accounts.update((accounts) => accounts.map((account) =>
          account.id === details.summary.accountId
            ? { ...account, currentInvoice: details.summary }
            : account
        ));
        this.applySelectedDraft();
        this.invoiceLoading.set(false);
      },
      error: (err) => {
        this.invoiceLoading.set(false);
        this.toastService.error('Счет не загрузился', apiErrorDetail(err));
      }
    });
  }

  private humanCommonInvoiceError(error: string): string {
    const normalized = error.toLowerCase();
    if (normalized.startsWith('disabled:')) {
      return 'Связь выключена, а неоплаченные заказы были отключены от общего счета. Проверьте, нужно ли включить связь заново или закрыть этот хвост в контроле.';
    }
    if (normalized.startsWith('empty:')) {
      return 'В общем счете не осталось активных неоплаченных заказов. Проверьте, не нужно ли закрыть пустой счет или убрать его из контроля.';
    }
    if (normalized.startsWith('merged_into:')) {
      return 'Позиции перенесены в другой общий счет. Откройте актуальную связь и закройте этот хвост после проверки.';
    }
    if (normalized.startsWith('whatsapp_group_missing')) {
      return 'Не найден чат WhatsApp для отправки общего счета. Проверьте связь компании с чатом или отправьте счет вручную.';
    }
    if (normalized.includes('t-bank') || normalized.includes('tbank') || normalized.includes('payment')) {
      return 'Есть ошибка платежа или T-Bank. Проверьте состояние оплаты в правой панели и повторите действие только после сверки.';
    }
    return `Ошибка общего счета: ${error}`;
  }

  private humanPaymentNotificationError(error: string): string {
    const normalized = error.toLowerCase();
    if (normalized.startsWith('whatsapp_group_missing')) {
      return 'Оплата прошла, но автоматическое сообщение в WhatsApp не ушло: у группы не заполнен groupId.';
    }
    if (normalized.startsWith('immediate_messages_disabled')) {
      return 'Оплата прошла, но автоматическое уведомление выключено в настройках сообщений.';
    }
    if (normalized.startsWith('notification_result_empty') || normalized.startsWith('notification_not_sent')) {
      return 'Оплата прошла, но система не получила подтверждение отправки уведомления.';
    }
    return `Оплата прошла, но уведомление не отправлено: ${error}`;
  }

  private commonInvoiceProblemSteps(): string[] {
    const notificationError = this.invoicePaymentNotificationError().toLowerCase();
    const commonError = this.invoiceProblemRaw().toLowerCase();
    if (notificationError.startsWith('whatsapp_group_missing')) {
      return [
        'Откройте чат компании и убедитесь, что это нужная WhatsApp-группа.',
        'Заполните groupId у этой группы в настройках связи, чтобы следующие уведомления уходили автоматически.',
        'Если клиент уже ждет сообщение, скопируйте текст уведомления и отправьте его вручную.',
        'Когда сообщение отправлено или groupId исправлен, нажмите "Уведомление обработано".'
      ];
    }
    if (notificationError.startsWith('immediate_messages_disabled')) {
      return [
        'Проверьте, почему отключены моментальные клиентские сообщения.',
        'Включите отправку или отправьте клиенту уведомление вручную.',
        'После ручного сообщения нажмите "Уведомление обработано".'
      ];
    }
    if (notificationError.startsWith('notification_result_empty') || notificationError.startsWith('notification_not_sent')) {
      return [
        'Откройте чат и проверьте, появилось ли сообщение об оплате.',
        'Если сообщения нет, отправьте его вручную через текст уведомления.',
        'После проверки нажмите "Уведомление обработано".'
      ];
    }
    if (notificationError) {
      return [
        'Проверьте чат или настройки отправки уведомлений.',
        'Отправьте клиенту сообщение вручную, если автоматическая отправка не сработала.',
        'После исправления нажмите "Уведомление обработано".'
      ];
    }
    if (commonError.startsWith('whatsapp_group_missing')) {
      return [
        'Откройте чат компании и проверьте WhatsApp-группу.',
        'Заполните groupId у связи или отправьте счет вручную.',
        'После исправления повторите отправку счета.'
      ];
    }
    if (commonError.startsWith('disabled:') || commonError.startsWith('empty:') || commonError.startsWith('merged_into:')) {
      return [
        'Проверьте, что в счете действительно нет активных неоплаченных заказов.',
        'Если заказы перенесены или связь выключена намеренно, закройте технический хвост.'
      ];
    }
    return [];
  }

  private buildPaymentNotificationText(): string {
    const invoice = this.currentInvoice();
    if (!invoice) {
      return '';
    }
    const title = invoice.title || invoice.accountName || 'общему счету';
    const parts = [
      'Здравствуйте!',
      '',
      `Оплата по общему счету "${title}" прошла успешно.`,
      `Оплачено: ${this.formatKopecks(invoice.paidKopecks)}.`,
      `Готово заказов: ${invoice.paidOrders}/${invoice.totalOrders}.`
    ];
    if (invoice.publicUrl?.trim()) {
      parts.push('', `Ссылка на счет: ${invoice.publicUrl.trim()}`);
    }
    return parts.join('\n');
  }

  private invoiceAction(
    invoiceId: number,
    key: string,
    action: () => ReturnType<CommonBillingApi['invoice']>,
    successTitle: string
  ): void {
    this.mutating.set(key);
    action().subscribe({
      next: (details) => {
        this.invoiceDetails.set(details);
        this.accounts.update((accounts) => accounts.map((account) => {
          if (account.currentInvoice?.id !== invoiceId) {
            return account;
          }
          return { ...account, currentInvoice: details.summary };
        }));
        this.mutating.set('');
        this.toastService.success(successTitle);
      },
      error: (err) => this.failMutation(err, 'Действие со счетом не выполнено')
    });
  }

  private replaceAccount(updated: CommonBillingAccountResponse): void {
    this.accounts.update((accounts) => accounts.map((account) => account.id === updated.id ? updated : account));
  }

  private applySelectedDraft(): void {
    const account = this.selectedAccount();
    if (!account) {
      return;
    }
    this.draft.set({
      name: account.name ?? '',
      enabled: account.enabled,
      autoRepeatOrders: account.autoRepeatOrders,
      managerId: account.managerId == null ? '' : String(account.managerId),
      invoiceCompanyId: account.invoiceCompanyId == null ? '' : String(account.invoiceCompanyId)
    });
    this.draftCompanies.set([]);
    this.clearCompanySearch();
  }

  private enabledCompanyIds(account: CommonBillingAccountResponse): number[] {
    return (account.companies ?? [])
      .filter((company) => company.enabled)
      .map((company) => company.companyId);
  }

  private pluralRu(value: number, one: string, few: string, many: string): string {
    const mod10 = Math.abs(value) % 10;
    const mod100 = Math.abs(value) % 100;
    if (mod10 === 1 && mod100 !== 11) {
      return one;
    }
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
      return few;
    }
    return many;
  }

  private optionalNumber(value: string): number | null {
    const numeric = Number(value);
    return Number.isInteger(numeric) && numeric > 0 ? numeric : null;
  }

  private normalizedName(value: string | null | undefined): string {
    return (value ?? '').trim().replace(/\s+/g, ' ').toLocaleLowerCase('ru-RU');
  }

  private clearCompanySearch(): void {
    this.companySearch.set('');
    this.companySearchResults.set([]);
    this.companySearchError.set('');
    this.companySearchLoading.set(false);
    if (this.companySearchTimer != null) {
      window.clearTimeout(this.companySearchTimer);
      this.companySearchTimer = null;
    }
  }

  private failMutation(err: unknown, fallback: string): void {
    const message = apiErrorDetail(err, fallback);
    this.mutating.set('');
    this.toastService.error(fallback, message);
  }
}
