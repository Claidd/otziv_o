import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, ElementRef, EventEmitter, HostListener, Input, OnDestroy, OnInit, Output, ViewChild, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ManagerControlApi, ManagerControlConcreteItem } from '../../../core/manager-control.api';
import { apiErrorMessage } from '../../../shared/api-error-message';

@Component({
  selector: 'app-manager-delivery-handoff-dialog',
  imports: [FormsModule],
  template: `
    <div class="handoff-backdrop" role="presentation" (click)="cancel()">
      <section #dialog class="handoff-dialog" role="dialog" aria-modal="true"
        aria-labelledby="handoff-title" tabindex="-1" (click)="$event.stopPropagation()">
        <form (ngSubmit)="submit()">
          <header>
            <h2 id="handoff-title">Ручная сверка отправки</h2>
            <button type="button" aria-label="Закрыть" (click)="cancel()" [disabled]="saving()">×</button>
          </header>
          <p>Проверьте клиентский чат и результат этой отправки. Сохранение передаст карточку в ручную работу. Сообщение клиенту не отправляется, доставка не подтверждается.</p>
          @if (loading()) {
            <p role="status">Загружаем сведения об отправке…</p>
          }
          @if (preview(); as operation) {
            <dl>
              <dt>Операция</dt><dd>{{ operation.operationId }}</dd>
              <dt>Подготовлена</dt><dd>{{ operation.preparedAt }}</dd>
            </dl>
            <h3>Текст отправки</h3>
            <pre class="handoff-message">{{ operation.message }}</pre>
            <label class="handoff-note">
              <span>Что обнаружено в чате и что будет сделано для связи с клиентом</span>
              <textarea name="note" rows="4" minlength="10" maxlength="400" required
                [ngModel]="note()" (ngModelChange)="note.set($event)" [disabled]="saving()"
                aria-describedby="handoff-note-hint"></textarea>
              <small id="handoff-note-hint">От 10 до 400 символов · {{ note().trim().length }}/400</small>
            </label>
            <label class="handoff-check">
              <input name="checkedChat" type="checkbox" required [ngModel]="checkedChat()"
                (ngModelChange)="checkedChat.set($event)" [disabled]="saving()">
              <span>Я проверил клиентский чат и результат этой отправки</span>
            </label>
          }
          @if (error()) {
            <p class="handoff-error" role="alert">{{ error() }}</p>
            @if (!preview() && !loading()) {
              <button type="button" (click)="loadPreview()">Повторить загрузку</button>
            }
          }
          <footer>
            <button type="button" (click)="cancel()" [disabled]="saving()">Отмена</button>
            <button type="submit" class="primary" [disabled]="!canSubmit()">
              {{ saving() ? 'Сохраняем…' : 'Сохранить ручную передачу' }}
            </button>
          </footer>
        </form>
      </section>
    </div>
  `,
  styles: [`
    .handoff-backdrop { position: fixed; inset: 0; z-index: 2100; display: grid; place-items: center; padding: 16px; background: rgba(20, 28, 42, .5); }
    .handoff-dialog { width: min(640px, 100%); max-height: calc(100dvh - 32px); overflow: auto; padding: 22px; border-radius: 20px; background: var(--surface, #fff); color: var(--text, #233247); box-shadow: 0 24px 80px rgba(13, 24, 43, .3); }
    header, footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
    h2 { margin: 0; font-size: 1.25rem; } h3 { font-size: 1rem; }
    p, small { line-height: 1.5; color: var(--text-muted, #68758a); }
    dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 8px 12px; font-size: .9rem; }
    dt { font-weight: 700; } dd { margin: 0; overflow-wrap: anywhere; }
    .handoff-message { max-height: 220px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; line-height: 1.5; padding: 12px; border: 1px solid rgba(42, 67, 101, .22); border-radius: 12px; }
    .handoff-note { display: grid; gap: 8px; margin-top: 20px; font-weight: 700; }
    textarea { width: 100%; box-sizing: border-box; resize: vertical; min-height: 100px; padding: 10px 12px; border: 1px solid rgba(42, 67, 101, .22); border-radius: 12px; font: inherit; background: var(--surface, #fff); color: inherit; }
    .handoff-check { display: flex; align-items: flex-start; gap: 10px; margin-top: 16px; line-height: 1.45; }
    .handoff-check input { margin-top: 4px; }
    .handoff-error { color: #b42318; }
    footer { justify-content: flex-end; flex-wrap: wrap; margin-top: 20px; }
    button { min-height: 40px; padding: 9px 16px; border: 1px solid rgba(42, 67, 101, .22); border-radius: 11px; cursor: pointer; }
    button.primary { background: #e84932; border-color: #e84932; color: #fff; }
    button:disabled { opacity: .55; cursor: default; }
    @media (max-width: 480px) { .handoff-dialog { padding: 16px; } dl { grid-template-columns: 1fr; gap: 4px; } dd { margin-bottom: 8px; } footer button { flex: 1; } }
  `]
})
export class ManagerDeliveryHandoffDialogComponent implements OnInit, OnDestroy {
  private readonly api = inject(ManagerControlApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly document = inject(DOCUMENT);
  private readonly trigger = this.document.activeElement as HTMLElement | null;
  private readonly finished = signal(false);

  @Input({ required: true }) itemId!: number;
  @Output() readonly saved = new EventEmitter<ManagerControlConcreteItem>();
  @Output() readonly closed = new EventEmitter<void>();
  @ViewChild('dialog') set dialogElement(element: ElementRef<HTMLElement> | undefined) {
    this.dialog = element?.nativeElement;
    this.dialog?.focus();
  }
  private dialog?: HTMLElement;

  readonly preview = signal<{ operationId: string; preparedAt: string; message: string } | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly error = signal<string | null>(null);
  readonly note = signal('');
  readonly checkedChat = signal(false);
  readonly canSubmit = computed(() => !!this.preview()?.operationId && !this.loading() && !this.saving()
    && !this.finished() && this.checkedChat() && this.note().trim().length >= 10 && this.note().trim().length <= 400);

  ngOnInit(): void { this.loadPreview(); }

  ngOnDestroy(): void {
    const trigger = this.trigger;
    queueMicrotask(() => { if (trigger?.isConnected) trigger.focus(); });
  }

  loadPreview(): void {
    if (this.loading() || this.saving() || this.finished()) return;
    this.loading.set(true);
    this.error.set(null);
    this.preview.set(null);
    this.checkedChat.set(false);
    this.api.uncertainDelivery(this.itemId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: preview => { this.preview.set(preview); this.loading.set(false); },
      error: err => {
        this.loading.set(false);
        this.error.set(apiErrorMessage(err, 'Не удалось получить сведения об отправке'));
      }
    });
  }

  submit(): void {
    const preview = this.preview();
    if (!preview || !this.canSubmit()) return;
    this.saving.set(true);
    this.error.set(null);
    this.api.handoffUncertainDelivery(this.itemId, preview.operationId, this.note().trim())
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: updated => {
          this.finished.set(true);
          this.saving.set(false);
          this.saved.emit(updated);
        },
        error: err => {
          this.saving.set(false);
          this.error.set(apiErrorMessage(err, 'Ручная передача не сохранена. Проверьте состояние карточки.'));
        }
      });
  }

  cancel(): void {
    if (this.saving() || this.finished()) return;
    this.finished.set(true);
    this.closed.emit();
  }

  @HostListener('document:keydown', ['$event'])
  onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      this.cancel();
    } else if (event.key === 'Tab' && this.dialog) {
      const focusable = Array.from(this.dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), input:not([disabled]), textarea:not([disabled])'));
      const first = focusable[0], last = focusable.at(-1), active = this.document.activeElement;
      if (!first || !last) { event.preventDefault(); this.dialog.focus(); }
      else if (!focusable.includes(active as HTMLElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus(); }
      else if (event.shiftKey && active === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && active === last) { event.preventDefault(); first.focus(); }
    }
  }
}
