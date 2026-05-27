import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin, switchMap } from 'rxjs';
import { PaymentsApi } from '../../../core/payments.api';
import type {
  AdminPaymentLinkResponse,
  ManualPaymentTaskResponse,
  ManualPaymentTaskStatus,
  ManualPaymentType,
  ManagerPaymentProfileResponse,
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
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';

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
  manualMonthlyLimitRubles: string;
};

type StatusFilterOption = {
  key: PaymentStatusFilter;
  label: string;
  icon: string;
};

@Component({
  selector: 'app-tbank-payments',
  imports: [AdminLayoutComponent, DatePipe, DecimalPipe, FormsModule, LoadErrorCardComponent, RouterLink],
  templateUrl: './tbank-payments.component.html',
  styleUrl: './tbank-payments.component.scss'
})
export class TbankPaymentsComponent {
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
  readonly adminTaskManagerId = signal<number | null>(null);
  readonly adminTaskPaymentType = signal<ManualPaymentType>('MOBILE_BANK');
  readonly adminTaskPhone = signal('');
  readonly adminTaskRecipient = signal(TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME);
  readonly adminTaskPaymentUrl = signal(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL);
  readonly adminTaskPaymentButtonLabel = signal(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
  readonly adminTaskAmountRubles = signal('');
  readonly adminTaskComment = signal('');
  readonly profileAssignments = signal<Record<number, number | null>>({});
  readonly profilePolicies = signal<Record<number, ProfilePolicyDraft>>({});
  readonly runtimeSettings = signal<TbankRuntimeSettings | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutatingId = signal<number | null>(null);
  readonly mutatingTaskId = signal<number | null>(null);
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
    const search = this.search().trim().toLowerCase();
    const from = this.dateFrom();
    const to = this.dateTo();
    const filter = this.statusFilter();
    return this.links().filter((link) => {
      return this.matchesSearch(link, search)
        && this.matchesStatusFilter(link, filter)
        && this.matchesDateRange(link, from, to);
    });
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

  readonly canCreateManualTask = computed(() => {
    const hasTarget = this.adminTaskPaymentType() === 'MOBILE_BANK'
      ? Boolean(this.adminTaskPhone().trim()) && Boolean(this.adminTaskRecipient().trim())
      : Boolean(this.adminTaskPaymentUrl().trim()) && Boolean(this.adminTaskRecipient().trim());
    return !this.savingManualTask()
      && this.adminTaskManagerId() != null
      && hasTarget
      && this.adminTaskTargetKopecks() > 0;
  });

  readonly tbankClientPaymentEnabled = computed(() => {
    return this.runtimeSettings()?.clientTbankEnabled ?? false;
  });

  readonly tbankReadyForClientPayments = computed(() => {
    const status = this.status();
    const settings = this.runtimeSettings();
    return Boolean(settings?.tbankEnabled
      && settings.paymentLinksEnabled
      && settings.managerUiEnabled
      && status?.hasCredentials);
  });

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
        return 'На странице оплаты доступна только форма банка: карта, T-Pay и способы, включенные в T-Bank.';
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
        return 'В режиме "СБП + Pay" сейчас будет только СБП. Включите T-Pay, SberPay или Mir Pay ниже.';
      }
      return `На /pay показываем СБП и быстрые способы: ${methods.join(', ')}. Карточную кнопку не показываем.`;
    }
    if (!this.bankPaymentBlockEnabled()) {
      if (!methods.length) {
        return 'Форма банка скрыта режимом "Только СБП". Быстрые методы появятся после выбора режима с банковским блоком.';
      }
      return `Сейчас выбран режим "Только СБП", поэтому ${methods.join(', ')} не показывается на /pay. Выберите "СБП + карта", "СБП + Pay" или "Только банк".`;
    }
    if (!methods.length) {
      return 'В блоке формы банка показываем только оплату картой. Быстрые методы можно включить здесь после включения в T-Bank.';
    }
    return `В блоке формы банка показываем: ${methods.join(', ')}.`;
  });

  readonly launchStateTitle = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings) {
      return 'Настройки загружаются';
    }
    if (settings.runtimeMode === 'TEST') {
      return 'Тестовый контур';
    }
    if (settings.paymentInstructionSource !== 'TBANK_LINK') {
      return 'Боевой терминал, клиенты на Альфа';
    }
    if (!settings.applyConfirmedPayments) {
      return 'T-Bank клиентам, заказы вручную';
    }
    return 'T-Bank полностью включен';
  });

  readonly launchStateDescription = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings) {
      return 'Получаю состояние из backend.';
    }
    if (settings.runtimeMode === 'TEST') {
      return 'Можно проверять ссылки и возвраты. Клиентам остаются старые счета, заказы не переводятся в оплату.';
    }
    if (settings.paymentInstructionSource !== 'TBANK_LINK') {
      return 'Рабочие терминалы готовы для ручных тестов, но автоответчик продолжает отправлять старый текст/Альфа.';
    }
    if (!settings.applyConfirmedPayments) {
      return 'Клиенты получают ссылки T-Bank, но подтвержденные платежи только попадают в журнал.';
    }
    return 'Клиенты получают ссылки T-Bank, webhook переводит заказ в оплату и запоминает e-mail плательщика.';
  });

  readonly launchStateClass = computed(() => {
    const settings = this.runtimeSettings();
    if (!settings || settings.runtimeMode === 'TEST') {
      return 'launch-summary test';
    }
    if (settings.paymentInstructionSource === 'TBANK_LINK' && settings.applyConfirmedPayments) {
      return 'launch-summary live';
    }
    return 'launch-summary staged';
  });

  readonly metrics = computed<PaymentMetric[]>(() => {
    const links = this.links();
    const filtered = this.filteredLinks();
    const status = this.status();
    const paid = links.filter((link) => this.isPaid(link.status)).length;
    const refunded = links.filter((link) => this.isRefunded(link.status) || link.status === 'CANCELED').length;
    const rejected = links.filter((link) => link.status === 'REJECTED' || link.status === 'FAILED').length;
    const manualPending = this.manualPendingLinks().length;
    const confirmedLinks = links.filter((link) => link.status === 'CONFIRMED');
    const notificationsSent = confirmedLinks.filter((link) => Boolean(link.paymentSuccessNotifiedAt)).length;
    const notificationErrors = confirmedLinks.filter((link) => !link.paymentSuccessNotifiedAt && Boolean(link.paymentSuccessNotificationError)).length;
    return [
      { label: 'Показано', value: `${filtered.length}/${links.length}`, icon: 'filter_list', tone: 'blue' },
      { label: 'Оплачено', value: paid, icon: 'check_circle', tone: 'green' },
      { label: 'Ручные ждут', value: manualPending, icon: 'phone_iphone', tone: manualPending ? 'yellow' : 'gray' },
      { label: 'Уведомления', value: confirmedLinks.length ? `${notificationsSent}/${confirmedLinks.length}` : 0, icon: notificationErrors ? 'sms_failed' : 'mark_chat_read', tone: notificationErrors ? 'red' : notificationsSent ? 'green' : 'gray' },
      { label: 'Можно вернуть', value: links.filter((link) => link.refundable).length, icon: 'undo', tone: 'yellow' },
      { label: 'Возвращено', value: refunded, icon: 'assignment_return', tone: 'green' },
      { label: 'Ошибки', value: rejected, icon: 'priority_high', tone: rejected ? 'red' : 'gray' },
      { label: 'Источник счетов', value: this.tbankClientPaymentEnabled() ? 'T-Bank' : 'Текст', icon: 'payments', tone: this.tbankClientPaymentEnabled() ? 'green' : 'gray' },
      { label: 'Режим', value: this.activeRuntimeMode() === 'TEST' ? 'Тестовый' : 'Боевой', icon: this.activeRuntimeMode() === 'TEST' ? 'science' : 'verified', tone: this.activeRuntimeMode() === 'TEST' ? 'yellow' : 'green' }
    ];
  });

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    forkJoin({
      status: this.paymentsApi.getTbankStatus(),
      links: this.paymentsApi.getAdminTbankPaymentLinks(),
      profiles: this.paymentsApi.getAdminTbankPaymentProfiles(),
      manualTasks: this.paymentsApi.getAdminManualPaymentTasks(),
      runtimeSettings: this.paymentsApi.getAdminTbankRuntimeSettings()
    }).subscribe({
      next: ({ status, links, profiles, manualTasks, runtimeSettings }) => {
        this.status.set(status);
        this.links.set(links);
        this.manualTasks.set(manualTasks ?? []);
        this.runtimeSettings.set(runtimeSettings);
        this.applyProfilesState(profiles.profiles, profiles.managers);
        this.loading.set(false);
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось загрузить T-Bank платежи');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('T-Bank платежи не загрузились', message);
      }
    });
  }

  setRuntimeMode(mode: TbankRuntimeMode): void {
    const current = this.runtimeSettings();
    if (!current || this.savingRuntimeSettings() || current.runtimeMode === mode) {
      return;
    }

    if (mode === 'LIVE') {
      const confirmed = window.confirm(
        'Переключить T-Bank на рабочие терминалы? Клиентам ссылки T-Bank не уйдут, пока источник счетов остается «Альфа / текст».'
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
        this.toastService.error('Сначала включите боевой контур', 'В тестовом режиме клиентам нельзя отправлять ссылки T-Bank.');
        return;
      }
      if (!this.tbankReadyForClientPayments()) {
        this.toastService.error('T-Bank еще не готов', 'Проверьте API, создание ссылок, UI менеджера и секреты терминалов.');
        return;
      }
      const confirmed = window.confirm(
        'Отправлять клиентам ссылки T-Bank вместо старого текста/Альфа? Рекомендую включать после тестового реального платежа и чека.'
      );
      if (!confirmed) {
        return;
      }
    }
    this.saveRuntimeSettings(
      { paymentInstructionSource: source },
      source === 'TBANK_LINK' ? 'Клиентские счета переключены на T-Bank' : 'Клиентские счета вернулись на Альфа / текст'
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
    this.saveRuntimeSettings(patch, 'Настройка T-Bank сохранена');
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
  }

  setStatusFilter(filter: PaymentStatusFilter): void {
    this.statusFilter.set(filter);
  }

  setDateFrom(value: string): void {
    this.dateFrom.set(value ?? '');
  }

  setDateTo(value: string): void {
    this.dateTo.set(value ?? '');
  }

  resetFilters(): void {
    this.search.set('');
    this.statusFilter.set('all');
    this.dateFrom.set('');
    this.dateTo.set('');
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
    this.paymentsApi.updateAdminTbankPaymentProfileAssignments(assignments).subscribe({
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
    if (value === 'EXTERNAL_LINK' && !this.policyDraft(profileId).manualPaymentUrl.trim()) {
      patch.manualPaymentUrl = TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL;
    }
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
        manualPaymentUrl: draft.manualPaymentUrl.trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL,
        manualPaymentButtonLabel: draft.manualPaymentButtonLabel.trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        manualMonthlySoftLimitKopecks: manualMonthlyLimitKopecks,
        manualMonthlyHardLimitKopecks: manualMonthlyLimitKopecks
      };
    });
    this.savingProfilePolicies.set(true);
    this.paymentsApi.updateAdminPaymentProfilePolicies(request).subscribe({
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
        manualPaymentUrl: draft.manualPaymentUrl.trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL,
        manualPaymentButtonLabel: draft.manualPaymentButtonLabel.trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
        manualMonthlySoftLimitKopecks: manualMonthlyLimitKopecks,
        manualMonthlyHardLimitKopecks: manualMonthlyLimitKopecks
      };
    });
    const assignments = this.managerProfiles().map((manager) => ({
      managerId: manager.managerId,
      paymentProfileId: this.selectedProfileId(manager)
    }));

    this.savingProfilePolicies.set(true);
    this.savingProfiles.set(true);
    this.paymentsApi.updateAdminPaymentProfilePolicies(policyRequest).pipe(
      switchMap(() => this.paymentsApi.updateAdminTbankPaymentProfileAssignments(assignments))
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
        this.toastService.error('Маршрутизация не сохранена', message);
      }
    });
  }

  cancel(link: AdminPaymentLinkResponse): void {
    if (!link.refundable || this.mutatingId()) {
      return;
    }

    const confirmed = window.confirm(`Вернуть платеж T-Bank ${link.tbankPaymentId} на сумму ${link.amount} руб.?`);
    if (!confirmed) {
      return;
    }

    this.mutatingId.set(link.id);
    this.paymentsApi.cancelAdminTbankPaymentLink(link.id).subscribe({
      next: (updated) => {
        this.links.update((links) => links.map((item) => item.id === updated.id ? updated : item));
        this.mutatingId.set(null);
        this.toastService.success('Возврат отправлен', `Статус: ${this.statusLabel(updated.status)}`);
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
        this.links.update((links) => links.map((item) => item.id === updated.id ? updated : item));
        this.mutatingId.set(null);
        this.toastService.success('Ручная оплата подтверждена', `Статус: ${this.statusLabel(updated.status)}`);
        this.loadProfilesOnly();
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось подтвердить ручную оплату');
        this.mutatingId.set(null);
        this.toastService.error('Оплата не подтверждена', message);
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
        this.links.update((links) => links.map((item) => item.id === updated.id ? updated : item));
        this.mutatingId.set(null);
        this.toastService.success('Статус чека обновлен');
        this.loadProfilesOnly();
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось отметить чек');
        this.mutatingId.set(null);
        this.toastService.error('Чек не обновлен', message);
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

  setAdminTaskManagerId(value: number | string | null): void {
    const id = value == null || value === '' ? NaN : Number(value);
    this.adminTaskManagerId.set(Number.isFinite(id) && id > 0 ? id : null);
  }

  setAdminTaskPaymentType(value: ManualPaymentType): void {
    this.adminTaskPaymentType.set(value);
    if (value === 'EXTERNAL_LINK' && !this.adminTaskPaymentUrl().trim()) {
      this.adminTaskPaymentUrl.set(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL);
    }
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

  setAdminTaskPaymentUrl(value: string | null): void {
    this.adminTaskPaymentUrl.set(value ?? '');
  }

  setAdminTaskPaymentButtonLabel(value: string | null): void {
    this.adminTaskPaymentButtonLabel.set(value ?? '');
  }

  setAdminTaskAmountRubles(value: string | number | null): void {
    this.adminTaskAmountRubles.set(value == null ? '' : String(value));
  }

  setAdminTaskComment(value: string | null): void {
    this.adminTaskComment.set(value ?? '');
  }

  createManualTask(): void {
    if (!this.canCreateManualTask()) {
      return;
    }
    this.savingManualTask.set(true);
    this.paymentsApi.createAdminManualPaymentTask({
      managerId: this.adminTaskManagerId(),
      manualPaymentType: this.adminTaskPaymentType(),
      manualPhone: this.adminTaskPhone().trim(),
      manualRecipientName: this.adminTaskRecipient().trim() || TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME,
      manualPaymentUrl: this.adminTaskPaymentUrl().trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL,
      manualPaymentButtonLabel: this.adminTaskPaymentButtonLabel().trim() || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL,
      targetAmountKopecks: this.adminTaskTargetKopecks(),
      comment: this.adminTaskComment().trim() || null
    }).subscribe({
      next: (task) => {
        this.manualTasks.update((tasks) => [task, ...tasks]);
        this.adminTaskPhone.set('');
        this.adminTaskRecipient.set(TbankPaymentsComponent.DEFAULT_MANUAL_RECIPIENT_NAME);
        this.adminTaskPaymentType.set('MOBILE_BANK');
        this.adminTaskPaymentUrl.set(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL);
        this.adminTaskPaymentButtonLabel.set(TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_BUTTON_LABEL);
        this.adminTaskAmountRubles.set('');
        this.adminTaskComment.set('');
        this.savingManualTask.set(false);
        this.toastService.success('Ручное задание создано');
      },
      error: (err) => {
        const message = apiErrorDetail(err, 'Не удалось создать ручное задание');
        this.savingManualTask.set(false);
        this.toastService.error('Задание не создано', message);
      }
    });
  }

  copy(value: string | null | undefined, key: string): void {
    const text = value?.trim();
    if (!text) {
      return;
    }

    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(key);
      window.setTimeout(() => {
        if (this.copied() === key) {
          this.copied.set(null);
        }
      }, 1600);
      this.toastService.success('Скопировано');
    });
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
      FAILED: 'Ошибка'
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
    if (status === 'REJECTED' || status === 'FAILED' || status === 'EXPIRED') {
      return 'status-pill failed';
    }
    return 'status-pill neutral';
  }

  paymentTitle(link: AdminPaymentLinkResponse): string {
    return link.companyTitle || link.filialTitle || `Заказ ${link.orderId ?? '-'}`;
  }

  paymentSubtitle(link: AdminPaymentLinkResponse): string {
    const parts = [link.filialTitle, link.description].filter(Boolean);
    return parts.join(' - ') || 'T-Bank';
  }

  paymentMethodLabel(link: AdminPaymentLinkResponse): string {
    if (this.isManualPayment(link)) {
      return this.isExternalManualPayment(link) ? 'Ссылка Альфа' : 'Телефон';
    }
    return link.paymentMethod === 'SBP_QR' ? 'СБП' : 'Форма банка';
  }

  manualTaskTargetLine(task: ManualPaymentTaskResponse): string {
    if (this.isExternalManualTask(task)) {
      return `${task.manualPaymentUrl || TbankPaymentsComponent.DEFAULT_MANUAL_PAYMENT_URL} · ${task.managerTitle || task.username}`;
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
    return link.receiptStatus === 'MARKED' ? 'Чек отмечен' : 'Чек ожидает';
  }

  manualSourceLabel(link: AdminPaymentLinkResponse): string {
    if (link.manualSource === 'MANUAL_TASK') {
      return link.manualTaskTitle ? `Задание: ${link.manualTaskTitle}` : 'Ручное задание';
    }
    if (this.isManualPayment(link)) {
      return 'Лимит профиля';
    }
    return '';
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
    return task.manualPaymentType === 'EXTERNAL_LINK';
  }

  canConfirmManual(link: AdminPaymentLinkResponse): boolean {
    return this.isManualPayment(link)
      && (link.status === 'WAITING_MANUAL_PAYMENT' || link.status === 'MANUAL_REPORTED');
  }

  canMarkManualReceipt(link: AdminPaymentLinkResponse): boolean {
    return this.isManualPayment(link) && link.status === 'CONFIRMED' && link.receiptStatus !== 'MARKED';
  }

  profilePolicy(profileId: number): ProfilePolicyDraft {
    return this.policyDraft(profileId);
  }

  profilePolicyLabel(profile: PaymentProfileResponse): string {
    const policy = this.policyDraft(profile.id).paymentPolicy;
    if (policy !== 'MANUAL_UNTIL_LIMIT_THEN_TBANK') {
      return 'Только T-Bank';
    }
    return this.policyDraft(profile.id).manualPaymentType === 'EXTERNAL_LINK'
      ? 'Ссылка до лимита'
      : 'Телефон до лимита';
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

  totalAmount(links: AdminPaymentLinkResponse[]): number {
    return links.reduce((sum, link) => sum + Number(link.amount || 0), 0);
  }

  rowMode(link: AdminPaymentLinkResponse): string {
    if (this.isPaid(link.status)) {
      return 'paid';
    }
    if (this.isRefunded(link.status) || link.status === 'CANCELED') {
      return 'refunded';
    }
    if (this.isManualPayment(link) && (link.status === 'WAITING_MANUAL_PAYMENT' || link.status === 'MANUAL_REPORTED')) {
      return 'manual';
    }
    if (link.status === 'REJECTED' || link.status === 'FAILED' || link.status === 'EXPIRED') {
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
    optimistic.clientTbankEnabled = optimistic.paymentInstructionSource === 'TBANK_LINK';
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
        this.toastService.error('Настройки T-Bank не сохранены', message);
      }
    });
  }

  private adminTaskTargetKopecks(): number {
    const value = Number(this.adminTaskAmountRubles());
    return Number.isFinite(value) && value > 0 ? Math.round(value * 100) : 0;
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
        return link.status === 'REJECTED' || link.status === 'FAILED' || link.status === 'EXPIRED';
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

  private loadProfilesOnly(): void {
    this.paymentsApi.getAdminTbankPaymentProfiles().subscribe({
      next: (profiles) => this.applyProfilesState(profiles.profiles, profiles.managers),
      error: () => {}
    });
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
}
