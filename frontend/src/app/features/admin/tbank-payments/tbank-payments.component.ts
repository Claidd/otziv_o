import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin, switchMap, tap } from 'rxjs';
import { PaymentsApi } from '../../../core/payments.api';
import type { OrderCardItem } from '../../../core/manager.api';
import type {
  AdminPaymentLinkResponse,
  AdminPaymentLinkSummaryResponse,
  AdminPaymentLinksPageResponse,
  ManualPaymentRecipientMonthlySummaryItem,
  ManualPaymentRecipientMonthlySummaryResponse,
  ManualPaymentTaskAccountingTargetOption,
  ManualPaymentTaskResponse,
  ManualPaymentTaskStatus,
  ManualPaymentType,
  ManagerPaymentProfileResponse,
  PaymentLinkListSource,
  PaymentInstructionSource,
  PaymentPolicy,
  PaymentProfilePolicyRequest,
  PaymentProfileResponse,
  TbankPaymentPageMode,
  TbankPaymentStatus,
  TbankRuntimeMode,
  TbankRuntimeSettings,
  UpdateTbankRuntimeSettingsRequest
} from '../../../core/payments.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorDetail } from '../../../shared/api-error-message';
import { copyTextToClipboard } from '../../../shared/clipboard-copy';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { MobileBottomPagerComponent } from '../../../shared/mobile/mobile-bottom-pager.component';
import {
  paymentTargetForUpdate,
  safePaymentNavigationTarget,
  type PaymentNavigationPurpose
} from '../../../shared/payment-navigation';
import {
  bankProfileOptionLabel,
  bankProviderLabel,
  isBankPaymentRouteType
} from '../../../shared/bank-payment-presentation';
import { ToastService } from '../../../shared/toast.service';
import {
  ManagerManualCardPaymentModalComponent,
  type ManagerManualCardPaymentCompleted
} from '../../manager/manager-manual-card-payment-modal.component';
import {
  manualPaymentRecipientLabel,
  manualPaymentRouteErrorCode,
  manualPaymentRouteErrorMessage
} from '../../../shared/manual-payment-routing';
import { ManualPaymentTaskOperationKeyDraft } from '../../../shared/manual-payment-operation-key';
import {
  canCloseManualPaymentAsUnpaid,
  manualPaymentUnpaidCloseConfirmation,
  manualPaymentUnpaidCloseNotePrompt
} from './manual-payment-unpaid-close';
import {
  manualPaymentTaskRecommendedTarget,
  manualPaymentTaskSelectedTarget,
  manualPaymentTaskTargetEffect,
  manualPaymentTaskTargetForSnapshot,
  manualPaymentTaskTargetValid
} from '../../../shared/manual-payment-task-target';
import {
  manualPaymentAccountingDestinationLabel,
  manualPaymentAccountingRecipientLabel,
  manualPaymentAccountingSourceLabel
} from '../../../shared/manual-payment-recipient-summary';

type PaymentMetric = {
  label: string;
  value: string | number;
  icon: string;
  tone: 'blue' | 'green' | 'yellow' | 'red' | 'gray';
};

type PaymentStatusFilter = 'all' | 'active' | 'paid' | 'refunded' | 'failed' | 'created' | 'manual';

type ProfilePolicyDraft = {
  paymentPolicy: PaymentPolicy;
  manualPaymentType: ManualPaymentType;
  manualPhone: string;
  manualRecipientName: string;
  manualPaymentUrl: string;
  manualPaymentButtonLabel: string;
  manualComment: string;
  manualMonthlyLimitRubles: string;
};

type StatusFilterOption = {
  key: PaymentStatusFilter;
  label: string;
  icon: string;
};

@Component({
  selector: 'app-tbank-payments',
  imports: [AdminLayoutComponent, DatePipe, DecimalPipe, FormsModule, LoadErrorCardComponent, ManagerManualCardPaymentModalComponent, MobileBottomPagerComponent, RouterLink],
  templateUrl: './tbank-payments.component.html',
  styleUrl: './tbank-payments.component.scss'
})
export class TbankPaymentsComponent implements OnDestroy {
  private static readonly DEFAULT_MANUAL_MONTHLY_LIMIT_RUBLES = 191000;
  private static readonly DEFAULT_MANUAL_RECIPIENT_NAME = 'Сивохин И.И.';
  private static readonly DEFAULT_MANUAL_PAYMENT_URL = 'https://pay.alfabank.ru/sc/EWwpfrArNZotkqOR';
  private static readonly DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL = 'Оплатить через Альфа-Банк';

  private readonly paymentsApi = inject(PaymentsApi);
  private readonly toastService = inject(ToastService);

  readonly links = signal<AdminPaymentLinkResponse[]>([]);
  readonly status = signal<TbankPaymentStatus | null>(null);
  readonly profiles = signal<PaymentProfileResponse[]>([]);
  readonly managerProfiles = signal<ManagerPaymentProfileResponse[]>([]);
  readonly manualTasks = signal<ManualPaymentTaskResponse[]>([]);
  readonly recipientSummaryMonth = signal(TbankPaymentsComponent.currentMonthInput());
  readonly recipientMonthlySummary = signal<ManualPaymentRecipientMonthlySummaryResponse | null>(null);
  readonly recipientSummaryError = signal<string | null>(null);
  readonly journalManualCardPaymentOrder = signal<OrderCardItem | null>(null);
  private readonly adminTaskOperationKey = new ManualPaymentTaskOperationKeyDraft();
  readonly adminTaskManagerId = signal<number | null>(null);
  readonly adminTaskPaymentType = signal<ManualPaymentType>('MOBILE_BANK');
  readonly adminTaskPhone = signal('');
  readonly adminTaskRecipient = signal(TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly adminTaskBankName = signal('');
  readonly adminTaskPaymentUrl = signal(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL);
  readonly adminTaskPaymentButtonLabel = signal(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly adminTaskAmountRubles = signal('');
  readonly adminTaskComment = signal('');
  readonly adminTaskAccountingTargets = signal<ManualPaymentTaskAccountingTargetOption[]>([]);
  readonly adminTaskAccountingTargetKey = signal('');
  readonly adminTaskAccountingTargetAcknowledged = signal(false);
  readonly adminTaskAccountingTargetsLoading = signal(false);
  readonly adminTaskAccountingTargetError = signal<string | null>(null);
  readonly profileAssignments = signal<Record<number, number | null>>({});
  readonly profilePolicies = signal<Record<number, ProfilePolicyDraft>>({});
  readonly runtimeSettings = signal<TbankRuntimeSettings | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutatingId = signal<number | null>(null);
  readonly mutatingTaskId = signal<number | null>(null);
  readonly editingTaskId = signal<number | null>(null);
  readonly editTaskPaymentType = signal<ManualPaymentType>('MOBILE_BANK');
  readonly editTaskPhone = signal('');
  readonly editTaskRecipient = signal(TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly editTaskBankName = signal('');
  readonly editTaskPaymentUrl = signal(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL);
  readonly editTaskPaymentButtonLabel = signal(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly editTaskAmountRubles = signal('');
  readonly editTaskComment = signal('');
  readonly editTaskAccountingTargets = signal<ManualPaymentTaskAccountingTargetOption[]>([]);
  readonly editTaskAccountingTargetKey = signal('');
  readonly editTaskAccountingTargetAcknowledged = signal(false);
  readonly editTaskAccountingTargetsLoading = signal(false);
  readonly editTaskAccountingTargetError = signal<string | null>(null);
  private adminTaskAccountingPreviewEpoch = 0;
  private editTaskAccountingPreviewEpoch = 0;
  readonly savingManualTask = signal(false);
  readonly savingProfiles = signal(false);
  readonly savingProfilePolicies = signal(false);
  readonly savingRuntimeSettings = signal(false);
  readonly savingRoutingSettings = computed(() => this.savingProfiles() || this.savingProfilePolicies());
  readonly copied = signal<string | null>(null);
  readonly search = signal('');
  readonly statusFilter = signal<PaymentStatusFilter>('all');
  readonly dateFrom = signal('');
  readonly dateTo = signal('');
  readonly paymentSource = signal<PaymentLinkListSource>('LIVE');
  readonly paymentPage = signal(0);
  readonly paymentSize = signal(25);
  readonly paymentTotalElements = signal(0);
  readonly paymentTotalPages = signal(0);
  readonly paymentSummary = signal<AdminPaymentLinkSummaryResponse | null>(null);
  readonly archiving = signal(false);
  readonly loadingRecipientSummary = signal(false);
  private recipientSummaryLoadEpoch = 0;
  private searchReloadTimer: number | null = null;

  readonly statusOptions: StatusFilterOption[] = [
    { key: 'all', label: 'Все', icon: 'apps' },
    { key: 'active', label: 'Активные', icon: 'bolt' },
    { key: 'paid', label: 'Оплачены', icon: 'check_circle' },
    { key: 'refunded', label: 'Возвраты', icon: 'assignment_return' },
    { key: 'failed', label: 'Ошибки', icon: 'error' },
    { key: 'created', label: 'Созданы', icon: 'schedule' },
    { key: 'manual', label: 'Ручные', icon: 'phone_iphone' }
  ];

  readonly filteredLinks = computed(() => {
    return this.links();
  });

  readonly hasFilters = computed(() => {
    return Boolean(this.search().trim())
      || this.statusFilter() !== 'all'
      || Boolean(this.dateFrom())
      || Boolean(this.dateTo());
  });

  readonly manualLinks = computed(() => this.links().filter((link) => this.isManualPayment(link)));

  readonly manualPendingLinks = computed(() => this.manualLinks().filter((link) => {
    return link.status === 'WAITING_MANUAL_PAYMENT' || link.status === 'MANUAL_REPORTED';
  }));

  readonly manualPendingCount = computed(() => {
    return this.paymentSummary()?.manualPending ?? this.manualPendingLinks().length;
  });

  readonly receiptPendingCount = computed(() => this.paymentSummary()?.receiptPending ?? 0);
  readonly receiptOverdueCount = computed(() => this.paymentSummary()?.receiptOverdue ?? 0);

  readonly recipientSummaryItems = computed(() => this.recipientMonthlySummary()?.items ?? []);
  readonly selectedAdminTaskAccountingTarget = computed(() => manualPaymentTaskSelectedTarget(
    this.adminTaskAccountingTargets(), this.adminTaskAccountingTargetKey()
  ));
  readonly selectedEditTaskAccountingTarget = computed(() => manualPaymentTaskSelectedTarget(
    this.editTaskAccountingTargets(), this.editTaskAccountingTargetKey()
  ));
  readonly manualTaskTargetEffect = manualPaymentTaskTargetEffect;

  readonly paymentPageLabel = computed(() => {
    const totalPages = this.paymentTotalPages();
    if (!totalPages) {
      return 'Страница 0 из 0';
    }
    return `Страница ${this.paymentPage() + 1} из ${totalPages}`;
  });

  readonly canCreateManualTask = computed(() => {
    const hasTarget = this.adminTaskPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.adminTaskPhone().trim()) && Boolean(this.adminTaskRecipient().trim())
      : Boolean(this.adminTaskPaymentUrl().trim()) && Boolean(this.adminTaskRecipient().trim());
    return !this.savingManualTask()
      && this.adminTaskManagerId() != null
      && hasTarget
      && this.adminTaskTargetKopecks() > 0
      && !this.adminTaskAccountingTargetsLoading()
      && manualPaymentTaskTargetValid(
        this.selectedAdminTaskAccountingTarget(), this.adminTaskAccountingTargetAcknowledged()
      );
  });

  readonly tbankClientPaymentEnabled = computed(() => {
    const settings = this.runtimeSettings();
    return Boolean(settings?.clientTbankEnabled || isBankPaymentRouteType(settings?.paymentInstructionSource));
  });

  readonly defaultBankProfile = computed(() =>
    this.profiles().find((candidate) => candidate.defaultProfile)
      ?? this.profiles()[0]
      ?? null
  );

  readonly bankReadyForClientPayments = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings?.paymentLinksEnabled || !settings.managerUiEnabled) {
      return false;
    }
    const managers = this.managerProfiles();
    if (!managers.length) {
      return this.profiles().some((profile) => this.bankProfileReady(profile));
    }
    return managers.every((manager) => {
      const profile = this.selectedManagerProfile(manager)
        ?? this.defaultBankProfile()
        ?? null;
      return Boolean(profile && this.bankProfileReady(profile));
    });
  });

  readonly unreadyManagerBankProfiles = computed(() => this.managerProfiles()
    .filter((manager) => {
      const profile = this.selectedManagerProfile(manager)
        ?? this.defaultBankProfile()
        ?? null;
      return !profile || !this.bankProfileReady(profile);
    })
    .map((manager) => manager.managerTitle));

  readonly activeRuntimeMode = computed<TbankRuntimeMode>(() => {
    return this.runtimeSettings()?.runtimeMode ?? this.status()?.runtimeMode ?? (this.status()?.testMode ? 'TEST' : 'LIVE');
  });

  readonly clientPaymentSource = computed<PaymentInstructionSource>(() => {
    return this.runtimeSettings()?.paymentInstructionSource ?? 'MANAGER_TEXT';
  });

  readonly paymentPageMode = computed<TbankPaymentPageMode>(() => {
    return this.runtimeSettings()?.paymentPageMode ?? 'SBP_PRIMARY';
  });

  readonly bankPaymentBlockEnabled = computed(() => {
    const mode = this.paymentPageMode();
    return mode !== 'SBP_ONLY' && mode !== 'SBP_PAY_ONLY';
  });

  readonly paymentPageModeDescription = computed(() => {
    switch (this.paymentPageMode()) {
      case 'BANK_PRIMARY':
        return 'На странице оплаты сначала показываем форму банка, СБП остается запасным способом.';
      case 'SBP_PAY_ONLY':
        return 'На странице оплаты показываем СБП и быстрые Pay-кнопки, карточная кнопка скрыта.';
      case 'SBP_ONLY':
        return 'На странице оплаты доступна только кнопка СБП. Форма банка скрыта.';
      case 'BANK_ONLY':
        return 'На странице оплаты доступна только форма выбранного банка.';
      default:
        return 'На странице оплаты сначала показываем СБП, а форма банка остается запасным способом.';
    }
  });

  readonly fastBankMethodDescription = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings) {
      return 'Настройки быстрых методов загружаются.';
    }
    const methods = [
      settings.tpayEnabled ? 'T-Pay' : '',
      settings.sberpayEnabled ? 'SberPay' : '',
      settings.mirpayEnabled ? 'Mir Pay' : ''
    ].filter(Boolean);
    if (this.paymentPageMode() === 'SBP_PAY_ONLY') {
      if (!methods.length) {
        return 'Для T‑Bank в режиме «СБП + Pay» сейчас будет только СБП. Профиль Точки эти переключатели не использует.';
      }
      return `Для T‑Bank на /pay показываем СБП и ${methods.join(', ')}. Карточную кнопку не показываем.`;
    }
    if (!this.bankPaymentBlockEnabled()) {
      if (!methods.length) {
        return 'Форма T‑Bank скрыта режимом «Только СБП». Профиль Точки эти переключатели не использует.';
      }
      return `Сейчас выбран режим «Только СБП», поэтому способы T‑Bank (${methods.join(', ')}) не показываются на /pay.`;
    }
    if (!methods.length) {
      return 'В форме T‑Bank показываем только карту. T‑Pay, SberPay и Mir Pay можно включить после их активации в T‑Bank; профиль Точки эти флаги игнорирует.';
    }
    return `В форме T‑Bank показываем: ${methods.join(', ')}. Профиль Точки эти флаги игнорирует.`;
  });

  readonly launchStateTitle = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings) {
      return 'Настройки загружаются';
    }
    if (settings.runtimeMode === 'TEST') {
      return 'T‑Bank: тестовый контур';
    }
    if (!isBankPaymentRouteType(settings.paymentInstructionSource)) {
      return 'Боевой терминал, клиенты на Альфа';
    }
    if (!settings.applyConfirmedPayments) {
      return 'Банковские ссылки клиентам, заказы вручную';
    }
    return 'Банковские ссылки полностью включены';
  });

  readonly launchStateDescription = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings) {
      return 'Получаю состояние банковских профилей и настроек T‑Bank.';
    }
    if (settings.runtimeMode === 'TEST') {
      return 'T‑Bank работает на тестовом терминале. Клиентам остаются старые счета, заказы не переводятся в оплату.';
    }
    if (!isBankPaymentRouteType(settings.paymentInstructionSource)) {
      return 'Рабочие терминалы готовы для ручных тестов, но автоответчик продолжает отправлять старый текст/Альфа.';
    }
    if (!settings.applyConfirmedPayments) {
      return 'Клиенты получают банковские ссылки, но подтвержденные платежи только попадают в журнал.';
    }
    return 'Клиенты получают ссылку назначенного банка, webhook переводит заказ в оплату и запоминает e-mail плательщика.';
  });

  readonly launchStateClass = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings || settings.runtimeMode === 'TEST') {
      return 'launch-summary test';
    }
    if (isBankPaymentRouteType(settings.paymentInstructionSource) && settings.applyConfirmedPayments) {
      return 'launch-summary live';
    }
    return 'launch-summary staged';
  });

  readonly metrics = computed<PaymentMetric[]>(() => {
    const summary = this.paymentSummary();
    const links = this.links();
    const total = summary?.totalElements ?? links.length;
    const paid = summary?.paid ?? links.filter((link) => this.isPaid(link.status)).length;
    const refunded = summary?.refunded ?? links.filter((link) => this.isRefunded(link.status) || link.status === 'CANCELED').length;
    const rejected = summary?.rejected ?? links.filter((link) =>
      link.status === 'REJECTED' || link.status === 'FAILED' || link.status === 'NEEDS_RECONCILIATION'
    ).length;
    const manualPending = this.manualPendingCount();
    const confirmed = summary?.confirmed ?? links.filter((link) => link.status === 'CONFIRMED').length;
    const notificationsSent = summary?.notificationsSent ?? links.filter((link) => link.status === 'CONFIRMED' && Boolean(link.paymentSuccessNotifiedAt)).length;
    const notificationErrors = summary?.notificationErrors ?? links.filter((link) => link.status === 'CONFIRMED' && !link.paymentSuccessNotifiedAt && Boolean(link.paymentSuccessNotificationError)).length;
    const refundable = summary?.refundable ?? links.filter((link) => link.refundable).length;
    return [
      { label: 'Показано', value: `${links.length}/${total}`, icon: 'filter_list', tone: 'blue' },
      { label: 'Оплачено', value: paid, icon: 'check_circle', tone: 'green' },
      { label: 'Ручные ждут', value: manualPending, icon: 'phone_iphone', tone: manualPending ? 'yellow' : 'gray' },
      { label: 'Уведомления', value: confirmed ? `${notificationsSent}/${confirmed}` : 0, icon: notificationErrors ? 'sms_failed' : 'mark_chat_read', tone: notificationErrors ? 'red' : notificationsSent ? 'green' : 'gray' },
      { label: 'Можно вернуть', value: refundable, icon: 'undo', tone: 'yellow' },
      { label: 'Возвращено', value: refunded, icon: 'assignment_return', tone: 'green' },
      { label: 'Ошибки', value: rejected, icon: 'priority_high', tone: rejected ? 'red' : 'gray' },
      { label: 'Источник счетов', value: this.tbankClientPaymentEnabled() ? 'Банк' : 'Текст', icon: 'payments', tone: this.tbankClientPaymentEnabled() ? 'green' : 'gray' },
      { label: 'Режим', value: this.activeRuntimeMode() === 'TEST' ? 'Тестовый' : 'Боевой', icon: this.activeRuntimeMode() === 'TEST' ? 'science' : 'verified', tone: this.activeRuntimeMode() === 'TEST' ? 'yellow' : 'green' }
    ];
  });

  constructor() {
    this.load();
  }

  ngOnDestroy(): void {
    if (this.searchReloadTimer != null) {
      window.clearTimeout(this.searchReloadTimer);
    }
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.loadRecipientMonthlySummary();
    forkJoin({
      status: this.paymentsApi.getTbankStatus(),
      links: this.paymentsApi.getAdminTbankPaymentLinks(this.paymentLinkQuery()),
      profiles: this.paymentsApi.getAdminBankPaymentProfiles(),
      manualTasks: this.paymentsApi.getAdminManualPaymentTasks(),
      runtimeSettings: this.paymentsApi.getAdminTbankRuntimeSettings()
    }).subscribe({
      next: ({ status, links, profiles, manualTasks, runtimeSettings }) => {
        this.status.set(status);
        this.applyPaymentLinksPage(links);
        this.manualTasks.set(manualTasks ?? []);
        this.runtimeSettings.set(runtimeSettings);
        this.applyProfilesState(profiles.profiles, profiles.managers);
        this.loading.set(false);
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось загрузить банковские платежи');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Банковские платежи не загрузились', message);
      }
    });
  }

  setRecipientSummaryMonth(value: string): void {
    const month = value || TbankPaymentsComponent.currentMonthInput();
    if (month === this.recipientSummaryMonth()) {
      return;
    }
    this.recipientSummaryMonth.set(month);
    this.loadRecipientMonthlySummary();
  }

  setRuntimeMode(mode: TbankRuntimeMode): void {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.runtimeMode === mode) {
      return;
    }

    if (mode === 'LIVE') {
      const confirmed = window.confirm(
        'Переключить T‑Bank на рабочие терминалы? Клиентам банковские ссылки не уйдут, пока источник счетов остается «Альфа / текст».'
      );
      if (!confirmed) {
        return;
      }
    }

    const patch: UpdateTbankRuntimeSettingsRequest = { runtimeMode: mode };
    if (mode === 'TEST') {
      patch.paymentInstructionSource = 'MANAGER_TEXT';
      patch.applyConfirmedPayments = false;
    }
    this.saveRuntimeSettings(patch, mode === 'TEST' ? 'Включен тестовый контур' : 'Включен боевой контур');
  }

  setPaymentInstructionSource(source: PaymentInstructionSource): void {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.paymentInstructionSource === source) {
      return;
    }
    if (source === 'TBANK_LINK') {
      if (current.runtimeMode !== 'LIVE') {
        this.toastService.error('Сначала включите боевой контур', 'В тестовом режиме клиентам нельзя отправлять банковские ссылки.');
        return;
      }
      if (!this.bankReadyForClientPayments()) {
        this.toastService.error('Банковские ссылки еще не готовы', 'Проверьте API, создание ссылок, UI менеджера и реквизиты профилей.');
        return;
      }
      const confirmed = window.confirm(
        'Отправлять клиентам ссылки назначенного банка вместо старого текста/Альфа? Включайте после тестового реального платежа и проверки чека.'
      );
      if (!confirmed) {
        return;
      }
    }
    this.saveRuntimeSettings(
      { paymentInstructionSource: source },
      source === 'TBANK_LINK' ? 'Клиентские счета переключены на банковские ссылки' : 'Клиентские счета вернулись на Альфа / текст'
    );
  }

  setApplyConfirmedPayments(enabled: boolean): void {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.applyConfirmedPayments === enabled) {
      return;
    }
    if (enabled) {
      if (current.runtimeMode !== 'LIVE') {
        this.toastService.error('Нельзя в тестовом режиме', 'Тестовые платежи не должны переводить заказы в оплату.');
        return;
      }
      const confirmed = window.confirm(
        'Включить реальное применение оплат? После webhook CONFIRMED заказ будет переходить в оплату автоматически.'
      );
      if (!confirmed) {
        return;
      }
    }
    this.saveRuntimeSettings(
      { applyConfirmedPayments: enabled },
      enabled ? 'Автооплата заказов включена' : 'Автооплата заказов выключена'
    );
  }

  setPaymentPageMode(mode: TbankPaymentPageMode): void {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.paymentPageMode === mode) {
      return;
    }

    const titles: Record<TbankPaymentPageMode, string> = {
      SBP_PRIMARY: 'СБП выбран основным способом',
      BANK_PRIMARY: 'Форма банка выбрана основным способом',
      SBP_PAY_ONLY: 'На странице оплаты оставлены СБП и Pay',
      SBP_ONLY: 'На странице оплаты оставлен только СБП',
      BANK_ONLY: 'На странице оплаты оставлена только форма банка'
    };
    this.saveRuntimeSettings({ paymentPageMode: mode }, titles[mode]);
  }

  setCoreSwitch(
    field: 'tbankEnabled' | 'paymentLinksEnabled' | 'managerUiEnabled',
    enabled: boolean
  ): void {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current[field] === enabled) {
      return;
    }
    const patch: UpdateTbankRuntimeSettingsRequest = {};
    patch[field] = enabled;
    this.saveRuntimeSettings(
      patch,
      field === 'tbankEnabled' ? 'Настройка API T‑Bank сохранена' : 'Настройка банковских ссылок сохранена'
    );
  }

  setFastBankMethodSwitch(
    field: 'tpayEnabled' | 'sberpayEnabled' | 'mirpayEnabled',
    enabled: boolean
  ): void {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current[field] === enabled) {
      return;
    }
    const patch: UpdateTbankRuntimeSettingsRequest = {};
    patch[field] = enabled;
    this.saveRuntimeSettings(patch, 'Способы оплаты на /pay сохранены');
  }

  setSearch(value: string): void {
    this.search.set(value ?? '');
    this.paymentPage.set(0);
    if (this.searchReloadTimer != null) {
      window.clearTimeout(this.searchReloadTimer);
    }
    this.searchReloadTimer = window.setTimeout(() => {
      this.loadPaymentLinks();
      this.searchReloadTimer = null;
    }, 260);
  }

  setStatusFilter(filter: PaymentStatusFilter): void {
    this.statusFilter.set(filter);
    this.paymentPage.set(0);
    this.loadPaymentLinks();
  }

  statusTotal(filter: PaymentStatusFilter): number {
    const summary = this.paymentSummary();
    if (summary && this.statusFilter() === 'all') {
      switch (filter) {
        case 'all':
          return summary.totalElements;
        case 'paid':
          return summary.paid;
        case 'manual':
          return summary.manualPending;
        case 'refunded':
          return summary.refunded;
        case 'failed':
          return summary.rejected;
      }
    }
    if (filter === this.statusFilter() && this.paymentTotalElements()) {
      return this.paymentTotalElements();
    }
    return this.links().filter((link) => this.matchesStatusFilter(link, filter)).length;
  }

  setDateFrom(value: string): void {
    this.dateFrom.set(value ?? '');
    this.paymentPage.set(0);
    this.loadPaymentLinks();
  }

  setDateTo(value: string): void {
    this.dateTo.set(value ?? '');
    this.paymentPage.set(0);
    this.loadPaymentLinks();
  }

  resetFilters(): void {
    if (this.searchReloadTimer != null) {
      window.clearTimeout(this.searchReloadTimer);
      this.searchReloadTimer = null;
    }
    this.search.set('');
    this.statusFilter.set('all');
    this.dateFrom.set('');
    this.dateTo.set('');
    this.paymentPage.set(0);
    this.loadPaymentLinks();
  }

  setPaymentSource(source: PaymentLinkListSource): void {
    if (this.paymentSource() === source) {
      return;
    }
    this.paymentSource.set(source);
    this.paymentPage.set(0);
    this.loadPaymentLinks();
  }

  setPaymentPage(page: number): void {
    const totalPages = this.paymentTotalPages();
    const next = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
    if (next === this.paymentPage()) {
      return;
    }
    this.paymentPage.set(next);
    this.loadPaymentLinks();
  }

  setPaymentSize(size: string | number): void {
    const next = Number(size);
    if (!Number.isFinite(next) || next <= 0 || next === this.paymentSize()) {
      return;
    }
    this.paymentSize.set(next);
    this.paymentPage.set(0);
    this.loadPaymentLinks();
  }

  archiveClosedLinks(): void {
    if (this.archiving()) {
      return;
    }
    const confirmed = window.confirm(
      'Перенести закрытые старые платежи в архивную таблицу? В live-журнале останутся свежие и рабочие ссылки.'
    );
    if (!confirmed) {
      return;
    }
    this.archiving.set(true);
    this.paymentsApi.runAdminPaymentLinkArchive(false).subscribe({
      next: (result) => {
        this.archiving.set(false);
        this.toastService.success('Архивация платежей завершена', `Перенесено: ${result.archived}, удалено из live: ${result.deleted}`);
        this.loadPaymentLinks();
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось архивировать платежи');
        this.archiving.set(false);
        this.toastService.error('Архивация не выполнена', message);
      }
    });
  }

  setManagerProfile(managerId: number, value: string | number | null): void {
    const profileId = Number(value);
    this.profileAssignments.update((assignments) => ({
      ...assignments,
      [managerId]: Number.isFinite(profileId) && profileId > 0 ? profileId : null
    }));
  }

  selectedProfileId(manager: ManagerPaymentProfileResponse): number | null {
    return this.profileAssignments()[manager.managerId] ?? manager.paymentProfileId ?? null;
  }

  saveProfileAssignments(): void {
    if (this.savingProfiles()) {
      return;
    }
    const assignments = this.managerProfiles().map((manager) => ({
      managerId: manager.managerId,
      paymentProfileId: this.selectedProfileId(manager)
    }));
    this.savingProfiles.set(true);
    this.paymentsApi.updateAdminBankPaymentProfileAssignments(assignments).subscribe({
      next: (state) => {
        this.applyProfilesState(state.profiles, state.managers);
        this.savingProfiles.set(false);
        this.toastService.success('Платежные профили сохранены');
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось сохранить платежные профили');
        this.savingProfiles.set(false);
        this.toastService.error('Профили не сохранены', message);
      }
    });
  }

  setProfilePolicy(profileId: number, value: PaymentPolicy): void {
    this.profilePolicies.update((policies) => ({
      ...policies,
      [profileId]: {
        ...this.policyDraft(profileId),
        paymentPolicy: value
      }
    }));
  }

  setProfileManualPaymentType(profileId: number, value: ManualPaymentType): void {
    const patch: Partial<ProfilePolicyDraft> = { manualPaymentType: value };
    if (!this.policyDraft(profileId).manualRecipientName.trim()) {
      patch.manualRecipientName = TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME;
    }
    this.updateProfilePolicyDraft(profileId, patch);
  }

  setProfileManualPhone(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualPhone: value ?? '' });
  }

  setProfileManualRecipient(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualRecipientName: value ?? '' });
  }

  setProfileManualPaymentUrl(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualPaymentUrl: value ?? '' });
  }

  setProfileManualPaymentButtonLabel(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualPaymentButtonLabel: value ?? '' });
  }

  setProfileManualComment(profileId: number, value: string): void {
    this.updateProfilePolicyDraft(profileId, { manualComment: value ?? '' });
  }

  setProfileManualLimit(
    profileId: number,
    value: string | number | null
  ): void {
    this.updateProfilePolicyDraft(profileId, {
      manualMonthlyLimitRubles: value == null ? '' : String(value)
    });
  }

  saveProfilePolicies(): void {
    if (this.savingProfilePolicies()) {
      return;
    }
    const request: PaymentProfilePolicyRequest[] = this.profiles().map((profile) => {
      const draft = this.policyDraft(profile.id);
      const manualMonthlyLimitKopecks = this.manualLimitKopecksFromDraft(draft.manualMonthlyLimitRubles);
      return {
        profileId: profile.id,
        paymentPolicy: draft.paymentPolicy,
        manualPaymentType: draft.manualPaymentType,
        manualPhone: draft.manualPhone.trim(),
        manualRecipientName: draft.manualRecipientName.trim() || TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME,
        manualPaymentUrl: paymentTargetForUpdate(draft.manualPaymentUrl, profile.manualPaymentUrlConfigured),
        manualPaymentButtonLabel: draft.manualPaymentButtonLabel.trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        manualComment: draft.manualComment.trim(),
        manualMonthlySoftLimitKopecks: manualMonthlyLimitKopecks,
        manualMonthlyHardLimitKopecks: manualMonthlyLimitKopecks,
        manualPaymentUrlReplacementConfirmed: profile.manualPaymentUrlConfigured === false
          && Boolean(draft.manualPaymentUrl.trim())
      };
    });
    this.savingProfilePolicies.set(true);
    this.paymentsApi.updateAdminBankPaymentProfilePolicies(request).subscribe({
      next: (state) => {
        this.applyProfilesState(state.profiles, state.managers);
        this.savingProfilePolicies.set(false);
        this.toastService.success('Политики оплаты сохранены');
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось сохранить политики оплаты');
        this.savingProfilePolicies.set(false);
        this.toastService.error('Политики не сохранены', message);
      }
    });
  }

  saveRoutingSettings(): void {
    if (this.savingRoutingSettings()) {
      return;
    }
    const policyRequest: PaymentProfilePolicyRequest[] = this.profiles().map((profile) => {
      const draft = this.policyDraft(profile.id);
      const manualMonthlyLimitKopecks = this.manualLimitKopecksFromDraft(draft.manualMonthlyLimitRubles);
      return {
        profileId: profile.id,
        paymentPolicy: draft.paymentPolicy,
        manualPaymentType: draft.manualPaymentType,
        manualPhone: draft.manualPhone.trim(),
        manualRecipientName: draft.manualRecipientName.trim(),
        manualPaymentUrl: paymentTargetForUpdate(draft.manualPaymentUrl, profile.manualPaymentUrlConfigured),
        manualPaymentButtonLabel: draft.manualPaymentButtonLabel.trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        manualComment: draft.manualComment.trim(),
        manualMonthlySoftLimitKopecks: manualMonthlyLimitKopecks,
        manualMonthlyHardLimitKopecks: manualMonthlyLimitKopecks,
        manualPaymentUrlReplacementConfirmed: profile.manualPaymentUrlConfigured === false
          && Boolean(draft.manualPaymentUrl.trim())
      };
    });
    const assignments = this.managerProfiles().map((manager) => ({
      managerId: manager.managerId,
      paymentProfileId: this.selectedProfileId(manager)
    }));

    this.savingProfilePolicies.set(true);
    this.savingProfiles.set(true);
    let policiesSaved = false;
    this.paymentsApi.updateAdminBankPaymentProfilePolicies(policyRequest).pipe(
      tap(() => {
        policiesSaved = true;
      }),
      switchMap(() => this.paymentsApi.updateAdminBankPaymentProfileAssignments(assignments))
    ).subscribe({
      next: (state) => {
        this.applyProfilesState(state.profiles, state.managers);
        this.savingProfilePolicies.set(false);
        this.savingProfiles.set(false);
        this.toastService.success('Маршрутизация оплат сохранена');
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось сохранить маршрутизацию оплат');
        this.savingProfilePolicies.set(false);
        this.savingProfiles.set(false);
        if (policiesSaved) {
          this.toastService.error(
            'Маршрутизация сохранена частично',
            `Политики профилей применены, но назначения менеджеров не сохранены. Состояние перечитано с сервера. ${message}`
          );
          this.loadProfilesOnly(true);
          return;
        }
        this.toastService.error('Маршрутизация не сохранена', message);
      }
    });
  }

  cancel(link: AdminPaymentLinkResponse): void {
    if (link.archived || !link.refundable || this.mutatingId()) {
      return;
    }

    const confirmed = window.confirm(`Вернуть банковский платеж ${link.tbankPaymentId} на сумму ${link.amount} руб.?`);
    if (!confirmed) {
      return;
    }

    this.mutatingId.set(link.id);
    this.paymentsApi.cancelAdminTbankPaymentLink(link.id).subscribe({
      next: (updated) => {
        this.replaceLink(updated);
        this.mutatingId.set(null);
        this.toastService.success('Возврат отправлен', `Статус: ${this.statusLabel(updated.status)}`);
        this.loadPaymentLinks();
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось выполнить возврат');
        this.mutatingId.set(null);
        this.toastService.error('Возврат не выполнен', message);
      }
    });
  }

  confirmManual(link: AdminPaymentLinkResponse): void {
    if (!this.canConfirmManual(link) || this.mutatingId()) {
      return;
    }
    const confirmed = window.confirm(`Подтвердить ручную оплату по заказу №${link.orderId ?? '-'} на сумму ${link.amount} руб.?`);
    if (!confirmed) {
      return;
    }

    this.mutatingId.set(link.id);
    this.paymentsApi.confirmAdminManualPaymentLink(link.id).subscribe({
      next: (updated) => {
        this.replaceLink(updated);
        this.mutatingId.set(null);
        this.toastService.success('Ручная оплата подтверждена', `Статус: ${this.statusLabel(updated.status)}`);
        this.loadProfilesOnly();
        this.loadPaymentLinks();
        this.loadRecipientMonthlySummary();
      },
      error: (err) => {
        this.mutatingId.set(null);
        const routeCode = manualPaymentRouteErrorCode(err);
        if (routeCode === 'ACTUAL_RECIPIENT_REQUIRED') {
          const orderId = Number(link.orderId);
          if (!Number.isSafeInteger(orderId) || orderId <= 0) {
            this.toastService.error(
              'Оплата не подтверждена',
              'У платежа нет корректного заказа, поэтому безопасный выбор фактического получателя недоступен.'
            );
            return;
          }
          this.journalManualCardPaymentOrder.set({
            id: orderId,
            companyId: 0,
            companyTitle: link.companyTitle || `Заказ №${orderId}`,
            status: link.status
          });
          return;
        }
        const message = routeCode
          ? manualPaymentRouteErrorMessage(err, 'Не удалось подтвердить ручную оплату')
          : apiErrorDetail(err, 'Не удалось подтвердить ручную оплату');
        this.toastService.error('Оплата не подтверждена', message);
      }
    });
  }

  closeJournalManualCardPayment(): void {
    this.journalManualCardPaymentOrder.set(null);
  }

  completeJournalManualCardPayment(result: ManagerManualCardPaymentCompleted): void {
    this.journalManualCardPaymentOrder.set(null);
    if (result.result.status === 'OWNER_APPROVAL_PENDING') {
      this.toastService.warning(
        'Ожидается подтверждение владельца',
        result.result.message
      );
      this.loadPaymentLinks();
      return;
    }
    this.toastService.success(
      'Ручная оплата подтверждена',
      `Получатель: ${manualPaymentRecipientLabel(result.recipient)}`
    );
    this.loadPaymentLinks();
    this.loadProfilesOnly(true);
    this.loadManualTasks();
    this.loadRecipientMonthlySummary();
  }

  confirmContractorSource(link: AdminPaymentLinkResponse): void {
    if (!this.canConfirmContractorSource(link) || this.mutatingId()) {
      return;
    }
    if (!window.confirm(
      `Выписка получателя проверена и перевод относится именно к счету №${link.id}?`
    )) {
      return;
    }
    const currentRubles = Math.max(0, link.confirmedAmountKopecks ?? 0) / 100;
    const amountInput = window.prompt(
      `Укажите общую подтвержденную сумму поступлений именно по этому счету, ₽ (не больше ${link.amount}):`,
      currentRubles > 0 ? String(currentRubles) : String(link.amount)
    )?.trim() ?? '';
    const amountRubles = Number(amountInput.replace(',', '.'));
    const confirmedTotalKopecks = Math.round(amountRubles * 100);
    if (!Number.isFinite(amountRubles)
      || confirmedTotalKopecks <= 0
      || confirmedTotalKopecks > link.amountKopecks) {
      this.toastService.error('Поступление не подтверждено', 'Проверьте сумму: она должна быть больше нуля и не превышать сумму счета.');
      return;
    }
    const reason = window.prompt(
      'Укажите основание сверки (дата выписки, назначение или другой неперсональный комментарий):',
      ''
    )?.trim() ?? '';
    if (!reason) {
      this.toastService.error('Поступление не подтверждено', 'Для аудита требуется основание сверки.');
      return;
    }

    this.mutatingId.set(link.id);
    this.paymentsApi.confirmAdminContractorPaymentSource(link.id, {
      recipientStatementChecked: true,
      paymentReceived: true,
      confirmedTotalKopecks,
      reason
    }).subscribe({
      next: (updated) => {
        this.replaceLink(updated);
        this.mutatingId.set(null);
        this.toastService.success(
          'Поступление подтверждено',
          `Учтено по счету №${link.id}: ${(confirmedTotalKopecks / 100).toFixed(2)} ₽`
        );
        this.loadProfilesOnly();
        this.loadPaymentLinks();
        this.loadRecipientMonthlySummary();
      },
      error: (err) => {
        this.mutatingId.set(null);
        this.toastService.error(
          'Поступление не подтверждено',
          apiErrorDetail(err, 'Не удалось сохранить сверку по выбранному счету')
        );
      }
    });
  }

  closeManualAsUnpaid(link: AdminPaymentLinkResponse): void {
    if (!this.canCloseManualAsUnpaid(link) || this.mutatingId()) {
      return;
    }
    if (!window.confirm(manualPaymentUnpaidCloseConfirmation(link))) {
      return;
    }
    const note = window.prompt(manualPaymentUnpaidCloseNotePrompt(), '')?.trim() ?? '';
    if (!note) {
      this.toastService.error(
        'Инструкция не закрыта',
        'Для истории проверки нужна заметка: когда и какая выписка получателя проверена.'
      );
      return;
    }

    this.mutatingId.set(link.id);
    this.paymentsApi.closeAdminManualPaymentLinkAsUnpaid(link.id, {
      recipientStatementChecked: true,
      paymentAbsent: true,
      note
    }).subscribe({
      next: (updated) => {
        this.replaceLink(updated);
        this.mutatingId.set(null);
        this.toastService.success(
          'Ручная инструкция закрыта',
          'Перевод не засчитан. Статус оплаты заказа не изменён.'
        );
        this.loadProfilesOnly();
        this.loadPaymentLinks();
        this.loadRecipientMonthlySummary();
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось закрыть ручную инструкцию');
        this.mutatingId.set(null);
        this.toastService.error('Инструкция не закрыта', message);
      }
    });
  }

  markManualReceipt(link: AdminPaymentLinkResponse): void {
    if (!this.canMarkManualReceipt(link) || this.mutatingId()) {
      return;
    }

    this.mutatingId.set(link.id);
    this.paymentsApi.markAdminManualPaymentReceipt(link.id).subscribe({
      next: (updated) => {
        this.replaceLink(updated);
        this.mutatingId.set(null);
        this.toastService.success('Статус чека обновлен');
        this.loadProfilesOnly();
        this.loadPaymentLinks();
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось отметить чек');
        this.mutatingId.set(null);
        this.toastService.error('Чек не обновлен', message);
      }
    });
  }

  markLegacyReceiptNotRequired(link: AdminPaymentLinkResponse): void {
    if (!this.canMarkLegacyReceiptNotRequired(link) || this.mutatingId()) {
      return;
    }
    if (!window.confirm('Закрыть старую оплату без отметки чека? Денежные суммы при этом не изменятся.')) {
      return;
    }

    this.mutatingId.set(link.id);
    this.paymentsApi.markAdminManualPaymentReceiptLegacyNotRequired(link.id).subscribe({
      next: (updated) => {
        this.replaceLink(updated);
        this.mutatingId.set(null);
        this.toastService.success('Старая оплата закрыта без требования чека');
        this.loadPaymentLinks();
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось закрыть старую оплату без чека');
        this.mutatingId.set(null);
        this.toastService.error('Статус чека не обновлен', message);
      }
    });
  }

  updateManualTaskStatus(task: ManualPaymentTaskResponse, status: ManualPaymentTaskStatus): void {
    if (!task?.id || this.mutatingTaskId()) {
      return;
    }
    this.mutatingTaskId.set(task.id);
    this.paymentsApi.updateAdminManualPaymentTaskStatus(task.id, status).subscribe({
      next: (updated) => {
        this.manualTasks.update((tasks) => tasks.map((item) => item.id === updated.id ? updated : item));
        this.mutatingTaskId.set(null);
        this.toastService.success('Статус задания сохранен');
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось обновить ручное задание');
        this.mutatingTaskId.set(null);
        this.toastService.error('Задание не сохранено', message);
      }
    });
  }

  startManualTaskEdit(task: ManualPaymentTaskResponse): void {
    if (!task?.id || task.status === 'COMPLETED' || task.status === 'CANCELED') {
      return;
    }
    this.editingTaskId.set(task.id);
    this.editTaskPaymentType.set(this.normalizeManualPaymentType(task.manualPaymentType));
    this.editTaskPhone.set(task.manualPhone ?? '');
    this.editTaskRecipient.set(task.manualRecipientName || TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME);
    this.editTaskBankName.set(task.manualBankName ?? '');
    this.editTaskPaymentUrl.set(task.manualPaymentUrl?.trim() ?? '');
    this.editTaskPaymentButtonLabel.set(task.manualPaymentButtonLabel || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
    this.editTaskAmountRubles.set(String((task.targetAmountKopecks ?? 0) / 100));
    this.editTaskComment.set(task.comment ?? '');
    this.editTaskAccountingTargetKey.set('');
    this.editTaskAccountingTargetAcknowledged.set(false);
    this.loadEditTaskAccountingTargets(task);
  }

  cancelManualTaskEdit(): void {
    this.editingTaskId.set(null);
    this.editTaskAccountingTargets.set([]);
    this.editTaskAccountingTargetKey.set('');
    this.editTaskAccountingTargetAcknowledged.set(false);
    this.editTaskAccountingTargetError.set(null);
    this.editTaskAccountingPreviewEpoch += 1;
  }

  setEditTaskPaymentType(value: ManualPaymentType): void {
    this.editTaskPaymentType.set(value);
    if (!this.editTaskRecipient().trim()) {
      this.editTaskRecipient.set(TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME);
    }
  }

  setEditTaskPhone(value: string | null): void {
    this.editTaskPhone.set(value ?? '');
  }

  setEditTaskRecipient(value: string | null): void {
    this.editTaskRecipient.set(value ?? '');
  }

  setEditTaskBankName(value: string | null): void {
    this.editTaskBankName.set(value ?? '');
  }

  setEditTaskPaymentUrl(value: string | null): void {
    this.editTaskPaymentUrl.set(value ?? '');
  }

  setEditTaskPaymentButtonLabel(value: string | null): void {
    this.editTaskPaymentButtonLabel.set(value ?? '');
  }

  setEditTaskAmountRubles(value: string | number | null): void {
    this.editTaskAmountRubles.set(value == null ? '' : String(value));
    this.loadEditTaskAccountingTargets();
  }

  setEditTaskAccountingTarget(value: string | null): void {
    this.editTaskAccountingTargetKey.set(value?.trim() ?? '');
    this.editTaskAccountingTargetAcknowledged.set(false);
  }

  setEditTaskAccountingTargetAcknowledged(value: boolean): void {
    this.editTaskAccountingTargetAcknowledged.set(Boolean(value));
  }

  setEditTaskComment(value: string | null): void {
    this.editTaskComment.set(value ?? '');
  }

  canSaveManualTaskEdit(task: ManualPaymentTaskResponse): boolean {
    const hasTarget = this.editTaskPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.editTaskPhone().trim()) && Boolean(this.editTaskRecipient().trim())
      : Boolean(this.editTaskPaymentUrl().trim()) && Boolean(this.editTaskRecipient().trim());
    return this.editingTaskId() === task.id
      && this.mutatingTaskId() !== task.id
      && task.status !== 'COMPLETED'
      && task.status !== 'CANCELED'
      && hasTarget
      && this.editTaskTargetKopecks() >= Math.max(1, task.reservedAmountKopecks ?? 0)
      && !this.editTaskAccountingTargetsLoading()
      && manualPaymentTaskTargetValid(
        this.selectedEditTaskAccountingTarget(), this.editTaskAccountingTargetAcknowledged()
      );
  }

  saveManualTaskEdit(task: ManualPaymentTaskResponse): void {
    if (!task?.id || !this.canSaveManualTaskEdit(task)) {
      return;
    }
    const accountingTarget = this.selectedEditTaskAccountingTarget();
    if (!accountingTarget) {
      return;
    }
    this.mutatingTaskId.set(task.id);
    this.paymentsApi.updateAdminManualPaymentTask(task.id, {
      manualPaymentType: this.editTaskPaymentType(),
      manualPhone: this.editTaskPhone().trim(),
      manualRecipientName: this.editTaskRecipient().trim() || TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME,
      manualBankName: this.editTaskBankName().trim() || null,
      manualPaymentUrl: this.editTaskPaymentUrl().trim(),
      manualPaymentButtonLabel: this.editTaskPaymentButtonLabel().trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      targetAmountKopecks: this.editTaskTargetKopecks(),
      comment: this.editTaskComment().trim() || null,
      manualPaymentUrlReplacementConfirmed: this.editTaskPaymentType() === 'EXTERNAL_LINK'
        && !Boolean(task.manualPaymentUrl?.trim())
        && Boolean(this.editTaskPaymentUrl().trim()),
      accountingTargetKind: accountingTarget.kind,
      accountingTargetProfileId: accountingTarget.profileId ?? null,
      accountingTargetOverrunAcknowledged: this.editTaskAccountingTargetAcknowledged(),
      expectedGeneration: task.generation ?? null
    }).subscribe({
      next: (updated) => {
        this.manualTasks.update((tasks) => tasks.map((item) => item.id === updated.id ? updated : item));
        this.editingTaskId.set(null);
        this.mutatingTaskId.set(null);
        this.toastService.success('Задание сохранено');
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось обновить ручное задание');
        this.mutatingTaskId.set(null);
        this.toastService.error('Задание не сохранено', message);
      }
    });
  }

  setAdminTaskManagerId(value: number | string | null): void {
    const id = value == null || value === '' ? NaN : Number(value);
    this.adminTaskManagerId.set(Number.isFinite(id) && id > 0 ? id : null);
    this.loadAdminTaskAccountingTargets();
  }

  setAdminTaskPaymentType(value: ManualPaymentType): void {
    this.adminTaskPaymentType.set(value);
    if (!this.adminTaskRecipient().trim()) {
      this.adminTaskRecipient.set(TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME);
    }
  }

  setAdminTaskPhone(value: string | null): void {
    this.adminTaskPhone.set(value ?? '');
  }

  setAdminTaskRecipient(value: string | null): void {
    this.adminTaskRecipient.set(value ?? '');
  }

  setAdminTaskBankName(value: string | null): void {
    this.adminTaskBankName.set(value ?? '');
  }

  setAdminTaskPaymentUrl(value: string | null): void {
    this.adminTaskPaymentUrl.set(value ?? '');
  }

  setAdminTaskPaymentButtonLabel(value: string | null): void {
    this.adminTaskPaymentButtonLabel.set(value ?? '');
  }

  setAdminTaskAmountRubles(value: string | number | null): void {
    this.adminTaskAmountRubles.set(value == null ? '' : String(value));
    this.loadAdminTaskAccountingTargets();
  }

  setAdminTaskAccountingTarget(value: string | null): void {
    this.adminTaskAccountingTargetKey.set(value?.trim() ?? '');
    this.adminTaskAccountingTargetAcknowledged.set(false);
  }

  setAdminTaskAccountingTargetAcknowledged(value: boolean): void {
    this.adminTaskAccountingTargetAcknowledged.set(Boolean(value));
  }

  setAdminTaskComment(value: string | null): void {
    this.adminTaskComment.set(value ?? '');
  }

  createManualTask(): void {
    if (!this.canCreateManualTask()) {
      return;
    }
    const accountingTarget = this.selectedAdminTaskAccountingTarget();
    if (!accountingTarget) {
      return;
    }
    this.savingManualTask.set(true);
    this.paymentsApi.createAdminManualPaymentTask({
      operationKey: this.adminTaskOperationKey.current(),
      managerId: this.adminTaskManagerId(),
      manualPaymentType: this.adminTaskPaymentType(),
      manualPhone: this.adminTaskPhone().trim(),
      manualRecipientName: this.adminTaskRecipient().trim() || TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME,
      manualBankName: this.adminTaskBankName().trim() || null,
      manualPaymentUrl: this.adminTaskPaymentUrl().trim(),
      manualPaymentButtonLabel: this.adminTaskPaymentButtonLabel().trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      targetAmountKopecks: this.adminTaskTargetKopecks(),
      comment: this.adminTaskComment().trim() || null,
      accountingTargetKind: accountingTarget.kind,
      accountingTargetProfileId: accountingTarget.profileId ?? null,
      accountingTargetOverrunAcknowledged: this.adminTaskAccountingTargetAcknowledged()
    }).subscribe({
      next: (task) => {
        this.manualTasks.update((tasks) => [task, ...tasks.filter((item) => item.id !== task.id)]);
        this.savingManualTask.set(false);
        this.resetAdminTaskDraft();
        this.toastService.success('Ручное задание создано');
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось создать ручное задание');
        this.savingManualTask.set(false);
        this.toastService.error('Задание не создано', message);
      }
    });
  }

  resetAdminTaskDraft(): void {
    if (this.savingManualTask()) {
      return;
    }
    this.adminTaskPhone.set('');
    this.adminTaskRecipient.set(TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME);
    this.adminTaskBankName.set('');
    this.adminTaskPaymentType.set('MOBILE_BANK');
    this.adminTaskPaymentUrl.set(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL);
    this.adminTaskPaymentButtonLabel.set(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
    this.adminTaskAmountRubles.set('');
    this.adminTaskComment.set('');
    this.adminTaskAccountingTargets.set([]);
    this.adminTaskAccountingTargetKey.set('');
    this.adminTaskAccountingTargetAcknowledged.set(false);
    this.adminTaskAccountingTargetError.set(null);
    this.adminTaskAccountingPreviewEpoch += 1;
    this.adminTaskOperationKey.rotate();
  }

  async copy(value: string | null | undefined, key: string): Promise<void> {
    const text = value?.trim();
    if (!text) {
      return;
    }

    if (await copyTextToClipboard(text)) {
      this.copied.set(key);
      window.setTimeout(() => {
        if (this.copied() === key) {
          this.copied.set(null);
        }
      }, 1600);
      this.toastService.success('Скопировано');
    } else {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    }
  }

  statusLabel(status: string): string {
    const labels: Record<string, string> = {
      CREATED: 'Создана',
      INITIATED: 'Открыта форма',
      AUTHORIZED: 'Авторизован',
      WAITING_MANUAL_PAYMENT: 'Ждет перевод',
      MANUAL_REPORTED: 'Клиент оплатил',
      TEST_CONFIRMED: 'Тест оплачен',
      CONFIRMED: 'Оплачен',
      REJECTED: 'Отклонен',
      CANCELED: 'Отменен',
      REVERSED: 'Отменен полностью',
      PARTIAL_REVERSED: 'Отменен частично',
      REFUNDED: 'Возвращен полностью',
      PARTIAL_REFUNDED: 'Возвращен частично',
      EXPIRED: 'Истек',
      FAILED: 'Ошибка',
      NEEDS_RECONCILIATION: 'Требует сверки с банком'
    };
    return labels[status] ?? status;
  }

  statusClass(status: string): string {
    if (status === 'TEST_CONFIRMED' || status === 'CONFIRMED' || status === 'AUTHORIZED') {
      return 'status-pill paid';
    }
    if (status === 'WAITING_MANUAL_PAYMENT' || status === 'MANUAL_REPORTED') {
      return 'status-pill manual';
    }
    if (this.isRefunded(status) || status === 'CANCELED') {
      return 'status-pill refunded';
    }
    if (status === 'REJECTED' || status === 'FAILED' || status === 'NEEDS_RECONCILIATION' || status === 'EXPIRED') {
      return 'status-pill failed';
    }
    return 'status-pill neutral';
  }

  paymentTitle(link: AdminPaymentLinkResponse): string {
    return link.companyTitle || link.filialTitle || `Заказ ${link.orderId ?? '-'}`;
  }

  paymentSubtitle(link: AdminPaymentLinkResponse): string {
    const parts = [link.filialTitle, link.description].filter(Boolean);
    return parts.join(' - ') || 'Банк';
  }

  paymentProfileDisplayName(link: AdminPaymentLinkResponse): string {
    const profileCode = link.paymentProfileCode?.trim();
    const profile = profileCode
      ? this.profiles().find((candidate) => candidate.code === profileCode)
      : null;
    if (profile) {
      return `${bankProviderLabel(profile.provider)} · ${profile.name}`;
    }
    return link.paymentProfileName || link.tbankTerminalKey || 'Профиль оплаты';
  }

  paymentMethodLabel(link: AdminPaymentLinkResponse): string {
    if (this.isManualPayment(link)) {
      return this.isExternalManualPayment(link) ? 'Ссылка Альфа' : 'Телефон';
    }
    return link.paymentMethod === 'SBP_QR' ? 'СБП' : 'Форма банка';
  }

  manualTaskTargetLine(task: ManualPaymentTaskResponse): string {
    if (this.isExternalManualTask(task)) {
      return `${task.manualPaymentUrl?.trim() || 'ссылка не настроена'} · ${task.managerTitle || task.username}`;
    }
    return `${task.manualPhone || 'телефон не указан'} · ${task.managerTitle || task.username}`;
  }

  manualTaskTitle(task: ManualPaymentTaskResponse): string {
    return task.manualRecipientName || 'Получатель не указан';
  }

  receiptStatusLabel(link: AdminPaymentLinkResponse): string {
    if (!this.isManualPayment(link)) {
      return '';
    }
    if (link.receiptStatus === 'MARKED') {
      return 'Чек отмечен';
    }
    if (link.receiptStatus === 'LEGACY_NOT_REQUIRED') {
      return 'Старая оплата · чек не требуется';
    }
    return 'Чек ожидает';
  }

  manualSourceLabel(link: AdminPaymentLinkResponse): string {
    if (link.manualSource === 'CONTRACTOR_PAYMENT_PROFILE') {
      return 'Платёжный профиль исполнителя';
    }
    if (link.manualSource === 'MANUAL_TASK') {
      return link.manualTaskTitle ? `Задание: ${link.manualTaskTitle}` : 'Ручное задание';
    }
    if (this.isManualPayment(link)) {
      return 'Лимит профиля';
    }
    return '';
  }

  recipientSummaryRecipientLabel(item: ManualPaymentRecipientMonthlySummaryItem): string {
    return manualPaymentAccountingRecipientLabel(item);
  }

  recipientSummaryDestinationLabel(item: ManualPaymentRecipientMonthlySummaryItem): string {
    return manualPaymentAccountingDestinationLabel(item);
  }

  recipientSummarySourceLabel(item: ManualPaymentRecipientMonthlySummaryItem): string {
    return manualPaymentAccountingSourceLabel(item);
  }

  hasPaymentNotificationInfo(link: AdminPaymentLinkResponse): boolean {
    return link.status === 'CONFIRMED'
      || Boolean(link.paymentSuccessNotifiedAt)
      || Boolean(link.paymentSuccessNotificationError);
  }

  paymentNotificationLabel(link: AdminPaymentLinkResponse): string {
    if (link.paymentSuccessNotifiedAt) {
      return 'Уведомление отправлено';
    }
    if (link.paymentSuccessNotificationError) {
      return 'Уведомление не отправлено';
    }
    if (link.status === 'CONFIRMED') {
      return 'Уведомление ожидает отправки';
    }
    return '';
  }

  paymentNotificationClass(link: AdminPaymentLinkResponse): string {
    if (link.paymentSuccessNotifiedAt) {
      return 'status-pill paid';
    }
    if (link.paymentSuccessNotificationError) {
      return 'status-pill failed';
    }
    return 'status-pill neutral';
  }

  clientChatInfo(link: AdminPaymentLinkResponse): string {
    const platform = this.chatPlatformLabel(link.clientChatPlatform);
    if (link.clientChatReady) {
      return `Чат ${platform} готов`;
    }
    const warning = link.clientChatWarning?.trim();
    return warning ? `Чат: ${warning}` : 'Чат не готов';
  }

  clientChatClass(link: AdminPaymentLinkResponse): string {
    return link.clientChatReady ? 'method-line' : 'error-text';
  }

  isManualPayment(link: AdminPaymentLinkResponse): boolean {
    return link.paymentMethod === 'MANUAL_MOBILE_BANK' || link.paymentMethod === 'MANUAL_EXTERNAL_LINK';
  }

  isExternalManualPayment(link: AdminPaymentLinkResponse): boolean {
    return this.isManualPayment(link)
      && (link.manualPaymentType === 'EXTERNAL_LINK' || link.paymentMethod === 'MANUAL_EXTERNAL_LINK');
  }

  isExternalManualTask(task: ManualPaymentTaskResponse): boolean {
    return this.normalizeManualPaymentType(task.manualPaymentType) === 'EXTERNAL_LINK';
  }

  canConfirmManual(link: AdminPaymentLinkResponse): boolean {
    return !link.archived
      && this.isManualPayment(link)
      && link.manualSource !== 'CONTRACTOR_PAYMENT_PROFILE'
      && (link.status === 'WAITING_MANUAL_PAYMENT' || link.status === 'MANUAL_REPORTED');
  }

  canConfirmContractorSource(link: AdminPaymentLinkResponse): boolean {
    return !link.archived
      && link.manualSource === 'CONTRACTOR_PAYMENT_PROFILE'
      && link.paymentMethod === 'MANUAL_MOBILE_BANK'
      && ['WAITING_MANUAL_PAYMENT', 'MANUAL_REPORTED', 'EXPIRED', 'CANCELED', 'AMOUNT_MISMATCH']
        .includes(link.status);
  }

  canCloseManualAsUnpaid(link: AdminPaymentLinkResponse): boolean {
    return !link.archived && canCloseManualPaymentAsUnpaid(link);
  }

  canMarkManualReceipt(link: AdminPaymentLinkResponse): boolean {
    return !link.archived
      && this.isManualPayment(link)
      && link.status === 'CONFIRMED'
      && link.receiptStatus !== 'MARKED'
      && link.receiptStatus !== 'LEGACY_NOT_REQUIRED';
  }

  canMarkLegacyReceiptNotRequired(link: AdminPaymentLinkResponse): boolean {
    if (!this.canMarkManualReceipt(link) || link.receiptStatus !== 'PENDING' || !link.paidAt) {
      return false;
    }
    return Date.now() - new Date(link.paidAt).getTime() >= 30 * 24 * 60 * 60 * 1000;
  }

  profilePolicy(profileId: number): ProfilePolicyDraft {
    return this.policyDraft(profileId);
  }

  profilePolicyLabel(profile: PaymentProfileResponse): string {
    const policy = this.policyDraft(profile.id).paymentPolicy;
    if (policy !== 'MANUAL_UNTIL_LIMIT_THEN_TBANK') {
      return 'Только банк';
    }
    return this.policyDraft(profile.id).manualPaymentType === 'EXTERNAL_LINK'
      ? 'Ссылка до лимита'
      : 'Телефон до лимита';
  }

  profileProviderLabel(profile: PaymentProfileResponse): string {
    return bankProviderLabel(profile.provider);
  }

  managerProfileOptionLabel(profile: PaymentProfileResponse): string {
    return bankProfileOptionLabel(profile);
  }

  defaultManagerProfileOptionLabel(): string {
    const profile = this.defaultBankProfile();
    return profile ? `По умолчанию · ${bankProfileOptionLabel(profile)}` : 'По умолчанию';
  }

  defaultManagerProfileReady(): boolean {
    const profile = this.defaultBankProfile();
    return Boolean(profile && this.bankProfileReady(profile));
  }

  selectedManagerProfile(manager: ManagerPaymentProfileResponse): PaymentProfileResponse | null {
    const profileId = this.selectedProfileId(manager);
    return profileId == null ? null : this.profiles().find((profile) => profile.id === profileId) ?? null;
  }

  managerProfileChanged(manager: ManagerPaymentProfileResponse): boolean {
    return this.selectedProfileId(manager) !== (manager.paymentProfileId ?? null);
  }

  bankProfileReady(profile: PaymentProfileResponse): boolean {
    if (profile.operational != null) {
      return profile.operational;
    }
    if (!profile.enabled || !profile.hasPassword) {
      return false;
    }
    const provider = (profile.provider ?? '').trim().toUpperCase();
    if (provider === 'T_BANK' || provider === 'TBANK' || provider === 'T-BANK') {
      return Boolean(this.runtimeSettings()?.tbankEnabled && this.status()?.hasCredentials);
    }
    return false;
  }

  profileManualUsagePercent(profile: PaymentProfileResponse): number {
    const limit = this.profileManualLimitKopecks(profile);
    if (!limit) {
      return 0;
    }
    return Math.min(100, Math.round((profile.manualMonthlyUsedKopecks / limit) * 100));
  }

  profileManualLimitKopecks(profile: PaymentProfileResponse): number {
    return this.manualLimitKopecksFromDraft(this.policyDraft(profile.id).manualMonthlyLimitRubles);
  }

  profileManualAvailableKopecks(profile: PaymentProfileResponse): number {
    return Math.max(0, this.profileManualLimitKopecks(profile) - (profile.manualMonthlyUsedKopecks ?? 0));
  }

  manualTaskProgressPercent(task: ManualPaymentTaskResponse): number {
    if (!task.targetAmountKopecks) {
      return 0;
    }
    return Math.min(100, Math.round((task.reservedAmountKopecks / task.targetAmountKopecks) * 100));
  }

  manualTaskStatusLabel(status: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'Активно';
      case 'PAUSED':
        return 'Пауза';
      case 'COMPLETED':
        return 'Выполнено';
      case 'CANCELED':
        return 'Отменено';
      default:
        return status || 'Неизвестно';
    }
  }

  formatKopecks(value?: number | null): string {
    return `${new Intl.NumberFormat('ru-RU', {
      minimumFractionDigits: 0,
      maximumFractionDigits: 2
    }).format((value ?? 0) / 100)} руб.`;
  }

  isMutating(link: AdminPaymentLinkResponse): boolean {
    return this.mutatingId() === link.id;
  }

  safePaymentHref(
    value: unknown,
    purpose: PaymentNavigationPurpose = 'payment'
  ): string | null {
    return safePaymentNavigationTarget(value, purpose);
  }

  trackLink(_index: number, link: AdminPaymentLinkResponse): number {
    return link.id;
  }

  trackMetric(_index: number, metric: PaymentMetric): string {
    return metric.label;
  }

  trackStatusOption(_index: number, option: StatusFilterOption): string {
    return option.key;
  }

  trackProfile(_index: number, profile: PaymentProfileResponse): number {
    return profile.id;
  }

  trackManagerProfile(_index: number, manager: ManagerPaymentProfileResponse): number {
    return manager.managerId;
  }

  trackManualTask(_index: number, task: ManualPaymentTaskResponse): number {
    return task.id;
  }

  private loadAdminTaskAccountingTargets(): void {
    const managerId = this.adminTaskManagerId();
    const amount = this.adminTaskTargetKopecks();
    const previousKey = this.adminTaskAccountingTargetKey();
    const epoch = ++this.adminTaskAccountingPreviewEpoch;
    this.adminTaskAccountingTargetAcknowledged.set(false);
    this.adminTaskAccountingTargetError.set(null);
    if (managerId == null || amount <= 0) {
      this.adminTaskAccountingTargets.set([]);
      this.adminTaskAccountingTargetKey.set('');
      this.adminTaskAccountingTargetsLoading.set(false);
      return;
    }
    this.adminTaskAccountingTargetsLoading.set(true);
    this.paymentsApi.getAdminManualPaymentTaskAccountingTargets(managerId, amount).subscribe({
      next: (options) => {
        if (epoch !== this.adminTaskAccountingPreviewEpoch) {
          return;
        }
        const normalized = options ?? [];
        const restored = normalized.find(option => option.key === previousKey)
          ?? manualPaymentTaskRecommendedTarget(normalized);
        this.adminTaskAccountingTargets.set(normalized);
        this.adminTaskAccountingTargetKey.set(restored?.key ?? '');
        this.adminTaskAccountingTargetsLoading.set(false);
      },
      error: (error) => {
        if (epoch !== this.adminTaskAccountingPreviewEpoch) {
          return;
        }
        this.adminTaskAccountingTargets.set([]);
        this.adminTaskAccountingTargetKey.set('');
        this.adminTaskAccountingTargetsLoading.set(false);
        this.adminTaskAccountingTargetError.set(apiErrorDetail(
          error, 'Не удалось рассчитать получателя и возможное превышение.'
        ));
      }
    });
  }

  private loadEditTaskAccountingTargets(sourceTask?: ManualPaymentTaskResponse): void {
    const task = sourceTask ?? this.manualTasks().find(item => item.id === this.editingTaskId());
    const amount = this.editTaskTargetKopecks();
    const previousKey = this.editTaskAccountingTargetKey();
    const epoch = ++this.editTaskAccountingPreviewEpoch;
    this.editTaskAccountingTargetAcknowledged.set(false);
    this.editTaskAccountingTargetError.set(null);
    if (!task?.managerId || amount <= 0) {
      this.editTaskAccountingTargets.set([]);
      this.editTaskAccountingTargetKey.set('');
      this.editTaskAccountingTargetsLoading.set(false);
      this.editTaskAccountingTargetError.set(task?.managerId ? null : 'У задания не найден менеджер. Сохранение заблокировано.');
      return;
    }
    this.editTaskAccountingTargetsLoading.set(true);
    this.paymentsApi.getAdminManualPaymentTaskAccountingTargets(task.managerId, amount, task.id).subscribe({
      next: (options) => {
        if (epoch !== this.editTaskAccountingPreviewEpoch) {
          return;
        }
        const normalized = options ?? [];
        const restored = normalized.find(option => option.key === previousKey)
          ?? manualPaymentTaskTargetForSnapshot(normalized, task);
        this.editTaskAccountingTargets.set(normalized);
        this.editTaskAccountingTargetKey.set(restored?.key ?? '');
        this.editTaskAccountingTargetsLoading.set(false);
      },
      error: (error) => {
        if (epoch !== this.editTaskAccountingPreviewEpoch) {
          return;
        }
        this.editTaskAccountingTargets.set([]);
        this.editTaskAccountingTargetKey.set('');
        this.editTaskAccountingTargetsLoading.set(false);
        this.editTaskAccountingTargetError.set(apiErrorDetail(error, 'Не удалось пересчитать получателя задания.'));
      }
    });
  }

  totalAmount(links: AdminPaymentLinkResponse[]): number {
    return links.reduce((sum, link) => sum + Number(link.amount || 0), 0);
  }

  rowMode(link: AdminPaymentLinkResponse): string {
    if (link.archived) {
      return 'archived';
    }
    if (this.isPaid(link.status)) {
      return 'paid';
    }
    if (this.isRefunded(link.status) || link.status === 'CANCELED') {
      return 'refunded';
    }
    if (this.isManualPayment(link) && (link.status === 'WAITING_MANUAL_PAYMENT' || link.status === 'MANUAL_REPORTED')) {
      return 'manual';
    }
    if (link.status === 'REJECTED' || link.status === 'FAILED' || link.status === 'NEEDS_RECONCILIATION' || link.status === 'EXPIRED') {
      return 'failed';
    }
    return 'neutral';
  }

  private saveRuntimeSettings(patch: UpdateTbankRuntimeSettingsRequest, successTitle: string): void {
    const previous = this.runtimeSettings();
    if (!previous) {
      return;
    }
    const optimistic = { ...previous, ...patch };
    optimistic.testMode = optimistic.runtimeMode === 'TEST';
    optimistic.clientTbankEnabled = isBankPaymentRouteType(optimistic.paymentInstructionSource);
    this.runtimeSettings.set(optimistic);
    this.savingRuntimeSettings.set(true);
    this.paymentsApi.updateAdminTbankRuntimeSettings(patch).subscribe({
      next: (settings) => {
        this.runtimeSettings.set(settings);
        this.status.update((status) => status ? {
          ...status,
          enabled: settings.tbankEnabled,
          paymentLinksEnabled: settings.paymentLinksEnabled,
          managerUiEnabled: settings.managerUiEnabled,
          applyConfirmedPayments: settings.applyConfirmedPayments,
          runtimeMode: settings.runtimeMode,
          testMode: settings.testMode
        } : status);
        this.savingRuntimeSettings.set(false);
        this.toastService.success(successTitle);
      },
      error: (err) => {
        this.runtimeSettings.set(previous);
        this.savingRuntimeSettings.set(false);
        const message = apiErrorDetail(err, 'Не удалось сохранить настройки запуска');
        this.toastService.error('Настройки банка не сохранены', message);
      }
    });
  }

  private adminTaskTargetKopecks(): number {
    const value = Number(this.adminTaskAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
  }

  private editTaskTargetKopecks(): number {
    const value = Number(this.editTaskAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
  }

  private normalizeManualPaymentType(value?: string | null): ManualPaymentType {
    return value === 'EXTERNAL_LINK' ? 'EXTERNAL_LINK' : 'MOBILE_BANK';
  }

  private matchesSearch(link: AdminPaymentLinkResponse, search: string): boolean {
    if (!search) {
      return true;
    }
    const haystack = [
      link.companyTitle,
      link.filialTitle,
      link.description,
      link.orderId,
      link.tbankPaymentId,
      link.tbankOrderId,
      link.paymentProfileName,
      link.tbankTerminalKey,
      link.payerEmail,
      link.manualPhone,
      link.manualRecipientName,
      link.manualPaymentUrl,
      link.manualPaymentButtonLabel,
      link.manualComment,
      link.paymentSuccessNotificationError,
      link.clientChatPlatform,
      link.clientChatWarning,
      link.lastError,
      this.statusLabel(link.status)
    ].join(' ').toLowerCase();
    return haystack.includes(search);
  }

  private matchesStatusFilter(link: AdminPaymentLinkResponse, filter: PaymentStatusFilter): boolean {
    switch (filter) {
      case 'active':
        return link.status === 'CREATED'
          || link.status === 'INITIATED'
          || link.status === 'AUTHORIZED'
          || link.status === 'WAITING_MANUAL_PAYMENT'
          || link.status === 'MANUAL_REPORTED';
      case 'paid':
        return this.isPaid(link.status);
      case 'refunded':
        return this.isRefunded(link.status) || link.status === 'CANCELED';
      case 'failed':
        return link.status === 'REJECTED'
          || link.status === 'FAILED'
          || link.status === 'NEEDS_RECONCILIATION'
          || link.status === 'EXPIRED';
      case 'created':
        return link.status === 'CREATED';
      case 'manual':
        return this.isManualPayment(link);
      default:
        return true;
    }
  }

  private matchesDateRange(link: AdminPaymentLinkResponse, from: string, to: string): boolean {
    const createdAt = new Date(link.createdAt).getTime();
    if (Number.isNaN(createdAt)) {
      return true;
    }
    if (from) {
      const fromTime = new Date(`${from}T00:00:00`).getTime();
      if (!Number.isNaN(fromTime) && createdAt < fromTime) {
        return false;
      }
    }
    if (to) {
      const toTime = new Date(`${to}T23:59:59.999`).getTime();
      if (!Number.isNaN(toTime) && createdAt > toTime) {
        return false;
      }
    }
    return true;
  }

  private applyProfilesState(
    profiles: PaymentProfileResponse[],
    managers: ManagerPaymentProfileResponse[]
  ): void {
    this.profiles.set(profiles ?? []);
    this.managerProfiles.set(managers ?? []);
    this.profileAssignments.set(Object.fromEntries(
      (managers ?? []).map((manager) => [manager.managerId, manager.paymentProfileId ?? null])
    ));
    this.profilePolicies.set(Object.fromEntries(
      (profiles ?? []).map((profile) => [profile.id, this.profileToPolicyDraft(profile)])
    ));
  }

  private loadProfilesOnly(showError = false): void {
    this.paymentsApi.getAdminBankPaymentProfiles().subscribe({
      next: (profiles) => this.applyProfilesState(profiles.profiles, profiles.managers),
      error: (err) => {
        if (showError) {
          this.toastService.error(
            'Состояние профилей не обновлено',
            apiErrorDetail(err, 'Обновите страницу перед следующей операцией')
          );
        }
      }
    });
  }

  private loadPaymentLinks(): void {
    this.paymentsApi.getAdminTbankPaymentLinks(this.paymentLinkQuery()).subscribe({
      next: (links) => this.applyPaymentLinksPage(links),
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось обновить журнал платежей');
        this.toastService.error('Журнал не обновлен', message);
      }
    });
  }

  private loadManualTasks(): void {
    this.paymentsApi.getAdminManualPaymentTasks().subscribe({
      next: (tasks) => this.manualTasks.set(tasks ?? []),
      error: (err) => this.toastService.error(
        'Задания не обновлены',
        apiErrorDetail(err, 'Обновите страницу перед следующей операцией')
      )
    });
  }

  loadRecipientMonthlySummary(): void {
    const loadEpoch = ++this.recipientSummaryLoadEpoch;
    this.loadingRecipientSummary.set(true);
    this.recipientSummaryError.set(null);
    this.paymentsApi.getAdminManualRecipientMonthlySummary(this.recipientSummaryMonth()).subscribe({
      next: (summary) => {
        if (loadEpoch !== this.recipientSummaryLoadEpoch) {
          return;
        }
        this.recipientMonthlySummary.set(summary);
        this.recipientSummaryError.set(null);
        this.loadingRecipientSummary.set(false);
      },
      error: (err) => {
        if (loadEpoch !== this.recipientSummaryLoadEpoch) {
          return;
        }
        const message = apiErrorDetail(err, 'Не удалось обновить сводку по получателям');
        this.loadingRecipientSummary.set(false);
        this.recipientSummaryError.set(message);
        this.toastService.error('Сводка не обновлена', message);
      }
    });
  }

  private paymentLinkQuery(): {
    page: number;
    size: number;
    status: PaymentStatusFilter;
    search: string;
    source: PaymentLinkListSource;
    from: string;
    to: string;
  } {
    return {
      page: this.paymentPage(),
      size: this.paymentSize(),
      status: this.statusFilter(),
      search: this.search().trim(),
      source: this.paymentSource(),
      from: this.dateFrom(),
      to: this.dateTo()
    };
  }

  private applyPaymentLinksPage(page: AdminPaymentLinksPageResponse): void {
    this.links.set(page.items ?? []);
    this.paymentPage.set(page.page ?? 0);
    this.paymentSize.set(page.size ?? this.paymentSize());
    this.paymentTotalElements.set(page.totalElements ?? 0);
    this.paymentTotalPages.set(page.totalPages ?? 0);
    this.paymentSummary.set(page.summary ?? null);
  }

  private replaceLink(updated: AdminPaymentLinkResponse): void {
    this.links.update((links) => links.map((item) => item.id === updated.id ? updated : item));
  }

  private updateProfilePolicyDraft(profileId: number, patch: Partial<ProfilePolicyDraft>): void {
    this.profilePolicies.update((policies) => ({
      ...policies,
      [profileId]: {
        ...this.policyDraft(profileId),
        ...patch
      }
    }));
  }

  private policyDraft(profileId: number): ProfilePolicyDraft {
    return this.profilePolicies()[profileId] ?? {
      paymentPolicy: 'T_BANK_ONLY',
      manualPaymentType: 'MOBILE_BANK',
      manualPhone: '',
      manualRecipientName: TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL,
      manualPaymentButtonLabel: TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      manualComment: '',
      manualMonthlyLimitRubles: String(TbankPaymentsComponent.DEFAULT_MANUAL_MONTHLY_LIMIT_RUBLES)
    };
  }

  private profileToPolicyDraft(profile: PaymentProfileResponse): ProfilePolicyDraft {
    return {
      paymentPolicy: profile.paymentPolicy ?? 'T_BANK_ONLY',
      manualPaymentType: (profile.manualPaymentType as ManualPaymentType | undefined) ?? 'MOBILE_BANK',
      manualPhone: profile.manualPhone ?? '',
      manualRecipientName: profile.manualRecipientName ?? TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: profile.manualPaymentUrl ?? TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL,
      manualPaymentButtonLabel: profile.manualPaymentButtonLabel ?? TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      manualComment: profile.manualComment ?? '',
      manualMonthlyLimitRubles: String(this.kopecksToRubles(profile.manualMonthlyHardLimitKopecks)
        ?? this.kopecksToRubles(profile.manualMonthlySoftLimitKopecks)
        ?? TbankPaymentsComponent.DEFAULT_MANUAL_MONTHLY_LIMIT_RUBLES)
    };
  }

  private manualLimitKopecksFromDraft(value: string): number {
    const numeric = Number(value);
    return this.rublesToKopecks(Number.isFinite(numeric) ? numeric : null)
      ?? this.rublesToKopecks(TbankPaymentsComponent.DEFAULT_MANUAL_MONTHLY_LIMIT_RUBLES)
      ?? 0;
  }

  private rublesToKopecks(value: number | null | undefined): number | null {
    return value && value > 0 ? Math.round(value * 100) : null;
  }

  private kopecksToRubles(value?: number | null): number | null {
    return value && value > 0 ? value / 100 : null;
  }

  private isPaid(status: string): boolean {
    return status === 'CONFIRMED' || status === 'TEST_CONFIRMED' || status === 'AUTHORIZED';
  }

  private isRefunded(status: string): boolean {
    return status === 'REFUNDED'
      || status === 'PARTIAL_REFUNDED'
      || status === 'REVERSED'
      || status === 'PARTIAL_REVERSED';
  }

  private chatPlatformLabel(platform?: string | null): string {
    switch ((platform ?? '').toUpperCase()) {
      case 'WHATSAPP':
        return 'WhatsApp';
      case 'TELEGRAM':
        return 'Telegram';
      case 'MAX':
        return 'MAX';
      default:
        return 'не настроен';
    }
  }

  private static currentMonthInput(): string {
    const now = new Date();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    return `${now.getFullYear()}-${month}`;
  }
}
