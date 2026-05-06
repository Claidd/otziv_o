import { Component, Input, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  PersonalReminder,
  PersonalReminderInput,
  PersonalReminderMode,
  PersonalRemindersService
} from './personal-reminders.service';
import { apiErrorMessage } from './api-error-message';
import { ToastService } from './toast.service';

type PersonalReminderView = 'full' | 'alert' | 'list';

type PersonalReminderDraft = {
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAtLocal: string;
  timerMinutes: number;
};

@Component({
  selector: 'app-personal-reminders',
  imports: [FormsModule],
  templateUrl: './personal-reminders.component.html',
  styleUrl: './personal-reminders.component.scss'
})
export class PersonalRemindersComponent implements OnInit {
  private readonly remindersService = inject(PersonalRemindersService);
  private readonly toastService = inject(ToastService);

  readonly view = signal<PersonalReminderView>('full');
  readonly initialized = signal(false);
  readonly formOpen = signal(false);
  readonly editingId = signal<number | null>(null);
  readonly expandedReminderId = signal<number | null>(null);
  readonly saving = signal(false);
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

      this.toastService.info('Есть неоконченное дело', due[0].title);
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
    this.remindersService.complete(reminder.id).subscribe({
      next: () => this.toastService.success('Дело закрыто', reminder.title),
      error: (err) => this.toastService.error('Дело не закрыто', apiErrorMessage(err, 'Попробуйте еще раз'))
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
    return reminder.text || 'Без текста';
  }

  fullNoteTitle(reminder: PersonalReminder): string {
    return `${reminder.title}\n${this.noteText(reminder)}`;
  }

  toggleExpanded(reminder: PersonalReminder): void {
    this.expandedReminderId.update((id) => (id === reminder.id ? null : reminder.id));
  }

  isExpanded(reminder: PersonalReminder): boolean {
    return this.expandedReminderId() === reminder.id;
  }

  reminderTimeLabel(reminder: PersonalReminder): string {
    if (!reminder.remindAt) {
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
      return false;
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
}
