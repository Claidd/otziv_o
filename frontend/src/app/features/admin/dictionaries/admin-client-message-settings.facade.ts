import { signal } from '@angular/core';
import { AbstractControl, FormBuilder, ValidatorFn, Validators } from '@angular/forms';
import type { AdminClientMessageSettingsApi } from '../../../core/admin-client-message-settings.api';
import type {
  AdminClientMessageSettings,
  ClientMessageSettingsRequest,
} from '../../../core/admin-dictionaries.api';
import type { ToastService } from '../../../shared/toast.service';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { DictionaryViewScope } from './dictionary-view-scope';
import { DictionaryEditorSession } from './dictionary-editor-session';

/** The server's own default list (ClientChatAutoIgnoreService.DEFAULT_PHRASES). */
const DEFAULT_AUTO_IGNORE_PHRASES =
  'ок,окей,хорошо,спасибо,спасибо большое,вам спасибо,и вам спасибо,взаимно,благодарю,да,нет,понял,поняла,поняли,принято,договорились,отлично,супер,ясно,ладно,хорошо спасибо,спс';

/** The server refuses the whole settings request when one phrase is longer (normalizedSettingList). */
export const AUTO_IGNORE_PHRASE_MAX_LENGTH = 80;

type SettingsKey = keyof AdminClientMessageSettings;
type FormValues = Record<string, unknown>;

/** How the page names a field, for a field that is not on the open tab. */
const FIELD_LABELS: Partial<Record<SettingsKey, string>> = {
  unansweredFastClickWarningCount: 'Первый порог: закрытий',
  unansweredFastClickWarningSeconds: 'Первый порог: период, секунд',
  unansweredFastClickCriticalCount: 'Второй порог: закрытий',
  unansweredFastClickCriticalSeconds: 'Второй порог: период, секунд',
  unansweredAutoIgnoreMaxLength: 'Сообщение клиента не длиннее, символов',
  unansweredAutoIgnorePhrases: 'Фразы без ответа',
  reviewCheckIntervalDays: 'Интервал проверки отзывов, дней',
  reviewCheckAutoArchiveDays: 'Автоархив проверки, дней',
  clientTextReminderIntervalDays: 'Интервал ожидания текста, дней',
  paymentReminderIntervalDays: 'Интервал оплаты, дней',
  reviewCheckRetryDelayHours: 'Повтор проверки после сбоя, часов',
  paymentInvoiceRetryDelayHours: 'Повтор счета после сбоя, часов',
  transientRetryMinutes: 'Повтор временной ошибки, минут',
  manualControlFailureThreshold: 'В контроль после ошибок',
  manualControlAfterMinutes: 'В контроль после, минут',
  badReviewInvoiceRetryDelayHours: 'Повтор доплаты после сбоя, часов',
  badReviewAutoBanDelayDays: 'Автобан после плохих, дней',
  reviewRecoveryNoticeRetryDelayHours: 'Повтор уведомления, часов',
  paymentOverdueDays: 'Просрочка оплаты, дней',
  archiveReorderMonths: 'Архивное предложение, месяцев',
  archiveReorderJitterDays: 'Разброс отправки, дней',
  archiveOrderRetentionDays: 'Архив закрытых заказов, дней',
  errorProtectionThreshold: 'Порог ошибок',
  errorProtectionWindowMinutes: 'Окно ошибок, минут',
  errorProtectionCooldownMinutes: 'Пауза после срабатывания, минут',
  whatsAppAuthRetryHours: 'Повтор WhatsApp QR, часов',
  whatsAppAuthAlertCooldownHours: 'Алерт менеджеру, часов',
  retentionDays: 'Хранить журнал, дней',
  tickBatchSize: 'Задач за один проход',
  candidateLimit: 'Заказов на проверку за проход',
  dailyLimit: 'Дневной лимит сообщений',
  defaultGapSeconds: 'Общая пауза, секунд',
  whatsAppGapSeconds: 'Пауза WhatsApp, секунд',
  telegramGapSeconds: 'Пауза Telegram, секунд',
  maxGapSeconds: 'Пауза MAX, секунд',
  businessWindows: 'Рабочие окна по Иркутску',
  reviewCheckStatuses: 'Статусы проверки отзывов',
  clientTextReminderStatuses: 'Статусы ожидания текста',
  paymentReminderStatuses: 'Статусы ожидания оплаты',
  paymentOverdueStatuses: 'Статусы просрочки оплаты',
  closedOrderStatuses: 'Закрытые статусы заказов',
  paymentOverdueTargetStatus: 'Целевой статус просрочки',
  archiveCompanyStatus: 'Статус архивной компании',
  archiveInactiveOrderStatuses: 'Неактивные статусы заказов',
  openNextOrderRequestStatuses: 'Открытые статусы заявок',
  reviewLinkBaseUrl: 'Ссылка проверки отзывов',
  reviewReminderText: 'Проверка отзывов',
  clientTextReminderText: 'Ожидание текста клиента',
  publicationStartedText: 'Передача в публикацию',
  publicationProgressReportText: 'Отчет о публикации',
  paymentInstructionSource: 'Источник оплаты',
  paymentReminderText: 'Напоминание об оплате',
  paymentLinkCopyText: 'Текст счёта клиенту',
  paymentSuccessText: 'Успешная оплата',
  reviewRecoveryNoticeText: 'Все отзывы восстановлены',
  archiveOfferText: 'Архивное предложение',
};

function normalizePhrase(value: string | null | undefined): string {
  return (value ?? '').trim().replace(/\s+/g, ' ');
}

function samePhrase(left: string, right: string): boolean {
  return left.toLocaleLowerCase() === right.toLocaleLowerCase();
}

function splitPhrases(value: string | null | undefined): string[] {
  return (value ?? '')
    .split(',')
    .map((item) => normalizePhrase(item))
    .filter(Boolean)
    .filter((item, index, list) => list.findIndex((candidate) => samePhrase(candidate, item)) === index);
}

function plural(count: number, one: string, few: string, many: string): string {
  const tens = Math.abs(count) % 100;
  const units = tens % 10;
  if (tens > 10 && tens < 20) return many;
  if (units === 1) return one;
  if (units >= 2 && units <= 4) return few;
  return many;
}

/** «1 фраза», «3 фразы», «11 фраз». */
export function phraseCountLabel(count: number): string {
  return `${count} ${plural(count, 'фраза', 'фразы', 'фраз')}`;
}

function shortened(text: string, length = 40): string {
  return text.length > length ? `${text.slice(0, length)}…` : text;
}

function sentence(text: string): string {
  return /[.!?…]$/.test(text) ? text : `${text}.`;
}

/**
 * The request sends trimmed texts, and the server refuses an empty one only after it has already written the
 * switches and numbers: text of spaces alone counts as empty here.
 */
const requiredText: ValidatorFn = (control) =>
  typeof control.value !== 'string' || control.value.trim() === '' ? { required: true } : null;

/** Each phrase is checked as the server checks it: before the request, not after a partial save. */
const phraseLengths: ValidatorFn = (control) => {
  const tooLong = splitPhrases(typeof control.value === 'string' ? control.value : '').find(
    (phrase) => phrase.length > AUTO_IGNORE_PHRASE_MAX_LENGTH,
  );
  return tooLong ? { phraseTooLong: { phrase: tooLong, max: AUTO_IGNORE_PHRASE_MAX_LENGTH } } : null;
};

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

  /** The phrase as it was when its edit opened: the edit follows it when the list changes around it. */
  private editingAutoIgnorePhraseOriginal: string | null = null;

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
      2,
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
      [requiredText, Validators.maxLength(500)],
    ],
    reviewCheckStatuses: ['На проверке', [requiredText, Validators.maxLength(500)]],
    clientTextReminderStatuses: ['Новый', [requiredText, Validators.maxLength(500)]],
    paymentReminderStatuses: [
      'Выставлен счет,Напоминание',
      [requiredText, Validators.maxLength(500)],
    ],
    paymentOverdueStatuses: [
      'Выставлен счет,Напоминание',
      [requiredText, Validators.maxLength(500)],
    ],
    closedOrderStatuses: [
      'Оплачено,Архив,Бан,Не оплачено',
      [requiredText, Validators.maxLength(500)],
    ],
    paymentOverdueTargetStatus: ['Не оплачено', [requiredText, Validators.maxLength(500)]],
    archiveCompanyStatus: ['На стопе', [requiredText, Validators.maxLength(500)]],
    archiveInactiveOrderStatuses: [
      'Оплачено,Архив,Бан',
      [requiredText, Validators.maxLength(500)],
    ],
    openNextOrderRequestStatuses: [
      'PENDING,FAILED',
      [requiredText, Validators.maxLength(500)],
    ],
    reviewLinkBaseUrl: ['https://o-ogo.ru', [requiredText, Validators.maxLength(500)]],
    reviewReminderText: ['', [requiredText, Validators.maxLength(500)]],
    clientTextReminderText: ['', [requiredText, Validators.maxLength(500)]],
    publicationStartedText: ['', [requiredText, Validators.maxLength(500)]],
    publicationProgressReportText: ['', [requiredText, Validators.maxLength(500)]],
    paymentInstructionSource: [
      'MANAGER_TEXT' as 'MANAGER_TEXT' | 'TBANK_LINK' | 'BANK_LINK' | 'TOCHKA_LINK',
      [Validators.required],
    ],
    paymentReminderText: ['', [requiredText, Validators.maxLength(500)]],
    paymentLinkCopyText: ['', [requiredText, Validators.maxLength(500)]],
    paymentSuccessText: ['', [requiredText, Validators.maxLength(500)]],
    reviewRecoveryNoticeText: ['', [requiredText, Validators.maxLength(500)]],
    archiveOfferText: ['', [requiredText, Validators.maxLength(500)]],
    unansweredAutoIgnorePhrases: [
      DEFAULT_AUTO_IGNORE_PHRASES,
      [Validators.maxLength(2000), phraseLengths],
    ],
  });

  /**
   * What each field showed before the user could type: the last server values, or what a reset put there. A field
   * that still holds it is untouched and takes fresher server values; any other value is the user's edit.
   */
  private baseValues: FormValues = this.autoresponderForm.getRawValue();

  private readonly numberFields = new Set(
    Object.keys(this.baseValues).filter((key) => typeof this.baseValues[key] === 'number'),
  );

  private readonly editor = new DictionaryEditorSession(this.autoresponderForm);
  constructor(private readonly deps: Deps) {
    // A save that ends after the tab was left and opened again reads the list again (see DictionaryViewScope).
    this.scope = new DictionaryViewScope(deps.isActive, () => this.load());
    // Until the first read the fields hold interface defaults, not the saved settings: nothing may be typed over them.
    // No events: an event would count as an edit and keep the read from filling the form.
    this.autoresponderForm.disable({ emitEvent: false });
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
    this.readSettings(false);
  }

  private readSettings(afterFailedSave: boolean): void {
    this.scope.read('settings', this.deps.api.getClientMessageSettings(), this.loading).subscribe({
      next: (response) => this.applyLoadedSettings(response),
      error: (err) => {
        // After a failed save its message stays on screen: this read only refreshes the overview.
        if (afterFailedSave) return;
        this.fail(err, 'Справочник не загрузился', 'Не удалось загрузить настройки сообщений');
      },
    });
  }

  /**
   * A read never replaces what the user typed: an edited field keeps its value, every other field takes the server
   * value. Before the first read this replaces the interface defaults; later it refreshes untouched fields.
   */
  private applyLoadedSettings(response: AdminClientMessageSettings): void {
    const current: FormValues = this.autoresponderForm.getRawValue();
    const edited = new Set(
      Object.keys(current).filter((key) => !Object.is(current[key], this.baseValues[key])),
    );
    if (edited.size === 0) {
      this.applyClientMessageSettings(response);
      return;
    }
    const server: FormValues = { ...response };
    const fresh: FormValues = {};
    for (const key of Object.keys(current)) {
      if (!edited.has(key) && server[key] !== undefined) fresh[key] = server[key];
    }
    this.autoresponderForm.patchValue(fresh as Partial<AdminClientMessageSettings>);
    this.applyClientMessageSettings(response, false);
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
    if (!phrase || !this.phraseListReady() || this.phraseTooLong(phrase)) {
      return;
    }
    const phrases = this.autoIgnorePhrases();
    if (phrases.some((item) => samePhrase(item, phrase))) {
      this.deps.toast.info('Фраза уже есть', phrase);

      return;
    }
    this.setAutoIgnorePhrases([...phrases, phrase]);
    this.autoIgnorePhraseDraft.set('');
  }

  startEditAutoIgnorePhrase(index: number, phrase: string): void {
    this.editingAutoIgnorePhraseIndex.set(index);
    this.editingAutoIgnorePhraseValue.set(phrase);
    this.editingAutoIgnorePhraseOriginal = phrase;
  }

  saveAutoIgnorePhraseEdit(index: number): void {
    if (!this.phraseListReady()) return;
    const phrases = this.autoIgnorePhrases();
    const target = this.editedPhraseIndex(index, phrases);
    if (target < 0) {
      this.cancelAutoIgnorePhraseEdit();

      return;
    }
    const phrase = this.normalizeAutoIgnorePhrase(this.editingAutoIgnorePhraseValue());
    if (!phrase) {
      this.removeAutoIgnorePhrase(target);

      return;
    }
    if (this.phraseTooLong(phrase)) return;
    const duplicate = phrases.some(
      (item, itemIndex) => itemIndex !== target && samePhrase(item, phrase),
    );
    if (duplicate) {
      this.deps.toast.info('Фраза уже есть', phrase);

      return;
    }
    phrases[target] = phrase;
    this.setAutoIgnorePhrases(phrases);
    this.cancelAutoIgnorePhraseEdit();
  }

  cancelAutoIgnorePhraseEdit(): void {
    this.editingAutoIgnorePhraseIndex.set(null);
    this.editingAutoIgnorePhraseValue.set('');
    this.editingAutoIgnorePhraseOriginal = null;
  }

  removeAutoIgnorePhrase(index: number): void {
    if (!this.phraseListReady()) return;
    const phrases = this.autoIgnorePhrases().filter((_phrase, itemIndex) => itemIndex !== index);
    this.setAutoIgnorePhrases(phrases);
    this.syncEditingPhrase();
  }

  /** Before the first read the list holds interface defaults: a change to it would replace the saved list. */
  private phraseListReady(): boolean {
    if (this.clientMessageSettings() != null) return true;
    this.deps.toast.info('Список ещё загружается', 'Дождитесь загрузки и повторите.');

    return false;
  }

  private phraseTooLong(phrase: string): boolean {
    if (!splitPhrases(phrase).some((item) => item.length > AUTO_IGNORE_PHRASE_MAX_LENGTH)) {
      return false;
    }
    this.deps.toast.info(
      'Фраза слишком длинная',
      `Фраза может быть не длиннее ${AUTO_IGNORE_PHRASE_MAX_LENGTH} символов: сократите её.`,
    );

    return true;
  }

  /** The row of the phrase being edited: its own index, unless the list has changed under it since. */
  private editedPhraseIndex(index: number, phrases: string[]): number {
    const original = this.editingAutoIgnorePhraseOriginal;
    if (original == null) return index >= 0 && index < phrases.length ? index : -1;
    if (index >= 0 && index < phrases.length && samePhrase(phrases[index], original)) return index;

    return phrases.findIndex((item) => samePhrase(item, original));
  }

  /** An open edit follows its phrase when rows above it change, and closes when the phrase is gone. */
  private syncEditingPhrase(): void {
    const original = this.editingAutoIgnorePhraseOriginal;
    if (original == null || this.editingAutoIgnorePhraseIndex() == null) return;
    const index = this.autoIgnorePhrases().findIndex((item) => samePhrase(item, original));
    if (index < 0) {
      this.cancelAutoIgnorePhraseEdit();
    } else {
      this.editingAutoIgnorePhraseIndex.set(index);
    }
  }

  private setAutoIgnorePhrases(phrases: string[]): void {
    this.autoresponderForm.controls.unansweredAutoIgnorePhrases.setValue(
      this.joinAutoIgnorePhrases(phrases),
    );
    this.autoresponderForm.controls.unansweredAutoIgnorePhrases.markAsDirty();
  }

  splitAutoIgnorePhrases(value: string | null | undefined): string[] {
    return splitPhrases(value);
  }

  private joinAutoIgnorePhrases(phrases: string[]): string {
    return phrases

      .map((item) => this.normalizeAutoIgnorePhrase(item))

      .filter(Boolean)

      .join(',');
  }

  private normalizeAutoIgnorePhrase(value: string | null | undefined): string {
    return normalizePhrase(value);
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

      clientTextReminderIntervalDays: settings?.clientTextReminderIntervalDays ?? 2,

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
        '{companyAndFilial}. Опубликован новый текст {progress}.',

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
    this.baseValues = this.autoresponderForm.getRawValue();
    this.autoIgnorePhraseDraft.set('');
    this.cancelAutoIgnorePhraseEdit();
  }

  patchClientMessageMonitorEnabled(enabled: boolean): void {
    const current = this.clientMessageSettings();
    if (current) {
      this.clientMessageSettings.set({ ...current, monitorEnabled: enabled });
    }
    this.autoresponderForm.patchValue({ monitorEnabled: enabled });
    // The monitor switch is already saved on the server: it is not the user's unsaved edit.
    this.baseValues = { ...this.baseValues, monitorEnabled: enabled };
  }

  /** «Фразы без ответа» sends the same full request as the autoresponder, under its own titles. */
  savePhraseDictionary(): void {
    this.saveAutoresponderSettings(
      'Фразы сохранены',
      (settings) => phraseCountLabel(splitPhrases(settings.unansweredAutoIgnorePhrases).length),
      'Фразы не сохранены',
      'Не удалось сохранить фразы',
    );
  }

  saveAutoresponderSettings(
    successTitle = 'Автоответчик сохранен',
    successMessage: (settings: AdminClientMessageSettings) => string = (settings) =>
      settings.workerEnabled ? `лимит ${settings.dailyLimit} в день` : 'сервис выключен',
    failureTitle = 'Автоответчик не сохранен',
    failureFallback = 'Не удалось сохранить автоответчик',
  ): void {
    if (!this.scope.active() || this.saving()) return;
    if (this.clientMessageSettings() == null) {
      // Until the first load the form holds interface defaults: saving them would overwrite every autoresponder setting.
      this.error.set('Настройки ещё не загрузились. Дождитесь загрузки и сохраните снова.');
      return;
    }
    if (this.unappliedPhraseBlocksSave()) return;
    if (this.autoresponderForm.invalid) {
      this.autoresponderForm.markAllAsTouched();
      this.reportInvalidFields(failureTitle);

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

      clientTextReminderIntervalDays: Number(raw.clientTextReminderIntervalDays ?? 2),

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
      // The values the form was loaded with: if another administrator or tab changed the settings since, the server
      // refuses instead of silently writing this form's switches back (an emergency stop must not be undone).
      ...(this.clientMessageSettings()?.settingsVersion
        ? {
            expectedSettingsVersion: this.clientMessageSettings()!.settingsVersion,
            // The monitor switch is outside the version (it has its own toggle): sent as loaded, so an untouched
            // switch in a stale form does not turn the monitor back on or off.
            expectedMonitorEnabled: this.baseValues['monitorEnabled'] === true,
          }
        : {}),
    };
    if (!this.confirmStoppingSends(request) || !this.confirmStartingSends(request)) return;
    const editor = this.editor.capture();
    this.saving.set(true);
    this.error.set(null);
    // Leaving the tab (or the page) during the request still reports how it ended: an emergency stop that failed
    // must not look done. The form is read again on return to the tab.
    this.scope.write(this.deps.api.updateClientMessageSettings(request), this.saving, (outcome) => outcome.failed
      ? this.deps.toast.error(failureTitle, (outcome.error as { status?: number } | null)?.status === 409
        ? sentence(apiErrorMessage(outcome.error, 'Настройки изменились после открытия страницы. Проверьте значения и сохраните снова.'))
        : `${sentence(apiErrorMessage(outcome.error, failureFallback))} Часть изменений могла сохраниться: сверьте значения.`)
      : outcome.value && this.deps.toast.success(successTitle, successMessage(outcome.value))
    ).subscribe({
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

        // A stale form is refused before anything is written (409): the fresh values are read in, typed ones stay.
        // Otherwise the server writes the values one by one and checks some of them last: a refused request may
        // already have changed switches and numbers. Either way the overview is read again.
        const staleForm = (err as { status?: number } | null)?.status === 409;
        const message = staleForm
          ? sentence(apiErrorMessage(err, 'Настройки изменились после открытия страницы. Проверьте значения и сохраните снова.'))
          : `${sentence(apiErrorMessage(err, failureFallback))} Часть изменений могла сохраниться: сверьте значения.`;

        this.error.set(message);

        this.saving.set(false);

        this.deps.toast.error(failureTitle, message);

        this.readSettings(true);
      },
    });
  }

  /** A phrase typed but not added, or an edit not applied, is not part of the request: saving would hide that. */
  private unappliedPhraseBlocksSave(): boolean {
    if (this.editingAutoIgnorePhraseIndex() !== null) {
      this.deps.toast.error(
        'Правка фразы не применена',
        'Нажмите галочку у фразы, чтобы применить правку, или крестик, чтобы отменить. Затем сохраните.',
      );

      return true;
    }
    const draft = this.normalizeAutoIgnorePhrase(this.autoIgnorePhraseDraft());
    if (draft) {
      this.deps.toast.error(
        'Фраза не добавлена',
        `«${shortened(draft)}»: нажмите «+», чтобы добавить её, или очистите поле. Затем сохраните.`,
      );

      return true;
    }

    return false;
  }

  /**
   * Turning off the background queue or sending to clients stops far more than reminders: the owner confirms the
   * list. Only a change from the saved «on» asks, so a later phrase save with sending already off does not.
   */
  /**
   * Turning the background queue or sending to clients back on releases what waited while they were off: the owner
   * confirms it. Only a change from the saved «off» asks.
   */
  private confirmStartingSends(request: ClientMessageSettingsRequest): boolean {
    const saved = this.clientMessageSettings();
    const startsQueue = !!saved && !saved.workerEnabled && request.workerEnabled;
    const startsSending = !!saved && !saved.liveEnabled && request.liveEnabled;
    if ((!startsQueue && !startsSending) || typeof window === 'undefined') return true;
    const lines = [
      startsQueue && startsSending
        ? 'Включить фоновую очередь и отправку клиентам?'
        : startsQueue
          ? 'Включить фоновую очередь?'
          : 'Включить отправку клиентам?',
      'Сообщения, накопленные в очереди за время выключения, начнут уходить клиентам в ближайшие рабочие окна с учётом лимитов.',
    ];
    return window.confirm(lines.join('\n\n'));
  }

  private confirmStoppingSends(request: ClientMessageSettingsRequest): boolean {
    const saved = this.clientMessageSettings();
    const stopsQueue = !!saved?.workerEnabled && !request.workerEnabled;
    const stopsSending = !!saved?.liveEnabled && !request.liveEnabled;
    if ((!stopsQueue && !stopsSending) || typeof window === 'undefined') return true;
    const lines = [
      stopsQueue && stopsSending
        ? 'Выключить фоновую очередь и отправку клиентам?'
        : stopsQueue
          ? 'Выключить фоновую очередь?'
          : 'Выключить отправку клиентам?',
    ];
    if (stopsQueue) {
      lines.push(
        'Остановятся напоминания, повторы после сбоев, автоархив, архивные предложения, контроль просрочки и рассылки предложений.',
      );
    }
    if (stopsSending) {
      lines.push(
        'Клиентам перестанут уходить сообщения автоответчика, счёт после плохого отзыва, общие счета (и ручные отправки тоже), рассылки предложений, сообщения менеджеров из «Контроля» и ответы в WhatsApp.',
      );
    }
    lines.push(
      'Новые маршруты оплаты на специалистов выдаваться не будут. Сообщения из очереди уйдут после включения.',
    );

    return window.confirm(lines.join('\n\n'));
  }

  /** A refused save names the wrong fields with their limits and brings the first one into view. */
  private reportInvalidFields(title: string): void {
    const controls: Record<string, AbstractControl> = this.autoresponderForm.controls;
    const keys = this.inPageOrder(Object.keys(controls).filter((key) => controls[key].invalid));
    if (keys.length === 0) return;
    const problems = keys
      .slice(0, 3)
      .map((key) => `«${this.fieldLabel(key)}»: ${this.fieldProblem(key, controls[key])}.`);
    const rest = keys.length - 3;
    if (rest > 0) problems.push(`Ещё ${rest} ${plural(rest, 'поле', 'поля', 'полей')} с ошибкой.`);
    this.deps.toast.error(title, problems.join(' '));
    this.revealField(keys[0]);
  }

  private fieldProblem(key: string, control: AbstractControl): string {
    const errors = control.errors ?? {};
    const tooLong = errors['phraseTooLong'] as { phrase: string; max: number } | undefined;
    if (tooLong) return `фраза «${shortened(tooLong.phrase)}» длиннее ${tooLong.max} символов`;
    if (errors['required']) return this.numberFields.has(key) ? 'укажите число' : 'заполните поле';
    if (errors['min']) return `не меньше ${errors['min'].min}`;
    if (errors['max']) return `не больше ${errors['max'].max}`;
    if (errors['maxlength']) {
      return `не длиннее ${errors['maxlength'].requiredLength} символов, сейчас ${errors['maxlength'].actualLength}`;
    }

    return 'проверьте значение';
  }

  /** The name next to the field on the page; the fixed name when the field is on the other tab. */
  private fieldLabel(key: string): string {
    const shown = this.fieldElement(key)?.closest('label')?.querySelector('span')?.textContent?.trim();

    return shown || FIELD_LABELS[key as SettingsKey] || 'Поле настроек';
  }

  /** Fields in the order the page shows them; fields of the other tab go last. */
  private inPageOrder(keys: string[]): string[] {
    if (typeof document === 'undefined') return keys;
    const shown = Array.from(document.querySelectorAll('[formcontrolname]'), (element) =>
      element.getAttribute('formcontrolname'),
    );
    const rank = (key: string) => {
      const position = shown.indexOf(key);

      return position < 0 ? shown.length : position;
    };

    return [...keys].sort((left, right) => rank(left) - rank(right));
  }

  /** The first wrong field may sit in a folded block: the block opens and the cursor goes to the field. */
  private revealField(key: string): void {
    const field = this.fieldElement(key);
    if (!field) return;
    const block = field.closest('details');
    if (block) block.open = true;
    field.focus();
  }

  private fieldElement(key: string): HTMLElement | null {
    if (typeof document === 'undefined') return null;

    return document.querySelector<HTMLElement>(`[formcontrolname="${key}"]`);
  }

  applyClientMessageSettings(response: AdminClientMessageSettings, patchDraft = true): void {
    this.clientMessageSettings.set(response);
    if (patchDraft) {
      this.autoresponderForm.patchValue(response);
      this.autoresponderForm.markAsPristine();
    }
    this.rememberServerValues(response);
    if (this.autoresponderForm.disabled) this.autoresponderForm.enable({ emitEvent: false });
    this.syncEditingPhrase();
    this.deps.onSettingsChanged();
  }

  /** Server values become what fields are compared with: a field that differs from them is an unsaved edit. */
  private rememberServerValues(response: AdminClientMessageSettings): void {
    const server: FormValues = { ...response };
    const next: FormValues = { ...this.baseValues };
    for (const key of Object.keys(next)) {
      if (server[key] !== undefined) next[key] = server[key];
    }
    this.baseValues = next;
  }

  private fail(error: unknown, title: string, fallback: string): void {
    const message = apiErrorMessage(error, fallback);
    this.error.set(message);
    this.deps.toast.error(title, message);
  }
}
