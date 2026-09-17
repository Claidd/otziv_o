import { CommonModule } from '@angular/common';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter, finalize, switchMap, timer } from 'rxjs';
import { ClientOffersApi, OfferAudience, OfferBoard, OfferRecipient, OfferSettings, OfferSummary } from '../../../core/client-offers.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';

const defaults = (): OfferSettings => ({ title: '', message: '', dailyLimit: 30, intervalMinutes: 10,
  windowStart: '10:00', windowEnd: '21:00', includeActive: true, includeStopped: false, includeBanned: false, fileMode: 'ATTACHMENT' });

@Component({
  selector: 'app-client-offers',
  imports: [CommonModule, FormsModule, RouterLink, AdminLayoutComponent],
  templateUrl: './client-offers.component.html',
  styleUrl: './client-offers.component.scss'
})
export class ClientOffersComponent {
  private readonly api = inject(ClientOffersApi);
  private readonly destroyRef = inject(DestroyRef);
  readonly board = signal<OfferBoard>({ liveEnabled: false, campaigns: [] });
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly selectedId = signal<string | null>(null);
  readonly selected = computed(() => this.board().campaigns.find(row => row.campaign.id === this.selectedId()));
  readonly editable = computed(() => !this.selected() || this.selected()?.campaign.state === 'DRAFT');
  readonly audience = signal<OfferAudience[] | null>(null);
  readonly recipients = signal<OfferRecipient[]>([]);
  readonly recipientLoading = signal(false);
  readonly page = signal(0);
  draft = defaults();
  draftId: string = crypto.randomUUID();
  file: File | null = null;
  removeFile = false;
  fileInputKey = 0;

  constructor() {
    this.load();
    timer(15000, 15000).pipe(filter(() => !document.hidden && !this.busy()), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.load(false));
  }
  load(showLoading = true): void {
    if (showLoading) this.loading.set(true);
    this.api.board().pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.loading.set(false))).subscribe({
      next: board => { this.board.set(board); if (this.selected() && !this.editable()) this.loadRecipients(); },
      error: error => this.fail(error)
    });
  }
  newDraft(): void {
    if (this.busy()) return;
    this.selectedId.set(null); this.draftId = crypto.randomUUID(); this.draft = defaults();
    this.clearFile(); this.removeFile = false; this.audience.set(null); this.recipients.set([]); this.error.set(''); this.notice.set('');
  }
  select(row: OfferSummary): void {
    if (this.busy()) return;
    this.selectedId.set(row.campaign.id); this.draftId = row.campaign.id;
    this.draft = { ...row.campaign.settings }; this.file = null; this.removeFile = false; this.fileInputKey++;
    this.audience.set(null); this.error.set(''); this.notice.set(''); this.page.set(0); this.recipients.set([]);
    if (!this.editable()) this.loadRecipients();
  }
  changed(): void { this.audience.set(null); this.notice.set(''); }
  chooseFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    if (file && (file.size === 0 || file.size > 5 * 1024 * 1024)) {
      this.error.set('Выберите непустой файл до 5 МБ.'); input.value = ''; return;
    }
    this.file = file; this.removeFile = false; this.error.set(''); this.changed();
  }
  clearFile(): void { this.file = null; this.removeFile = true; this.fileInputKey++; this.changed(); }
  fileName(): string { return this.file?.name ?? (this.removeFile ? '' : this.selected()?.campaign.fileName ?? ''); }
  valid(): boolean {
    const s = this.draft;
    return !!s.title.trim() && s.title.length <= 120 && !!s.message.trim() && s.message.length <= 1000
      && Number.isInteger(s.dailyLimit) && s.dailyLimit >= 1 && s.dailyLimit <= 10000
      && Number.isInteger(s.intervalMinutes) && s.intervalMinutes >= 1 && s.intervalMinutes <= 1440
      && !!s.windowStart && !!s.windowEnd && s.windowStart < s.windowEnd
      && (s.includeActive || s.includeStopped || s.includeBanned);
  }
  preview(): void {
    if (!this.valid() || this.busy()) return;
    this.busy.set(true); this.error.set('');
    this.api.preview(this.draft).pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.busy.set(false)))
      .subscribe({ next: rows => this.audience.set(rows), error: error => this.fail(error) });
  }
  save(start = false): void {
    if (!this.valid() || !this.editable() || this.busy()) return;
    this.busy.set(true); this.error.set(''); this.notice.set('');
    this.api.save(this.draftId, this.draft, this.file, this.removeFile).pipe(
      switchMap(campaign => {
        this.selectedId.set(campaign.id); this.file = null; this.removeFile = false; this.fileInputKey++;
        return start ? this.api.action(campaign.id, 'start') : this.api.board();
      }), takeUntilDestroyed(this.destroyRef), finalize(() => this.busy.set(false))
    ).subscribe({ next: board => {
      this.board.set(board); this.notice.set(start ? 'Рассылка запущена. Можно закрыть страницу — отправка продолжится на сервере.' : 'Черновик сохранён.');
      if (start) this.loadRecipients();
    }, error: error => { this.fail(error); this.load(false); } });
  }
  action(action: string): void {
    const id = this.selectedId(); if (!id || this.busy()) return;
    this.busy.set(true); this.error.set('');
    this.api.action(id, action).pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.busy.set(false)))
      .subscribe({ next: board => { this.board.set(board); this.loadRecipients(); }, error: error => this.fail(error) });
  }
  loadRecipients(): void {
    const id = this.selectedId(); const page = this.page(); if (!id || this.recipientLoading()) return;
    this.recipientLoading.set(true);
    this.api.recipients(id, page).pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.recipientLoading.set(false)))
      .subscribe({ next: rows => { if (id === this.selectedId() && page === this.page()) this.recipients.set(rows); }, error: error => this.fail(error) });
  }
  setPage(page: number): void { if (this.recipientLoading()) return; this.page.set(page); this.loadRecipients(); }
  download(): void {
    const selected = this.selected(); if (!selected) return;
    this.api.file(selected.campaign.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({ next: blob => {
      const url = URL.createObjectURL(blob); const link = document.createElement('a');
      link.href = url; link.download = selected.campaign.fileName ?? 'file'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    }, error: error => this.fail(error) });
  }
  label(state: string): string {
    return ({ DRAFT: 'Черновик', RUNNING: 'Рассылается', PAUSED: 'Пауза', COMPLETED: 'Завершена', CANCELLED: 'Остановлена',
      PENDING: 'В очереди', SENDING: 'Отправляется', SENT: 'Отправлено', FAILED: 'Ошибка', UNKNOWN: 'Нужна проверка',
      SKIPPED: 'Пропущено', ACTIVE: 'В работе', STOPPED: 'На стопе', BANNED: 'Бан' } as Record<string, string>)[state] ?? state;
  }
  date(value: string | null): string {
    return value ? new Intl.DateTimeFormat('ru-RU', { timeZone: 'Asia/Irkutsk', dateStyle: 'short', timeStyle: 'short' })
      .format(new Date(value.endsWith('Z') ? value : value + 'Z')) : '—';
  }
  private fail(error: { error?: { detail?: string; message?: string }; message?: string }): void {
    this.error.set(error?.error?.detail ?? error?.error?.message ?? 'Не удалось выполнить действие. Обновите страницу и повторите.');
  }
}
