import { Component, Input, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  BAD_REVIEW_ORDER_READY_SOURCE,
  BAD_REVIEW_TASK_SOURCE,
  PersonalReminder,
  PersonalReminderInput,
  PersonalReminderMode,
  PersonalRemindersService,
  REVIEW_RECOVERY_BATCH_SOURCE
} from './personal-reminders.service';
import { apiErrorMessage } from './api-error-message';
import { copyTextToClipboard } from './clipboard-copy';
import { ToastService } from './toast.service';
import type { ToastAction } from './toast.service';
import { ManagerApi } from '../core/manager.api';

type PersonalReminderView = 'full' | 'alert' | 'list';

type PersonalReminderDraft = {
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAtLocal: string;
  timerMinutes: number;
};

const CHAT_LINE_PATTERN = /(^|\n)\s*Чат:\s*(https?:\/\/\S+|tel:\S+)/i;

@Component({
  selector: 'app-personal-reminders',
  imports: [FormsModule],
  templateUrl: './personal-reminders.component.html',
  styleUrl: './personal-reminders.component.scss'
})
export class PersonalRemindersComponent implements OnInit {
  private readonly remindersService = inject(PersonalRemindersService);
  private readonly toastService = inject(ToastService);
  private readonly managerApi = inject(ManagerApi);

  readonly view = signal<PersonalReminderView>('full');
  readonly initialized = signal(false);
  readonly formOpen = signal(false);
  readonly editingId = signal<number | null>(null);
  readonly expandedReminderId = signal<number | null>(null);
  readonly saving = signal(false);
  readonly notifyingRecoveryReminderId = signal<number | null>(null);
  readonly copyingPaymentReminderId = signal<number | null>(null);
  readonly banningBadReviewReminderId = signal<number | null>(null);
  readonly draft = signal<PersonalReminderDraft>(this.emptyDraft());

  readonly authenticated = this.remindersService.authenticated;
  readonly reminders = this.remindersService.activeReminders;
  readonly dueReminders = this.remindersService.dueReminders;
  readonly alertReminders = computed(() => this.dueReminders().slice(0, 3));
  readonly extraDueCount = computed(() => Math.max(this.dueReminders().length - this.alertReminders().length, 0));
  readonly showAlert = computed(() => this.authenticated() && this.view() !== 'list' && this.dueReminders().length > 0);
  readonly showList = computed(() => this.authenticated() && this.view() !== 'alert');
  readonly isScrollable = computed(() => this.reminders().length > 5);
  readonly canSave = computed(() => {
    const draft = this.draft();
    const hasText = Boolean(draft.title.trim() || draft.text.trim());

    if (!hasText || this.saving()) {
      return false;
    }

    if (draft.reminderMode === 'datetime') {
      return Boolean(draft.remindAtLocal);
    }

    if (draft.reminderMode === 'timer') {
      return Number(draft.timerMinutes) > 0;
    }

    return true;
  });

  @Input()
  set mode(value: PersonalReminderView) {
    this.view.set(value || 'full');
  }

  constructor() {
    effect(() => {
      if (!this.initialized() || this.view() === 'list') {
        return;
      }

      const due = this.dueReminders();
      if (!due.length) {
        return;
      }

      const key = `${window.location.pathname}:${due.map((reminder) => `${reminder.id}-${reminder.updatedAt}`).join('|')}`;
      if (!this.remindersService.claimDueToast(key)) {
        return;
      }

      const reminder = due[0];
      const chatUrl = this.reminderChatUrl(reminder);
      const actions: ToastAction[] = [];
      if (chatUrl) {
        actions.push({ label: 'Открыть чат', href: chatUrl });
      }
      if (this.hasPaymentCopyText(reminder)) {
        actions.push({ label: 'счет', callback: () => void this.copyReminderPayment(reminder) });
      }
      if (this.isBadReviewOrderReadyReminder(reminder)) {
        actions.push({ label: 'В Бан', callback: () => this.moveBadReviewOrderToBan(reminder) });
      }
      this.toastService.info(
        'Есть неоконченное дело',
        this.toastText(reminder),
        actions.length ? actions : undefined
      );
    });
  }

  ngOnInit(): void {
    this.initialized.set(true);
    this.remindersService.load(true);
  }

  openCreate(): void {
    this.editingId.set(null);
    this.draft.set(this.emptyDraft());
    this.formOpen.set(true);
  }

  startEdit(reminder: PersonalReminder): void {
    this.expandedReminderId.set(null);
    this.editingId.set(reminder.id);
    this.draft.set(this.draftFromReminder(reminder));
    this.formOpen.set(true);
  }

  cancelEdit(): void {
    this.formOpen.set(false);
    this.editingId.set(null);
    this.draft.set(this.emptyDraft());
  }

  saveDraft(): void {
    if (!this.canSave()) {
      return;
    }

    const input = this.inputFromDraft(this.draft());
    const editingId = this.editingId();
    const request = editingId
      ? this.remindersService.update(editingId, input)
      : this.remindersService.create(input);

    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.toastService.success(editingId ? 'Заметка обновлена' : 'Заметка добавлена');
        this.saving.set(false);
        this.cancelEdit();
      },
      error: (err) => {
        this.saving.set(false);
        this.toastService.error('Заметка не сохранена', apiErrorMessage(err, 'Попробуйте еще раз'));
      }
    });
  }

  complete(reminder: PersonalReminder): void {
    if (this.isRecoveryCompletionReminder(reminder)) {
      this.notifyRecoveryClient(reminder);
      return;
    }

    this.remindersService.complete(reminder.id).subscribe({
      next: () => this.toastService.success('Дело закрыто', reminder.title),
      error: (err) => this.toastService.error('Дело не закрыто', apiErrorMessage(err, 'Попробуйте еще раз'))
    });
  }

  async copyReminderPayment(reminder: PersonalReminder): Promise<void> {
    const text = this.reminderPaymentText(reminder);
    if (!text) {
      this.toastService.error('Счет не скопирован', 'В напоминании нет текста счета');
      return;
    }

    this.copyingPaymentReminderId.set(reminder.id);
    try {
      if (await copyTextToClipboard(text)) {
        this.toastService.success('Скопировано', 'Текст счета скопирован');
      } else {
        this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
      }
    } finally {
      this.copyingPaymentReminderId.set(null);
    }
  }

  moveBadReviewOrderToBan(reminder: PersonalReminder): void {
    const orderId = reminder.sourceOrderId ?? this.recoveryOrderIdFromText(reminder);
    if (!orderId || !this.isBadReviewOrderReadyReminder(reminder)) {
      this.toastService.error('Заказ не переведен', 'Откройте детали заказа и измените статус там.');
      return;
    }

    this.banningBadReviewReminderId.set(reminder.id);
    this.managerApi.updateOrderStatus(orderId, 'Бан').subscribe({
      next: () => {
        this.remindersService.complete(reminder.id).subscribe({
          next: () => {
            this.banningBadReviewReminderId.set(null);
            this.toastService.success('Заказ переведен в Бан', reminder.title);
          },
          error: (err) => {
            this.banningBadReviewReminderId.set(null);
            this.toastService.error('Напоминание не закрыто', apiErrorMessage(err, 'Обновите страницу'));
          }
        });
      },
      error: (err) => {
        this.banningBadReviewReminderId.set(null);
        this.toastService.error('Заказ не переведен', apiErrorMessage(err, 'Проверьте статус и плохие задачи'));
      }
    });
  }

  notifyRecoveryClient(reminder: PersonalReminder): void {
    const batchId = reminder.sourceId;
    const orderId = reminder.sourceOrderId ?? this.recoveryOrderIdFromText(reminder);
    if (!batchId || !orderId) {
      this.toastService.error(
        'Не удалось отметить клиента',
        'Откройте детали заказа и нажмите "Клиент уведомлен" там.'
      );
      return;
    }

    this.notifyingRecoveryReminderId.set(reminder.id);
    this.managerApi.markRecoveryClientNotified(orderId, batchId).subscribe({
      next: () => {
        this.notifyingRecoveryReminderId.set(null);
        this.remindersService.dispatchRecoveryClientNotified({ orderId, batchId });
        this.toastService.success('Клиент уведомлен', reminder.title);
      },
      error: (err) => {
        this.notifyingRecoveryReminderId.set(null);
        this.toastService.error('Клиент не отмечен', apiErrorMessage(err, 'Попробуйте еще раз'));
      }
    });
  }

  remove(reminder: PersonalReminder): void {
    const confirmed = window.confirm(`Удалить заметку "${reminder.title}"?`);

    if (!confirmed) {
      return;
    }

    this.remindersService.remove(reminder.id).subscribe({
      next: () => this.toastService.success('Заметка удалена'),
      error: (err) => this.toastService.error('Заметка не удалена', apiErrorMessage(err, 'Попробуйте еще раз'))
    });
  }

  setDraftField(field: keyof PersonalReminderDraft, value: string | number): void {
    this.draft.update((draft) => ({
      ...draft,
      [field]: value
    }));
  }

  setReminderMode(mode: PersonalReminderMode): void {
    this.draft.update((draft) => ({
      ...draft,
      reminderMode: mode,
      remindAtLocal: mode === 'datetime' && !draft.remindAtLocal ? this.localInputAfterMinutes(60) : draft.remindAtLocal,
      timerMinutes: mode === 'timer' && !draft.timerMinutes ? 30 : draft.timerMinutes
    }));
  }

  noteText(reminder: PersonalReminder): string {
    const text = (reminder.text || '').replace(CHAT_LINE_PATTERN, '$1').trim();
    return text || 'Без текста';
  }

  fullNoteTitle(reminder: PersonalReminder): string {
    return `${reminder.title}\n${this.noteText(reminder)}`;
  }

  reminderChatUrl(reminder: PersonalReminder): string | null {
    const match = (reminder.text || '').match(CHAT_LINE_PATTERN);
    return match?.[2]?.trim() || null;
  }

  toggleExpanded(reminder: PersonalReminder): void {
    this.expandedReminderId.update((id) => (id === reminder.id ? null : reminder.id));
  }

  isExpanded(reminder: PersonalReminder): boolean {
    return this.expandedReminderId() === reminder.id;
  }

  reminderTimeLabel(reminder: PersonalReminder): string {
    if (!reminder.remindAt) {
      if (this.isRecoveryCompletionReminder(reminder)) {
        return 'Требует уведомить клиента';
      }
      if (this.isBadReviewOrderReadyReminder(reminder)) {
        return 'Готово к решению по оплате';
      }
      if (this.isBadReviewTaskReminder(reminder)) {
        return 'Требует отправить счет';
      }

      return 'Без напоминания';
    }

    const date = new Date(reminder.remindAt);
    if (Number.isNaN(date.getTime())) {
      return 'Без напоминания';
    }

    const prefix = date.getTime() <= Date.now() ? 'Сработало' : 'Напомнит';
    return `${prefix}: ${new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(date)}`;
  }

  reminderIcon(reminder: PersonalReminder): string {
    if (reminder.reminderMode === 'timer') {
      return 'timer';
    }

    if (reminder.reminderMode === 'datetime') {
      return 'event';
    }

    return 'sticky_note_2';
  }

  isDue(reminder: PersonalReminder): boolean {
    if (!reminder.remindAt) {
      return this.isRecoveryCompletionReminder(reminder) || this.isBadReviewReminder(reminder);
    }

    const dueAt = Date.parse(reminder.remindAt);
    return Number.isFinite(dueAt) && dueAt <= Date.now();
  }

  private inputFromDraft(draft: PersonalReminderDraft): PersonalReminderInput {
    return {
      title: draft.title,
      text: draft.text,
      reminderMode: draft.reminderMode,
      remindAtLocal: draft.remindAtLocal,
      timerMinutes: draft.timerMinutes
    };
  }

  private draftFromReminder(reminder: PersonalReminder): PersonalReminderDraft {
    return {
      title: reminder.title,
      text: reminder.text,
      reminderMode: reminder.reminderMode,
      remindAtLocal: reminder.reminderMode === 'datetime'
        ? this.remindersService.localInputFromIso(reminder.remindAt)
        : '',
      timerMinutes: reminder.reminderMode === 'timer'
        ? reminder.timerMinutes ?? this.remindersService.minutesUntil(reminder.remindAt)
        : 30
    };
  }

  private emptyDraft(): PersonalReminderDraft {
    return {
      title: '',
      text: '',
      reminderMode: 'none',
      remindAtLocal: '',
      timerMinutes: 30
    };
  }

  private localInputAfterMinutes(minutes: number): string {
    const date = new Date(Date.now() + minutes * 60_000);
    const timezoneOffset = date.getTimezoneOffset() * 60_000;
    return new Date(date.getTime() - timezoneOffset).toISOString().slice(0, 16);
  }

  private toastText(reminder: PersonalReminder): string {
    const text = `${reminder.title}. ${this.noteText(reminder)}`.replace(/\s+/g, ' ').trim();
    return text.length > 180 ? `${text.slice(0, 177).trim()}...` : text;
  }

  isRecoveryCompletionReminder(reminder: PersonalReminder): boolean {
    return reminder.sourceType === REVIEW_RECOVERY_BATCH_SOURCE
      || reminder.title.trim().toLowerCase().startsWith('восстановление завершено');
  }

  isBadReviewTaskReminder(reminder: PersonalReminder): boolean {
    return reminder.sourceType === BAD_REVIEW_TASK_SOURCE
      || reminder.title.trim().toLowerCase().startsWith('плохой отзыв выполнен');
  }

  isBadReviewOrderReadyReminder(reminder: PersonalReminder): boolean {
    return reminder.sourceType === BAD_REVIEW_ORDER_READY_SOURCE
      || reminder.title.trim().toLowerCase().startsWith('плохие отзывы завершены');
  }

  isBadReviewReminder(reminder: PersonalReminder): boolean {
    return this.isBadReviewTaskReminder(reminder) || this.isBadReviewOrderReadyReminder(reminder);
  }

  isUserReminder(reminder: PersonalReminder): boolean {
    return !this.isRecoveryCompletionReminder(reminder) && !this.isBadReviewReminder(reminder);
  }

  hasPaymentCopyText(reminder: PersonalReminder): boolean {
    return Boolean(this.reminderPaymentText(reminder));
  }

  reminderPaymentText(reminder: PersonalReminder): string {
    return (reminder.paymentCopyText ?? '').trim();
  }

  isPaymentCopying(reminder: PersonalReminder): boolean {
    return this.copyingPaymentReminderId() === reminder.id;
  }

  isBadReviewBanSaving(reminder: PersonalReminder): boolean {
    return this.banningBadReviewReminderId() === reminder.id;
  }

  canNotifyRecoveryClient(reminder: PersonalReminder): boolean {
    return this.isRecoveryCompletionReminder(reminder)
      && Boolean(reminder.sourceId)
      && Boolean(reminder.sourceOrderId ?? this.recoveryOrderIdFromText(reminder));
  }

  isRecoveryNotificationSaving(reminder: PersonalReminder): boolean {
    return this.notifyingRecoveryReminderId() === reminder.id;
  }

  private recoveryOrderIdFromText(reminder: PersonalReminder): number | null {
    const match = (reminder.text || '').match(/#(\d+)/);
    const orderId = Number(match?.[1]);
    return Number.isFinite(orderId) && orderId > 0 ? orderId : null;
  }
}
