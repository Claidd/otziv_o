import { HttpClient } from '@angular/common/http';
import { Injectable, OnDestroy, computed, effect, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { AuthService } from '../core/auth.service';
import { appEnvironment } from '../core/app-environment';

export type PersonalReminderMode = 'none' | 'datetime' | 'timer';

export type PersonalReminder = {
  id: number;
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAt: string | null;
  timerMinutes: number | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
};

export type PersonalReminderInput = {
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAtLocal?: string;
  timerMinutes?: number | string | null;
};

type PersonalReminderRequest = {
  title: string;
  text: string;
  reminderMode: PersonalReminderMode;
  remindAt: string | null;
  timerMinutes: number | null;
};

const DEFAULT_TIMER_MINUTES = 30;

@Injectable({ providedIn: 'root' })
export class PersonalRemindersService implements OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly endpoint = `${appEnvironment.apiBaseUrl}/api/personal-reminders`;
  private readonly tickTimer: ReturnType<typeof setInterval> | null = typeof window === 'undefined'
    ? null
    : window.setInterval(() => this.now.set(Date.now()), 15_000);
  private loaded = false;

  readonly authenticated = this.auth.authenticated;
  readonly now = signal(Date.now());
  readonly reminders = signal<PersonalReminder[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly activeReminders = computed(() => {
    this.now();

    return this.reminders()
      .filter((reminder) => !reminder.completedAt)
      .slice()
      .sort((left, right) => this.compareReminders(left, right));
  });

  readonly dueReminders = computed(() => {
    const now = this.now();

    return this.activeReminders().filter((reminder) => {
      if (!reminder.remindAt) {
        return false;
      }

      const dueAt = Date.parse(reminder.remindAt);
      return Number.isFinite(dueAt) && dueAt <= now;
    });
  });

  constructor() {
    effect(() => {
      if (this.authenticated()) {
        return;
      }

      this.loaded = false;
      this.reminders.set([]);
    });
  }

  ngOnDestroy(): void {
    if (this.tickTimer) {
      window.clearInterval(this.tickTimer);
    }
  }

  load(force = false): void {
    if (!this.authenticated() || this.loading() || (this.loaded && !force)) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.http.get<PersonalReminder[]>(this.endpoint).subscribe({
      next: (reminders) => {
        this.reminders.set(reminders);
        this.loaded = true;
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Не удалось загрузить заметки');
        this.loading.set(false);
      }
    });
  }

  create(input: PersonalReminderInput): Observable<PersonalReminder> {
    return this.http.post<PersonalReminder>(this.endpoint, this.requestFromInput(input)).pipe(
      tap((reminder) => {
        this.loaded = true;
        this.reminders.update((reminders) => [...reminders, reminder]);
      })
    );
  }

  update(id: number, input: PersonalReminderInput): Observable<PersonalReminder> {
    return this.http.put<PersonalReminder>(`${this.endpoint}/${id}`, this.requestFromInput(input)).pipe(
      tap((updated) => {
        this.reminders.update((reminders) => reminders.map((reminder) => reminder.id === id ? updated : reminder));
      })
    );
  }

  complete(id: number): Observable<PersonalReminder> {
    return this.http.post<PersonalReminder>(`${this.endpoint}/${id}/complete`, {}).pipe(
      tap(() => {
        this.reminders.update((reminders) => reminders.filter((reminder) => reminder.id !== id));
      })
    );
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.endpoint}/${id}`).pipe(
      tap(() => {
        this.reminders.update((reminders) => reminders.filter((reminder) => reminder.id !== id));
      })
    );
  }

  minutesUntil(remindAt: string | null): number {
    if (!remindAt) {
      return DEFAULT_TIMER_MINUTES;
    }

    const dueAt = Date.parse(remindAt);
    if (!Number.isFinite(dueAt)) {
      return DEFAULT_TIMER_MINUTES;
    }

    return Math.max(1, Math.ceil((dueAt - Date.now()) / 60_000));
  }

  localInputFromIso(iso: string | null): string {
    if (!iso) {
      return '';
    }

    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
      return '';
    }

    const timezoneOffset = date.getTimezoneOffset() * 60_000;
    return new Date(date.getTime() - timezoneOffset).toISOString().slice(0, 16);
  }

  private requestFromInput(input: PersonalReminderInput): PersonalReminderRequest {
    const reminderMode = input.reminderMode;
    let remindAt: string | null = null;
    let timerMinutes: number | null = null;

    if (reminderMode === 'datetime') {
      remindAt = this.isoFromLocalInput(input.remindAtLocal);
    }

    if (reminderMode === 'timer') {
      timerMinutes = this.normalizeTimerMinutes(input.timerMinutes);
    }

    return {
      title: input.title.trim(),
      text: input.text.trim(),
      reminderMode,
      remindAt,
      timerMinutes
    };
  }

  private normalizeTimerMinutes(value: number | string | null | undefined): number {
    const minutes = Math.round(Number(value));

    if (!Number.isFinite(minutes) || minutes < 1) {
      return DEFAULT_TIMER_MINUTES;
    }

    return Math.min(minutes, 10_080);
  }

  private isoFromLocalInput(value?: string): string | null {
    if (!value) {
      return null;
    }

    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? null : date.toISOString();
  }

  private compareReminders(left: PersonalReminder, right: PersonalReminder): number {
    const now = this.now();
    const leftDue = this.dueRank(left, now);
    const rightDue = this.dueRank(right, now);

    if (leftDue !== rightDue) {
      return leftDue - rightDue;
    }

    const leftTime = left.remindAt ? Date.parse(left.remindAt) : Number.POSITIVE_INFINITY;
    const rightTime = right.remindAt ? Date.parse(right.remindAt) : Number.POSITIVE_INFINITY;

    if (leftTime !== rightTime) {
      return leftTime - rightTime;
    }

    return Date.parse(right.updatedAt) - Date.parse(left.updatedAt);
  }

  private dueRank(reminder: PersonalReminder, now: number): number {
    if (!reminder.remindAt) {
      return 2;
    }

    const dueAt = Date.parse(reminder.remindAt);
    return Number.isFinite(dueAt) && dueAt <= now ? 0 : 1;
  }
}
