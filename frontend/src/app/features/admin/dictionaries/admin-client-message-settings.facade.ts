import { signal } from '@angular/core';
import { FormBuilder, Validators } from '@angular/forms';
import type { AdminClientMessageSettingsApi } from '../../../core/admin-client-message-settings.api';
import type {
  AdminClientMessageSettings,
  ClientMessageSettingsRequest,
} from '../../../core/admin-dictionaries.api';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { DictionaryViewScope } from './dictionary-view-scope';
import { DictionaryEditorSession } from './dictionary-editor-session';

const DEFAULT_AUTO_IGNORE_PHRASES =
  'ок,окей,хорошо,спасибо,спасибо большое,благодарю,да,нет,понял,поняла,поняли,принято,договорились,отлично,супер,ясно,ладно,хорошо спасибо,спс';

type Deps = {
  api: AdminClientMessageSettingsApi;
  toast: Pick<ToastService, 'success' | 'error' | 'info'>;
  isActive: () => boolean;
  onSettingsChanged: () => void;
};

/** Owns the shared message-dictionary/autoresponder form and the exact full settings request. */
export class AdminClientMessageSettingsFacade {
  private readonly fb = new FormBuilder();
  private readonly scope: DictionaryViewScope;
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);

  readonly clientMessageSettings = signal<AdminClientMessageSettings | null>(null);

  readonly autoIgnorePhraseDraft = signal('');

  readonly editingAutoIgnorePhraseIndex = signal<number | null>(null);

  readonly editingAutoIgnorePhraseValue = signal('');

  readonly autoresponderForm = this.fb.nonNullable.group({
    workerEnabled: [true],
    liveEnabled: [true],
    immediateEnabled: [true],
    monitorEnabled: [false],
    reviewCheckEnabled: [true],
    reviewCheckAutoArchiveEnabled: [true],
    clientTextReminderEnabled: [true],
    paymentReminderEnabled: [true],
    badReviewInvoiceEnabled: [true],
    badReviewAutoBanEnabled: [true],
    reviewRecoveryNoticeEnabled: [true],
    paymentOverdueEnabled: [true],
    paymentOverdueLiveEnabled: [false],
    archiveReorderEnabled: [true],
    errorProtectionEnabled: [true],
    unansweredAutoIgnoreEnabled: [true],
    unansweredResolutionEnforcementEnabled: [true],
    unansweredFastClickGuardEnabled: [false],
    unansweredReplyQualityShadowEnabled: [true],
    unansweredFastClickWarningCount: [
      3,
      [Validators.required, Validators.min(3), Validators.max(100)],
    ],
    unansweredFastClickWarningSeconds: [
      10,
      [Validators.required, Validators.min(3), Validators.max(300)],
    ],
    unansweredFastClickCriticalCount: [
      10,
      [Validators.required, Validators.min(3), Validators.max(500)],
    ],
    unansweredFastClickCriticalSeconds: [
      60,
      [Validators.required, Validators.min(3), Validators.max(3600)],
    ],
    reviewCheckIntervalDays: [2, [Validators.required, Validators.min(1), Validators.max(365)]],
    reviewCheckAutoArchiveDays: [
      30,
      [Validators.required, Validators.min(1), Validators.max(3650)],
    ],
    clientTextReminderIntervalDays: [
      3,
      [Validators.required, Validators.min(1), Validators.max(365)],
    ],
    paymentReminderIntervalDays: [2, [Validators.required, Validators.min(1), Validators.max(365)]],
    reviewCheckRetryDelayHours: [2, [Validators.required, Validators.min(1), Validators.max(168)]],
    paymentInvoiceRetryDelayHours: [
      2,
      [Validators.required, Validators.min(1), Validators.max(168)],
    ],
    transientRetryMinutes: [15, [Validators.required, Validators.min(1), Validators.max(1440)]],
    manualControlFailureThreshold: [
      3,
      [Validators.required, Validators.min(1), Validators.max(100)],
    ],
    manualControlAfterMinutes: [60, [Validators.required, Validators.min(1), Validators.max(1440)]],
    badReviewInvoiceRetryDelayHours: [
      2,
      [Validators.required, Validators.min(1), Validators.max(168)],
    ],
    badReviewAutoBanDelayDays: [2, [Validators.required, Validators.min(1), Validators.max(365)]],
    reviewRecoveryNoticeRetryDelayHours: [
      2,
      [Validators.required, Validators.min(1), Validators.max(168)],
    ],
    paymentOverdueDays: [30, [Validators.required, Validators.min(1), Validators.max(365)]],
    archiveReorderMonths: [3, [Validators.required, Validators.min(1), Validators.max(36)]],
    archiveReorderJitterDays: [10, [Validators.required, Validators.min(0), Validators.max(30)]],
    archiveOrderRetentionDays: [90, [Validators.required, Validators.min(1), Validators.max(3650)]],
    errorProtectionThreshold: [20, [Validators.required, Validators.min(1), Validators.max(10000)]],
    errorProtectionWindowMinutes: [
      10,
      [Validators.required, Validators.min(1), Validators.max(1440)],
    ],
    errorProtectionCooldownMinutes: [
      60,
      [Validators.required, Validators.min(1), Validators.max(1440)],
    ],
    whatsAppAuthRetryHours: [2, [Validators.required, Validators.min(1), Validators.max(48)]],
    whatsAppAuthAlertCooldownHours: [
      12,
      [Validators.required, Validators.min(1), Validators.max(168)],
    ],
    retentionDays: [90, [Validators.required, Validators.min(1), Validators.max(3650)]],
    tickBatchSize: [5, [Validators.required, Validators.min(1), Validators.max(100)]],
    candidateLimit: [200, [Validators.required, Validators.min(1), Validators.max(5000)]],
    dailyLimit: [140, [Validators.required, Validators.min(1), Validators.max(5000)]],
    defaultGapSeconds: [180, [Validators.required, Validators.min(30), Validators.max(86400)]],
    whatsAppGapSeconds: [180, [Validators.required, Validators.min(30), Validators.max(86400)]],
    telegramGapSeconds: [90, [Validators.required, Validators.min(30), Validators.max(86400)]],
    maxGapSeconds: [90, [Validators.required, Validators.min(30), Validators.max(86400)]],
    unansweredAutoIgnoreMaxLength: [
      60,
      [Validators.required, Validators.min(1), Validators.max(500)],
    ],
    businessWindows: [
      '10:00-12:00,14:00-17:00,19:00-21:00',
      [Validators.required, Validators.maxLength(500)],
    ],
    reviewCheckStatuses: ['На проверке', [Validators.required, Validators.maxLength(500)]],
    clientTextReminderStatuses: ['Новый', [Validators.required, Validators.maxLength(500)]],
    paymentReminderStatuses: [
      'Выставлен счет,Напоминание',
      [Validators.required, Validators.maxLength(500)],
    ],
    paymentOverdueStatuses: [
      'Выставлен счет,Напоминание',
      [Validators.required, Validators.maxLength(500)],
    ],
    closedOrderStatuses: [
      'Оплачено,Архив,Бан,Не оплачено',
      [Validators.required, Validators.maxLength(500)],
    ],
    paymentOverdueTargetStatus: ['Не оплачено', [Validators.required, Validators.maxLength(500)]],
    archiveCompanyStatus: ['На стопе', [Validators.required, Validators.maxLength(500)]],
    archiveInactiveOrderStatuses: [
      'Оплачено,Архив,Бан',
      [Validators.required, Validators.maxLength(500)],
    ],
    openNextOrderRequestStatuses: [
      'PENDING,FAILED',
      [Validators.required, Validators.maxLength(500)],
    ],
    reviewLinkBaseUrl: ['https://o-ogo.ru', [Validators.required, Validators.maxLength(500)]],
    reviewReminderText: ['', [Validators.required, Validators.maxLength(500)]],
    clientTextReminderText: ['', [Validators.required, Validators.maxLength(500)]],
    publicationStartedText: ['', [Validators.required, Validators.maxLength(500)]],
    publicationProgressReportText: ['', [Validators.required, Validators.maxLength(500)]],
    paymentInstructionSource: [
      'MANAGER_TEXT' as 'MANAGER_TEXT' | 'TBANK_LINK' | 'BANK_LINK' | 'TOCHKA_LINK',
      [Validators.required],
    ],
    paymentReminderText: ['', [Validators.required, Validators.maxLength(500)]],
    paymentLinkCopyText: ['', [Validators.required, Validators.maxLength(500)]],
    paymentSuccessText: ['', [Validators.required, Validators.maxLength(500)]],
    reviewRecoveryNoticeText: ['', [Validators.required, Validators.maxLength(500)]],
    archiveOfferText: ['', [Validators.required, Validators.maxLength(500)]],
    unansweredAutoIgnorePhrases: [DEFAULT_AUTO_IGNORE_PHRASES, [Validators.maxLength(2000)]],
  });

  private readonly editor = new DictionaryEditorSession(this.autoresponderForm);
  constructor(private readonly deps: Deps) {
    this.scope = new DictionaryViewScope(deps.isActive);
  }
  deactivate(): void {
    this.editor.change();
    this.scope.deactivate();
  }
  destroy(): void {
    this.editor.destroy();
    this.scope.destroy();
  }
  load(): void {
    if (!this.scope.active()) return;
    this.error.set(null);
    const editor = this.editor.capture();
    this.scope.read('settings', this.deps.api.getClientMessageSettings(), this.loading).subscribe({
      next: (response) =>
        this.applyClientMessageSettings(
          response,
          this.editor.sameDraft(editor) && !this.autoresponderForm.dirty,
        ),
      error: (err) =>
        this.fail(err, 'Справочник не загрузился', 'Не удалось загрузить настройки сообщений'),
    });
  }

  autoIgnorePhrases(): string[] {
    return this.splitAutoIgnorePhrases(
      this.autoresponderForm.controls.unansweredAutoIgnorePhrases.value,
    );
  }

  updateAutoIgnorePhraseDraft(value: string): void {
    this.autoIgnorePhraseDraft.set(value);
  }

  updateEditingAutoIgnorePhraseValue(value: string): void {
    this.editingAutoIgnorePhraseValue.set(value);
  }

  addAutoIgnorePhrase(): void {
    const phrase = this.normalizeAutoIgnorePhrase(this.autoIgnorePhraseDraft());
    if (!phrase) {
      return;
    }
    const phrases = this.autoIgnorePhrases();
    if (phrases.some((item) => item.toLocaleLowerCase() === phrase.toLocaleLowerCase())) {
      this.deps.toast.info('Фраза уже есть', phrase);

      return;
    }
    this.setAutoIgnorePhrases([...phrases, phrase]);
    this.autoIgnorePhraseDraft.set('');
  }

  startEditAutoIgnorePhrase(index: number, phrase: string): void {
    this.editingAutoIgnorePhraseIndex.set(index);
    this.editingAutoIgnorePhraseValue.set(phrase);
  }

  saveAutoIgnorePhraseEdit(index: number): void {
    const phrase = this.normalizeAutoIgnorePhrase(this.editingAutoIgnorePhraseValue());
    if (!phrase) {
      this.removeAutoIgnorePhrase(index);

      return;
    }
    const phrases = this.autoIgnorePhrases();
    const duplicateIndex = phrases.findIndex(
      (item, itemIndex) =>
        itemIndex !== index && item.toLocaleLowerCase() === phrase.toLocaleLowerCase(),
    );
    if (duplicateIndex >= 0) {
      this.deps.toast.info('Фраза уже есть', phrase);

      return;
    }
    phrases[index] = phrase;
    this.setAutoIgnorePhrases(phrases);
    this.cancelAutoIgnorePhraseEdit();
  }

  cancelAutoIgnorePhraseEdit(): void {
    this.editingAutoIgnorePhraseIndex.set(null);
    this.editingAutoIgnorePhraseValue.set('');
  }

  removeAutoIgnorePhrase(index: number): void {
    const phrases = this.autoIgnorePhrases().filter((_phrase, itemIndex) => itemIndex !== index);
    this.setAutoIgnorePhrases(phrases);
    if (this.editingAutoIgnorePhraseIndex() === index) {
      this.cancelAutoIgnorePhraseEdit();
    }
  }

  private setAutoIgnorePhrases(phrases: string[]): void {
    this.autoresponderForm.controls.unansweredAutoIgnorePhrases.setValue(
      this.joinAutoIgnorePhrases(phrases),
    );
    this.autoresponderForm.controls.unansweredAutoIgnorePhrases.markAsDirty();
  }

  splitAutoIgnorePhrases(value: string | null | undefined): string[] {
    return (value ?? '')

      .split(',')

      .map((item) => this.normalizeAutoIgnorePhrase(item))

      .filter(Boolean)

      .filter(
        (item, index, list) =>
          list.findIndex(
            (candidate) => candidate.toLocaleLowerCase() === item.toLocaleLowerCase(),
          ) === index,
      );
  }

  private joinAutoIgnorePhrases(phrases: string[]): string {
    return phrases

      .map((item) => this.normalizeAutoIgnorePhrase(item))

      .filter(Boolean)

      .join(',');
  }

  private normalizeAutoIgnorePhrase(value: string | null | undefined): string {
    return (value ?? '').trim().replace(/\s+/g, ' ');
  }

  resetAutoresponderForm(): void {
    this.editor.change();
    const settings = this.clientMessageSettings();
    this.autoresponderForm.reset({
      workerEnabled: settings?.workerEnabled ?? true,

      liveEnabled: settings?.liveEnabled ?? true,

      immediateEnabled: settings?.immediateEnabled ?? true,

      monitorEnabled: settings?.monitorEnabled ?? false,

      reviewCheckEnabled: settings?.reviewCheckEnabled ?? true,

      reviewCheckAutoArchiveEnabled: settings?.reviewCheckAutoArchiveEnabled ?? true,

      clientTextReminderEnabled: settings?.clientTextReminderEnabled ?? true,

      paymentReminderEnabled: settings?.paymentReminderEnabled ?? true,

      badReviewInvoiceEnabled: settings?.badReviewInvoiceEnabled ?? true,

      badReviewAutoBanEnabled: settings?.badReviewAutoBanEnabled ?? true,

      reviewRecoveryNoticeEnabled: settings?.reviewRecoveryNoticeEnabled ?? true,

      paymentOverdueEnabled: settings?.paymentOverdueEnabled ?? true,

      paymentOverdueLiveEnabled: settings?.paymentOverdueLiveEnabled ?? false,

      archiveReorderEnabled: settings?.archiveReorderEnabled ?? true,

      errorProtectionEnabled: settings?.errorProtectionEnabled ?? true,

      unansweredAutoIgnoreEnabled: settings?.unansweredAutoIgnoreEnabled ?? true,

      unansweredResolutionEnforcementEnabled:
        settings?.unansweredResolutionEnforcementEnabled ?? true,

      unansweredFastClickGuardEnabled: settings?.unansweredFastClickGuardEnabled ?? false,

      unansweredReplyQualityShadowEnabled: settings?.unansweredReplyQualityShadowEnabled ?? true,

      unansweredFastClickWarningCount: settings?.unansweredFastClickWarningCount ?? 3,

      unansweredFastClickWarningSeconds: settings?.unansweredFastClickWarningSeconds ?? 10,

      unansweredFastClickCriticalCount: settings?.unansweredFastClickCriticalCount ?? 10,

      unansweredFastClickCriticalSeconds: settings?.unansweredFastClickCriticalSeconds ?? 60,

      reviewCheckIntervalDays: settings?.reviewCheckIntervalDays ?? 2,

      reviewCheckAutoArchiveDays: settings?.reviewCheckAutoArchiveDays ?? 30,

      clientTextReminderIntervalDays: settings?.clientTextReminderIntervalDays ?? 3,

      paymentReminderIntervalDays: settings?.paymentReminderIntervalDays ?? 2,

      reviewCheckRetryDelayHours: settings?.reviewCheckRetryDelayHours ?? 2,

      paymentInvoiceRetryDelayHours: settings?.paymentInvoiceRetryDelayHours ?? 2,

      transientRetryMinutes: settings?.transientRetryMinutes ?? 15,

      manualControlFailureThreshold: settings?.manualControlFailureThreshold ?? 3,

      manualControlAfterMinutes: settings?.manualControlAfterMinutes ?? 60,

      badReviewInvoiceRetryDelayHours: settings?.badReviewInvoiceRetryDelayHours ?? 2,

      badReviewAutoBanDelayDays: settings?.badReviewAutoBanDelayDays ?? 2,

      reviewRecoveryNoticeRetryDelayHours: settings?.reviewRecoveryNoticeRetryDelayHours ?? 2,

      paymentOverdueDays: settings?.paymentOverdueDays ?? 30,

      archiveReorderMonths: settings?.archiveReorderMonths ?? 3,

      archiveReorderJitterDays: settings?.archiveReorderJitterDays ?? 10,

      archiveOrderRetentionDays: settings?.archiveOrderRetentionDays ?? 90,

      errorProtectionThreshold: settings?.errorProtectionThreshold ?? 20,

      errorProtectionWindowMinutes: settings?.errorProtectionWindowMinutes ?? 10,

      errorProtectionCooldownMinutes: settings?.errorProtectionCooldownMinutes ?? 60,

      whatsAppAuthRetryHours: settings?.whatsAppAuthRetryHours ?? 2,

      whatsAppAuthAlertCooldownHours: settings?.whatsAppAuthAlertCooldownHours ?? 12,

      retentionDays: settings?.retentionDays ?? 90,

      tickBatchSize: settings?.tickBatchSize ?? 5,

      candidateLimit: settings?.candidateLimit ?? 200,

      dailyLimit: settings?.dailyLimit ?? 140,

      defaultGapSeconds: settings?.defaultGapSeconds ?? 180,

      whatsAppGapSeconds: settings?.whatsAppGapSeconds ?? 180,

      telegramGapSeconds: settings?.telegramGapSeconds ?? 90,

      maxGapSeconds: settings?.maxGapSeconds ?? 90,

      unansweredAutoIgnoreMaxLength: settings?.unansweredAutoIgnoreMaxLength ?? 60,

      businessWindows: settings?.businessWindows ?? '10:00-12:00,14:00-17:00,19:00-21:00',

      reviewCheckStatuses: settings?.reviewCheckStatuses ?? 'На проверке',

      clientTextReminderStatuses: settings?.clientTextReminderStatuses ?? 'Новый',

      paymentReminderStatuses: settings?.paymentReminderStatuses ?? 'Выставлен счет,Напоминание',

      paymentOverdueStatuses: settings?.paymentOverdueStatuses ?? 'Выставлен счет,Напоминание',

      closedOrderStatuses: settings?.closedOrderStatuses ?? 'Оплачено,Архив,Бан,Не оплачено',

      paymentOverdueTargetStatus: settings?.paymentOverdueTargetStatus ?? 'Не оплачено',

      archiveCompanyStatus: settings?.archiveCompanyStatus ?? 'На стопе',

      archiveInactiveOrderStatuses: settings?.archiveInactiveOrderStatuses ?? 'Оплачено,Архив,Бан',

      openNextOrderRequestStatuses: settings?.openNextOrderRequestStatuses ?? 'PENDING,FAILED',

      reviewLinkBaseUrl: settings?.reviewLinkBaseUrl ?? 'https://o-ogo.ru',

      reviewReminderText:
        settings?.reviewReminderText ??
        '{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, проверьте шаблоны отзывов и внесите правки, если они нужны.\n\nСсылка на проверку отзывов: {reviewLink}',

      clientTextReminderText:
        settings?.clientTextReminderText ??
        '{companyAndFilial}\n\nЗдравствуйте! Напоминаем, пожалуйста, пришлите текст или пожелания для отзывов по заказу №{orderId}, чтобы мы могли продолжить работу.',

      publicationStartedText:
        settings?.publicationStartedText ??
        '{companyAndFilial}\n\nСпасибо, правки получили. Отзывы переданы в публикацию. Будем присылать короткие отчёты по мере публикации.',

      publicationProgressReportText:
        settings?.publicationProgressReportText ??
        '{companyAndFilial}. Опубликован новый отзыв {progress}.',

      paymentInstructionSource: settings?.paymentInstructionSource ?? 'MANAGER_TEXT',

      paymentReminderText:
        settings?.paymentReminderText ??
        '{companyAndFilial}\n\n{managerPayText} К оплате: {sum} руб.',

      paymentLinkCopyText:
        settings?.paymentLinkCopyText ??
        '{companyAndFilial}\n\nЗдравствуйте, ваш заказ выполнен. К оплате: {sum} руб.\n\n{paymentInstruction}\n\n{paymentAfterword}',

      paymentSuccessText:
        settings?.paymentSuccessText ??
        'Оплата прошла успешно.\n\nНовый заказ принят в работу.\n{orderLine}{companyLine}Сумма: {sum}\nСтраница оплаты: {paymentPage}\n\n{receiptText}',

      reviewRecoveryNoticeText:
        settings?.reviewRecoveryNoticeText ??
        '{companyAndFilial}\n\nВсе отзывы по заказу №{orderId} восстановлены. Продолжаем работу.',

      archiveOfferText:
        settings?.archiveOfferText ??
        '{company}\n\nЗдравствуйте! Давно не запускали новый заказ. Можем подготовить новую аккуратную серию отзывов и обновить карточку компании. Если актуально, напишите, пожалуйста, сколько отзывов нужно в этот раз?',

      unansweredAutoIgnorePhrases:
        settings?.unansweredAutoIgnorePhrases ?? DEFAULT_AUTO_IGNORE_PHRASES,
    });
    this.autoIgnorePhraseDraft.set('');
    this.editingAutoIgnorePhraseIndex.set(null);
    this.editingAutoIgnorePhraseValue.set('');
  }

  patchClientMessageMonitorEnabled(enabled: boolean): void {
    const current = this.clientMessageSettings();
    if (current) {
      this.clientMessageSettings.set({ ...current, monitorEnabled: enabled });
    }
    this.autoresponderForm.patchValue({ monitorEnabled: enabled });
  }

  saveAutoresponderSettings(
    successTitle = 'Автоответчик сохранен',
    successMessage: (settings: AdminClientMessageSettings) => string = (settings) =>
      settings.workerEnabled ? `лимит ${settings.dailyLimit} в день` : 'сервис выключен',
  ): void {
    if (!this.scope.active() || this.saving()) return;
    if (this.autoresponderForm.invalid) {
      this.autoresponderForm.markAllAsTouched();

      return;
    }
    const raw = this.autoresponderForm.getRawValue();
    const request: ClientMessageSettingsRequest = {
      workerEnabled: raw.workerEnabled,

      liveEnabled: raw.liveEnabled,

      immediateEnabled: raw.immediateEnabled,

      monitorEnabled: raw.monitorEnabled,

      reviewCheckEnabled: raw.reviewCheckEnabled,

      reviewCheckAutoArchiveEnabled: raw.reviewCheckAutoArchiveEnabled,

      clientTextReminderEnabled: raw.clientTextReminderEnabled,

      paymentReminderEnabled: raw.paymentReminderEnabled,

      badReviewInvoiceEnabled: raw.badReviewInvoiceEnabled,

      badReviewAutoBanEnabled: raw.badReviewAutoBanEnabled,

      reviewRecoveryNoticeEnabled: raw.reviewRecoveryNoticeEnabled,

      paymentOverdueEnabled: raw.paymentOverdueEnabled,

      paymentOverdueLiveEnabled: raw.paymentOverdueLiveEnabled,

      archiveReorderEnabled: raw.archiveReorderEnabled,

      errorProtectionEnabled: raw.errorProtectionEnabled,

      unansweredAutoIgnoreEnabled: raw.unansweredAutoIgnoreEnabled,

      unansweredResolutionEnforcementEnabled: raw.unansweredResolutionEnforcementEnabled,

      unansweredFastClickGuardEnabled: raw.unansweredFastClickGuardEnabled,

      unansweredReplyQualityShadowEnabled: raw.unansweredReplyQualityShadowEnabled,

      unansweredFastClickWarningCount: Number(raw.unansweredFastClickWarningCount ?? 3),

      unansweredFastClickWarningSeconds: Number(raw.unansweredFastClickWarningSeconds ?? 10),

      unansweredFastClickCriticalCount: Number(raw.unansweredFastClickCriticalCount ?? 10),

      unansweredFastClickCriticalSeconds: Number(raw.unansweredFastClickCriticalSeconds ?? 60),

      reviewCheckIntervalDays: Number(raw.reviewCheckIntervalDays ?? 2),

      reviewCheckAutoArchiveDays: Number(raw.reviewCheckAutoArchiveDays ?? 30),

      clientTextReminderIntervalDays: Number(raw.clientTextReminderIntervalDays ?? 3),

      paymentReminderIntervalDays: Number(raw.paymentReminderIntervalDays ?? 2),

      reviewCheckRetryDelayHours: Number(raw.reviewCheckRetryDelayHours ?? 2),

      paymentInvoiceRetryDelayHours: Number(raw.paymentInvoiceRetryDelayHours ?? 2),

      transientRetryMinutes: Number(raw.transientRetryMinutes ?? 15),

      manualControlFailureThreshold: Number(raw.manualControlFailureThreshold ?? 3),

      manualControlAfterMinutes: Number(raw.manualControlAfterMinutes ?? 60),

      badReviewInvoiceRetryDelayHours: Number(raw.badReviewInvoiceRetryDelayHours ?? 2),

      badReviewAutoBanDelayDays: Number(raw.badReviewAutoBanDelayDays ?? 2),

      reviewRecoveryNoticeRetryDelayHours: Number(raw.reviewRecoveryNoticeRetryDelayHours ?? 2),

      paymentOverdueDays: Number(raw.paymentOverdueDays ?? 30),

      archiveReorderMonths: Number(raw.archiveReorderMonths ?? 3),

      archiveReorderJitterDays: Number(raw.archiveReorderJitterDays ?? 10),

      archiveOrderRetentionDays: Number(raw.archiveOrderRetentionDays ?? 90),

      errorProtectionThreshold: Number(raw.errorProtectionThreshold ?? 20),

      errorProtectionWindowMinutes: Number(raw.errorProtectionWindowMinutes ?? 10),

      errorProtectionCooldownMinutes: Number(raw.errorProtectionCooldownMinutes ?? 60),

      whatsAppAuthRetryHours: Number(raw.whatsAppAuthRetryHours ?? 2),

      whatsAppAuthAlertCooldownHours: Number(raw.whatsAppAuthAlertCooldownHours ?? 12),

      retentionDays: Number(raw.retentionDays ?? 90),

      tickBatchSize: Number(raw.tickBatchSize ?? 5),

      candidateLimit: Number(raw.candidateLimit ?? 200),

      dailyLimit: Number(raw.dailyLimit ?? 140),

      defaultGapSeconds: Number(raw.defaultGapSeconds ?? 180),

      whatsAppGapSeconds: Number(raw.whatsAppGapSeconds ?? 180),

      telegramGapSeconds: Number(raw.telegramGapSeconds ?? 90),

      maxGapSeconds: Number(raw.maxGapSeconds ?? 90),

      unansweredAutoIgnoreMaxLength: Number(raw.unansweredAutoIgnoreMaxLength ?? 60),

      businessWindows: raw.businessWindows.trim(),

      reviewCheckStatuses: raw.reviewCheckStatuses.trim(),

      clientTextReminderStatuses: raw.clientTextReminderStatuses.trim(),

      paymentReminderStatuses: raw.paymentReminderStatuses.trim(),

      paymentOverdueStatuses: raw.paymentOverdueStatuses.trim(),

      closedOrderStatuses: raw.closedOrderStatuses.trim(),

      paymentOverdueTargetStatus: raw.paymentOverdueTargetStatus.trim(),

      archiveCompanyStatus: raw.archiveCompanyStatus.trim(),

      archiveInactiveOrderStatuses: raw.archiveInactiveOrderStatuses.trim(),

      openNextOrderRequestStatuses: raw.openNextOrderRequestStatuses.trim(),

      reviewLinkBaseUrl: raw.reviewLinkBaseUrl.trim(),

      reviewReminderText: raw.reviewReminderText.trim(),

      clientTextReminderText: raw.clientTextReminderText.trim(),

      publicationStartedText: raw.publicationStartedText.trim(),

      publicationProgressReportText: raw.publicationProgressReportText.trim(),

      paymentInstructionSource: raw.paymentInstructionSource,

      paymentReminderText: raw.paymentReminderText.trim(),

      paymentLinkCopyText: raw.paymentLinkCopyText.trim(),

      paymentSuccessText: raw.paymentSuccessText.trim(),

      reviewRecoveryNoticeText: raw.reviewRecoveryNoticeText.trim(),

      archiveOfferText: raw.archiveOfferText.trim(),

      unansweredAutoIgnorePhrases: this.joinAutoIgnorePhrases(
        this.splitAutoIgnorePhrases(raw.unansweredAutoIgnorePhrases),
      ),
    };
    const editor = this.editor.capture();
    this.saving.set(true);
    this.error.set(null);
    this.scope.write(this.deps.api.updateClientMessageSettings(request), this.saving).subscribe({
      next: (settings) => {
        this.saving.set(false);

        if (!this.editor.sameSelection(editor)) return;
        this.applyClientMessageSettings(settings, this.editor.sameDraft(editor));

        this.deps.toast.success(
          successTitle,

          successMessage(settings),
        );
      },

      error: (err) => {
        if (!this.editor.sameSelection(editor)) return;

        const message = apiErrorMessage(err, 'Не удалось сохранить автоответчик');

        this.error.set(message);

        this.saving.set(false);

        this.deps.toast.error('Автоответчик не сохранен', message);
      },
    });
  }
  applyClientMessageSettings(response: AdminClientMessageSettings, patchDraft = true): void {
    this.clientMessageSettings.set(response);
    if (patchDraft) {
      this.autoresponderForm.patchValue(response);
      this.autoresponderForm.markAsPristine();
    }
    this.deps.onSettingsChanged();
  }
  private fail(error: unknown, title: string, fallback: string): void {
    const message = apiErrorMessage(error, fallback);
    this.error.set(message);
    this.deps.toast.error(title, message);
  }
}
