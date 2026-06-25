import { Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonModal } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  ApiService,
  PersonalReminder,
  PersonalReminderMode,
  PersonalReminderRequest
} from '../core/api.service';
import { MobileConfirmService } from './mobile-confirm.service';

type PersonalReminderDraft = {
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAtLocal: string;
  timerMinutes: number;
};

export const MOBILE_RECOVERY_CLIENT_NOTIFIED_EVENT = 'review-recovery-client-notified';
export const REVIEW_RECOVERY_BATCH_SOURCE = 'REVIEW_RECOVERY_BATCH';
export const BAD_REVIEW_TASK_SOURCE = 'BAD_REVIEW_TASK';
export const BAD_REVIEW_ORDER_READY_SOURCE = 'BAD_REVIEW_ORDER_READY';

export type MobileRecoveryClientNotifiedDetail = {
  orderId: number;
  batchId: number;
};

const CHAT_LINE_PATTERN = /(^|\n)\s*Чат:\s*(https?:\/\/\S+|tel:\S+)/i;
const ORDER_ID_PATTERN = /#(\d+)/;

export function dispatchMobileRecoveryClientNotified(detail: MobileRecoveryClientNotifiedDetail): void {
  if (typeof window === 'undefined') {
    return;
  }

  window.dispatchEvent(new CustomEvent<MobileRecoveryClientNotifiedDetail>(
    MOBILE_RECOVERY_CLIENT_NOTIFIED_EVENT,
    { detail }
  ));
}

@Component({
  selector: 'app-mobile-reminders',
  imports: [FormsModule, IonModal],
  template: `
    <ion-modal class="sheet-modal reminders-sheet" [isOpen]="sheetOpen()" (didDismiss)="close()">
      <ng-template>
        <main class="sheet-body reminders-menu">
          <div class="sheet-head">
            <div>
              <p class="sheet-note">Личные дела</p>
              <h2>Напоминания</h2>
            </div>
            <div class="sheet-head-actions">
              <button class="icon-button" type="button" (click)="openCreate()" aria-label="Создать напоминание">
                <span class="material-icons-sharp">add</span>
              </button>
              <button class="icon-button" type="button" (click)="close()" aria-label="Закрыть">
                <span class="material-icons-sharp">close</span>
              </button>
            </div>
          </div>

          <section class="sheet-form-content reminders-menu-content">
            @if (error()) {
              <p class="sheet-error">{{ error() }}</p>
            }

            @if (formOpen()) {
              <form class="reminder-create-card" (ngSubmit)="save()">
                <label class="sheet-field">
                  <span>Название</span>
                  <input
                    name="sharedPersonalReminderTitle"
                    type="text"
                    maxlength="120"
                    placeholder="Что нужно сделать"
                    [ngModel]="draft().title"
                    (ngModelChange)="setDraftField('title', $event)"
                    [disabled]="saving()"
                  >
                </label>

                <label class="sheet-field">
                  <span>Текст</span>
                  <textarea
                    name="sharedPersonalReminderText"
                    rows="3"
                    maxlength="1000"
                    placeholder="Подробности"
                    [ngModel]="draft().text"
                    (ngModelChange)="setDraftField('text', $event)"
                    [disabled]="saving()"
                  ></textarea>
                </label>

                <div class="reminder-mode-row" role="group" aria-label="Тип напоминания">
                  <button type="button" [class.active]="draft().reminderMode === 'none'" (click)="setMode('none')" [disabled]="saving()" aria-label="Без времени">
                    <span class="material-icons-sharp">sticky_note_2</span>
                  </button>
                  <button type="button" [class.active]="draft().reminderMode === 'datetime'" (click)="setMode('datetime')" [disabled]="saving()" aria-label="Дата и время">
                    <span class="material-icons-sharp">event</span>
                  </button>
                  <button type="button" [class.active]="draft().reminderMode === 'timer'" (click)="setMode('timer')" [disabled]="saving()" aria-label="Таймер">
                    <span class="material-icons-sharp">timer</span>
                  </button>
                </div>

                @if (draft().reminderMode === 'datetime') {
                  <label class="sheet-field">
                    <span>Дата</span>
                    <input
                      name="sharedPersonalReminderDate"
                      type="datetime-local"
                      [ngModel]="draft().remindAtLocal"
                      (ngModelChange)="setDraftField('remindAtLocal', $event)"
                      [disabled]="saving()"
                    >
                  </label>
                }

                @if (draft().reminderMode === 'timer') {
                  <label class="sheet-field">
                    <span>Минут</span>
                    <input
                      name="sharedPersonalReminderTimer"
                      type="number"
                      min="1"
                      max="10080"
                      [ngModel]="draft().timerMinutes"
                      (ngModelChange)="setDraftField('timerMinutes', $event)"
                      [disabled]="saving()"
                    >
                  </label>
                }

                <div class="sheet-actions">
                  <button class="secondary" type="button" (click)="cancelCreate()" [disabled]="saving()">Отмена</button>
                  <button type="submit" [disabled]="!canSave()">
                    {{ saving() ? 'Сохраняю' : (editingId() ? 'Сохранить' : 'Создать') }}
                  </button>
                </div>
              </form>
            }

            @if (loading()) {
              <p class="sheet-hint">Загружаю напоминания...</p>
            } @else {
              <div class="mobile-reminder-list">
                @for (reminder of activeReminders(); track reminder.id) {
                  <article
                    class="mobile-reminder-card"
                    [class.due]="isDue(reminder)"
                    [class.expanded]="isExpanded(reminder)"
                    [class.has-delete]="canEdit(reminder)"
                  >
                    @if (canEdit(reminder)) {
                      <button
                        class="reminder-delete-button"
                        type="button"
                        (click)="delete(reminder)"
                        [disabled]="mutatingId() === reminder.id"
                        aria-label="Удалить напоминание"
                      >
                        ×
                      </button>
                    }
                    <span class="material-icons-sharp reminder-card-icon">{{ reminderIcon(reminder) }}</span>
                    <button class="mobile-reminder-body" type="button" (click)="toggle(reminder)">
                      <strong>{{ reminder.title || 'Без названия' }}</strong>
                      <span>{{ reminderText(reminder) }}</span>
                      <small>{{ timeLabel(reminder) }}</small>
                    </button>
                    <div class="mobile-reminder-actions">
                      @if (reminderChatUrl(reminder); as chatUrl) {
                        <a class="text-action" [href]="chatUrl" target="_blank" rel="noopener" aria-label="Открыть чат">
                          чат
                        </a>
                      }
                      @if (isRecoveryCompletionReminder(reminder)) {
                        <button
                          class="text-action"
                          type="button"
                          (click)="notifyRecoveryClient(reminder)"
                          [disabled]="isRecoveryNotificationSaving(reminder) || !canNotifyRecoveryClient(reminder)"
                          aria-label="Клиент уведомлен"
                        >
                          {{ isRecoveryNotificationSaving(reminder) ? '...' : 'клиент' }}
                        </button>
                      } @else if (isBadReviewReminder(reminder)) {
                        @if (hasPaymentCopyText(reminder)) {
                          <button
                            class="text-action"
                            type="button"
                            (click)="copyReminderPayment(reminder)"
                            [disabled]="isPaymentCopying(reminder)"
                            aria-label="Скопировать счет"
                          >
                            {{ isPaymentCopying(reminder) ? '...' : 'счет' }}
                          </button>
                        }
                        @if (isBadReviewOrderReadyReminder(reminder)) {
                          <button
                            class="text-action danger"
                            type="button"
                            (click)="moveBadReviewOrderToBan(reminder)"
                            [disabled]="isBadReviewBanSaving(reminder)"
                            aria-label="Перевести заказ в Бан"
                          >
                            {{ isBadReviewBanSaving(reminder) ? '...' : 'В Бан' }}
                          </button>
                        } @else {
                          <button type="button" (click)="complete(reminder)" [disabled]="mutatingId() === reminder.id" aria-label="Готово">
                            <span class="material-icons-sharp">done</span>
                          </button>
                        }
                      } @else if (canEdit(reminder)) {
                        <button type="button" (click)="startEdit(reminder)" [disabled]="mutatingId() === reminder.id" aria-label="Редактировать">
                          <span class="material-icons-sharp">edit</span>
                        </button>
                        <button type="button" (click)="complete(reminder)" [disabled]="mutatingId() === reminder.id" aria-label="Готово">
                          <span class="material-icons-sharp">done</span>
                        </button>
                      } @else {
                        <button type="button" (click)="complete(reminder)" [disabled]="mutatingId() === reminder.id" aria-label="Готово">
                          <span class="material-icons-sharp">done</span>
                        </button>
                      }
                    </div>
                  </article>
                } @empty {
                  <article class="personal-reminders-empty">
                    <span class="material-icons-sharp">edit_note</span>
                    <p>Нет напоминаний</p>
                  </article>
                }
              </div>
            }
          </section>
        </main>
      </ng-template>
    </ion-modal>
  `
})
export class MobileRemindersComponent implements OnInit, OnDestroy {
  private readonly recoveryClientNotifiedHandler = (event: Event) => this.handleRecoveryClientNotified(event);

  readonly reminders = signal<PersonalReminder[]>([]);
  readonly sheetOpen = signal(false);
  readonly formOpen = signal(false);
  readonly editingId = signal<number | null>(null);
  readonly draft = signal<PersonalReminderDraft>(this.emptyDraft());
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutatingId = signal<number | null>(null);
  readonly notifyingRecoveryReminderId = signal<number | null>(null);
  readonly copyingPaymentReminderId = signal<number | null>(null);
  readonly banningBadReviewReminderId = signal<number | null>(null);
  readonly expandedId = signal<number | null>(null);
  readonly activeReminders = computed(() => this.sortReminders(this.reminders().filter((reminder) => !reminder.completedAt)));
  readonly activeReminderCount = computed(() => this.activeReminders().length);

  constructor(
    private readonly api: ApiService,
    private readonly confirm: MobileConfirmService
  ) {}

  ngOnInit(): void {
    if (typeof window !== 'undefined') {
      window.addEventListener(MOBILE_RECOVERY_CLIENT_NOTIFIED_EVENT, this.recoveryClientNotifiedHandler);
    }
    void this.loadReminders();
  }

  ngOnDestroy(): void {
    if (typeof window !== 'undefined') {
      window.removeEventListener(MOBILE_RECOVERY_CLIENT_NOTIFIED_EVENT, this.recoveryClientNotifiedHandler);
    }
  }

  open(): void {
    this.sheetOpen.set(true);
    this.error.set(null);
    void this.loadReminders(true);
  }

  close(): void {
    if (this.saving() || this.mutatingId()) {
      return;
    }

    this.sheetOpen.set(false);
    this.formOpen.set(false);
    this.editingId.set(null);
    this.expandedId.set(null);
    this.error.set(null);
  }

  openCreate(): void {
    this.editingId.set(null);
    this.draft.set(this.emptyDraft());
    this.formOpen.set(true);
    this.error.set(null);
  }

  startEdit(reminder: PersonalReminder): void {
    if (!this.canEdit(reminder)) {
      return;
    }

    this.editingId.set(reminder.id);
    this.draft.set(this.draftFromReminder(reminder));
    this.expandedId.set(null);
    this.formOpen.set(true);
    this.error.set(null);
  }

  cancelCreate(): void {
    if (this.saving()) {
      return;
    }

    this.formOpen.set(false);
    this.editingId.set(null);
    this.draft.set(this.emptyDraft());
  }

  setDraftField<K extends keyof PersonalReminderDraft>(field: K, value: PersonalReminderDraft[K]): void {
    this.draft.update((draft) => ({ ...draft, [field]: value }));
  }

  setMode(mode: PersonalReminderMode): void {
    this.draft.update((draft) => ({
      ...draft,
      reminderMode: mode,
      remindAtLocal: mode === 'datetime' && !draft.remindAtLocal ? this.localInputAfterMinutes(60) : draft.remindAtLocal,
      timerMinutes: mode === 'timer' && !draft.timerMinutes ? 30 : draft.timerMinutes
    }));
  }

  canSave(): boolean {
    const draft = this.draft();
    const hasContent = Boolean(draft.title.trim() || draft.text.trim());

    if (!hasContent || this.saving()) {
      return false;
    }

    if (draft.reminderMode === 'datetime') {
      return Boolean(draft.remindAtLocal);
    }

    if (draft.reminderMode === 'timer') {
      return Number(draft.timerMinutes) > 0;
    }

    return true;
  }

  async save(): Promise<void> {
    if (!this.canSave()) {
      this.error.set('Заполните заметку и время напоминания.');
      return;
    }

    this.saving.set(true);
    this.error.set(null);

    try {
      const request = this.requestFromDraft(this.draft());
      const editingId = this.editingId();
      const reminder = editingId
        ? await firstValueFrom(this.api.updatePersonalReminder(editingId, request))
        : await firstValueFrom(this.api.createPersonalReminder(request));
      this.reminders.update((reminders) => editingId
        ? reminders.map((item) => item.id === editingId ? reminder : item)
        : [reminder, ...reminders]
      );
      this.formOpen.set(false);
      this.editingId.set(null);
      this.draft.set(this.emptyDraft());
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось сохранить напоминание.');
    } finally {
      this.saving.set(false);
    }
  }

  async complete(reminder: PersonalReminder): Promise<void> {
    if (this.isRecoveryCompletionReminder(reminder)) {
      await this.notifyRecoveryClient(reminder);
      return;
    }

    this.mutatingId.set(reminder.id);
    this.error.set(null);

    try {
      await firstValueFrom(this.api.completePersonalReminder(reminder.id));
      this.reminders.update((reminders) => reminders.filter((item) => item.id !== reminder.id));
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось закрыть напоминание.');
    } finally {
      this.mutatingId.set(null);
    }
  }

  async notifyRecoveryClient(reminder: PersonalReminder): Promise<void> {
    const batchId = reminder.sourceId;
    const orderId = reminder.sourceOrderId ?? this.orderIdFromText(reminder);
    if (!batchId || !orderId) {
      this.error.set('Откройте детали заказа и нажмите "Клиент уведомлен" там.');
      return;
    }

    this.notifyingRecoveryReminderId.set(reminder.id);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.markManagerRecoveryClientNotified(orderId, batchId));
      dispatchMobileRecoveryClientNotified({ orderId, batchId });
      this.reminders.update((reminders) => reminders.filter((item) => item.id !== reminder.id));
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось отметить клиента уведомленным.');
    } finally {
      this.notifyingRecoveryReminderId.set(null);
    }
  }

  async copyReminderPayment(reminder: PersonalReminder): Promise<void> {
    const text = this.reminderPaymentText(reminder);
    if (!text) {
      this.error.set('В уведомлении нет текста счета.');
      return;
    }

    this.copyingPaymentReminderId.set(reminder.id);
    this.error.set(null);
    try {
      await this.copyText(text);
    } catch {
      this.error.set('Не удалось скопировать счет.');
    } finally {
      this.copyingPaymentReminderId.set(null);
    }
  }

  async moveBadReviewOrderToBan(reminder: PersonalReminder): Promise<void> {
    const orderId = reminder.sourceOrderId ?? this.orderIdFromText(reminder);
    if (!orderId || !this.isBadReviewOrderReadyReminder(reminder)) {
      this.error.set('Откройте детали заказа и измените статус там.');
      return;
    }

    this.banningBadReviewReminderId.set(reminder.id);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.updateManagerOrderStatus(orderId, 'Бан'));
      this.reminders.update((reminders) => reminders.filter((item) => item.id !== reminder.id));
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось перевести заказ в Бан.');
    } finally {
      this.banningBadReviewReminderId.set(null);
    }
  }

  async delete(reminder: PersonalReminder): Promise<void> {
    const confirmed = await this.confirm.confirm({
      title: 'Удалить напоминание',
      message: `Удалить напоминание "${reminder.title || 'Без названия'}"?`,
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }

    this.mutatingId.set(reminder.id);
    this.error.set(null);

    try {
      await firstValueFrom(this.api.deletePersonalReminder(reminder.id));
      this.reminders.update((reminders) => reminders.filter((item) => item.id !== reminder.id));
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось удалить напоминание.');
    } finally {
      this.mutatingId.set(null);
    }
  }

  toggle(reminder: PersonalReminder): void {
    this.expandedId.update((id) => id === reminder.id ? null : reminder.id);
  }

  isExpanded(reminder: PersonalReminder): boolean {
    return this.expandedId() === reminder.id;
  }

  canEdit(reminder: PersonalReminder): boolean {
    return !this.isSystemReminder(reminder);
  }

  reminderText(reminder: PersonalReminder): string {
    return (reminder.text || '').replace(CHAT_LINE_PATTERN, '$1').trim() || 'Без текста';
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

  timeLabel(reminder: PersonalReminder): string {
    if (!reminder.remindAt) {
      if (this.isRecoveryCompletionReminder(reminder)) {
        return 'Нужно уведомить клиента';
      }
      if (this.isBadReviewOrderReadyReminder(reminder)) {
        return 'Готово к оплате или Бан';
      }
      if (this.isBadReviewTaskReminder(reminder)) {
        return 'Нужно отправить счет';
      }
      return reminder.reminderMode === 'timer' && reminder.timerMinutes
        ? `Таймер: ${reminder.timerMinutes} мин.`
        : 'Без времени';
    }

    const date = new Date(reminder.remindAt);
    if (Number.isNaN(date.getTime())) {
      return 'Без времени';
    }

    const prefix = date.getTime() <= Date.now() ? 'Сработало' : 'Напомнит';
    return `${prefix}: ${new Intl.DateTimeFormat('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(date)}`;
  }

  reminderChatUrl(reminder: PersonalReminder): string | null {
    const match = (reminder.text || '').match(CHAT_LINE_PATTERN);
    return match?.[2]?.trim() || null;
  }

  isRecoveryCompletionReminder(reminder: PersonalReminder): boolean {
    return reminder.sourceType === REVIEW_RECOVERY_BATCH_SOURCE
      || reminder.title.trim().toLocaleLowerCase('ru-RU').startsWith('восстановление завершено');
  }

  isBadReviewTaskReminder(reminder: PersonalReminder): boolean {
    return reminder.sourceType === BAD_REVIEW_TASK_SOURCE
      || reminder.title.trim().toLocaleLowerCase('ru-RU').startsWith('плохой отзыв выполнен');
  }

  isBadReviewOrderReadyReminder(reminder: PersonalReminder): boolean {
    return reminder.sourceType === BAD_REVIEW_ORDER_READY_SOURCE
      || reminder.title.trim().toLocaleLowerCase('ru-RU').startsWith('плохие отзывы завершены');
  }

  isBadReviewReminder(reminder: PersonalReminder): boolean {
    return this.isBadReviewTaskReminder(reminder) || this.isBadReviewOrderReadyReminder(reminder);
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
      && Boolean(reminder.sourceOrderId ?? this.orderIdFromText(reminder));
  }

  isRecoveryNotificationSaving(reminder: PersonalReminder): boolean {
    return this.notifyingRecoveryReminderId() === reminder.id;
  }

  isDue(reminder: PersonalReminder): boolean {
    if (!reminder.remindAt) {
      return this.isRecoveryCompletionReminder(reminder) || this.isBadReviewReminder(reminder);
    }

    const dueAt = Date.parse(reminder.remindAt);
    return Number.isFinite(dueAt) && dueAt <= Date.now();
  }

  private async loadReminders(force = false): Promise<void> {
    if (this.loading() || (!force && this.reminders().length)) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    try {
      this.reminders.set(await firstValueFrom(this.api.getPersonalReminders()));
    } catch (error) {
      this.error.set(error instanceof Error ? error.message : 'Не удалось загрузить напоминания.');
    } finally {
      this.loading.set(false);
    }
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

  private draftFromReminder(reminder: PersonalReminder): PersonalReminderDraft {
    return {
      title: reminder.title ?? '',
      text: reminder.text ?? '',
      reminderMode: reminder.reminderMode ?? 'none',
      remindAtLocal: reminder.reminderMode === 'datetime' ? this.localInputFromIso(reminder.remindAt) : '',
      timerMinutes: reminder.reminderMode === 'timer' ? reminder.timerMinutes ?? 30 : 30
    };
  }

  private requestFromDraft(draft: PersonalReminderDraft): PersonalReminderRequest {
    return {
      title: draft.title.trim(),
      text: draft.text.trim(),
      reminderMode: draft.reminderMode,
      remindAt: draft.reminderMode === 'datetime' ? this.isoFromLocalInput(draft.remindAtLocal) : null,
      timerMinutes: draft.reminderMode === 'timer' ? this.normalizeMinutes(draft.timerMinutes) : null
    };
  }

  private sortReminders(reminders: PersonalReminder[]): PersonalReminder[] {
    return reminders.slice().sort((left, right) => {
      const leftDue = this.isDue(left) ? 0 : 1;
      const rightDue = this.isDue(right) ? 0 : 1;
      if (leftDue !== rightDue) {
        return leftDue - rightDue;
      }

      const leftTime = left.remindAt ? Date.parse(left.remindAt) : Number.POSITIVE_INFINITY;
      const rightTime = right.remindAt ? Date.parse(right.remindAt) : Number.POSITIVE_INFINITY;
      if (leftTime !== rightTime) {
        return leftTime - rightTime;
      }

      return Date.parse(right.updatedAt) - Date.parse(left.updatedAt);
    });
  }

  private normalizeMinutes(value: number | string): number {
    const minutes = Math.round(Number(value));
    if (!Number.isFinite(minutes) || minutes < 1) {
      return 30;
    }

    return Math.min(minutes, 10_080);
  }

  private isoFromLocalInput(value: string): string | null {
    if (!value) {
      return null;
    }

    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }

  private localInputFromIso(value: string | null): string {
    if (!value) {
      return '';
    }

    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '';
    }

    const timezoneOffset = date.getTimezoneOffset() * 60_000;
    return new Date(date.getTime() - timezoneOffset).toISOString().slice(0, 16);
  }

  private localInputAfterMinutes(minutes: number): string {
    const date = new Date(Date.now() + minutes * 60_000);
    const timezoneOffset = date.getTimezoneOffset() * 60_000;
    return new Date(date.getTime() - timezoneOffset).toISOString().slice(0, 16);
  }

  private isSystemReminder(reminder: PersonalReminder): boolean {
    return this.isRecoveryCompletionReminder(reminder)
      || this.isBadReviewReminder(reminder)
      || Boolean(reminder.sourceType);
  }

  private orderIdFromText(reminder: PersonalReminder): number | null {
    const match = (reminder.text || '').match(ORDER_ID_PATTERN);
    const value = match?.[1] ? Number(match[1]) : NaN;
    return Number.isFinite(value) && value > 0 ? value : null;
  }

  private handleRecoveryClientNotified(event: Event): void {
    const detail = (event as CustomEvent<MobileRecoveryClientNotifiedDetail>).detail;
    if (!detail?.orderId || !detail.batchId) {
      return;
    }

    this.reminders.update((reminders) => reminders.filter((reminder) => {
      const sameBatch = reminder.sourceType === REVIEW_RECOVERY_BATCH_SOURCE
        && reminder.sourceId === detail.batchId;
      const legacySameOrder = reminder.sourceType !== REVIEW_RECOVERY_BATCH_SOURCE
        && reminder.title.trim().toLocaleLowerCase('ru-RU').startsWith('восстановление завершено')
        && reminder.text.includes(`#${detail.orderId}`);

      return !sameBatch && !legacySameOrder;
    }));
  }

  private async copyText(text: string): Promise<void> {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return;
    }

    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.setAttribute('readonly', 'true');
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    document.body.removeChild(textarea);
  }
}
