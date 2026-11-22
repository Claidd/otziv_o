import { DOCUMENT, DatePipe } from '@angular/common';
import { Component, DestroyRef, ElementRef, EventEmitter, HostListener, Input, OnDestroy, OnInit, Output, ViewChild, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ManagerControlApi, ManagerControlConcreteItem, UncertainDeliveryDecision } from '../../../core/manager-control.api';
import { apiErrorMessage } from '../../../shared/api-error-message';

/**
 * «Сверка отправки»: the autoresponder could not confirm that a message reached the client (no WhatsApp receipt,
 * not found in the chat history, and the automatic re-send did not help). The manager opens the client's chat and
 * says what is there; the server re-checks receipts first and records the decision once per send.
 */
@Component({
  selector: 'app-manager-delivery-handoff-dialog',
  imports: [FormsModule, DatePipe],
  template: `
    <div class="handoff-backdrop" role="presentation" (click)="cancel()">
      <section #dialog class="handoff-dialog" role="dialog" aria-modal="true"
        aria-labelledby="handoff-title" tabindex="-1" (click)="$event.stopPropagation()">
        <header class="handoff-head">
          <span class="handoff-icon" aria-hidden="true">fact_check</span>
          <div>
            <small>Автоответчик</small>
            <h2 id="handoff-title">Сверка отправки</h2>
          </div>
          <button type="button" class="handoff-close" aria-label="Закрыть" (click)="cancel()" [disabled]="saving()">×</button>
        </header>

        <div class="handoff-body">
          <p class="handoff-lead">WhatsApp не подтвердил, что это сообщение дошло до клиента. Откройте чат клиента и выберите, что вы там видите.</p>
          @if (loading()) {
            <p role="status">Загружаем сведения об отправке…</p>
          }
          @if (preview(); as operation) {
            <p class="handoff-meta">Отправлено: {{ operation.preparedAt | date: 'dd.MM.yyyy HH:mm' }}</p>
            <h3>Текст сообщения</h3>
            <pre class="handoff-message">{{ operation.message }}</pre>
            <label class="handoff-check">
              <input name="checkedChat" type="checkbox" [ngModel]="checkedChat()"
                (ngModelChange)="checkedChat.set($event)" [disabled]="saving()">
              <span>Я открыл чат клиента и проверил, есть ли там это сообщение</span>
            </label>
            <label class="handoff-note">
              <span>Комментарий, если нужен</span>
              <textarea name="note" rows="2" maxlength="400"
                [ngModel]="note()" (ngModelChange)="note.set($event)" [disabled]="saving()"
                aria-describedby="handoff-note-hint"></textarea>
              <small id="handoff-note-hint">{{ note().trim().length }}/400</small>
            </label>
          }
          @if (error()) {
            <p class="handoff-error" role="alert">{{ error() }}</p>
            @if (!preview() && !loading()) {
              <button type="button" (click)="loadPreview()">Повторить загрузку</button>
            }
          }
        </div>

        @if (preview()) {
          <footer class="handoff-actions">
            <button type="button" class="primary" (click)="decide('SEEN_IN_CHAT')" [disabled]="!canDecide()"
              title="Засчитать отправку и продолжить расписание автоответчика">
              {{ saving() && pending() === 'SEEN_IN_CHAT' ? 'Сохраняем…' : 'Сообщение есть в чате' }}
            </button>
            <button type="button" (click)="decide('NOT_IN_CHAT')" [disabled]="!canDecide()"
              title="Отправить это сообщение заново в ближайшее разрешённое время">
              {{ saving() && pending() === 'NOT_IN_CHAT' ? 'Сохраняем…' : 'Сообщения нет — отправить снова' }}
            </button>
            <button type="button" class="danger" (click)="decide('CLOSE')" [disabled]="!canDecide()"
              title="Не отправлять: автоответчик больше не пишет клиенту на этом этапе заказа, клиента ведёте вы">
              {{ saving() && pending() === 'CLOSE' ? 'Сохраняем…' : 'Не отправлять' }}
            </button>
          </footer>
        }
      </section>
    </div>
  `,
  styles: [`
    @use '../../../shared/plain-modal' as plain;

    :host { @include plain.tokens; }
    :host-context(body.otziv-dark-theme) { @include plain.dark-tokens; }

    .handoff-backdrop { position: fixed; inset: 0; z-index: 2100; display: grid; place-items: center; padding: 16px;
      background: rgba(12, 18, 28, .55); }
    .handoff-dialog { width: min(660px, 100%); max-height: calc(100dvh - 32px); overflow: auto;
      @include plain.skin; }
    .handoff-head { @include plain.head-band; display: flex; align-items: center; gap: .75rem; padding: 1rem 1.2rem; }
    .handoff-head > div { flex: 1; min-width: 0; }
    .handoff-head small { display: block; color: var(--otziv-action); font-size: .66rem; font-weight: 900;
      letter-spacing: .06em; text-transform: uppercase; }
    .handoff-head h2 { margin: .1rem 0 0; font-size: 1.15rem; font-weight: 800; }
    .handoff-icon { @include plain.icon-tile; }
    .handoff-close { @include plain.button; min-width: 2.4rem; justify-content: center; font-size: 1.2rem; }
    .handoff-body { display: grid; gap: .75rem; padding: 1rem 1.2rem; }
    .handoff-lead, .handoff-meta { margin: 0; line-height: 1.5; }
    .handoff-meta { color: var(--plain-muted); font-size: .9rem; }
    h3 { margin: .25rem 0 0; font-size: .95rem; }
    .handoff-message { max-height: 220px; margin: 0; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit;
      line-height: 1.5; padding: .75rem; border: 1px solid var(--plain-border); border-radius: 12px; background: var(--plain-soft); }
    .handoff-check { display: flex; align-items: flex-start; gap: .6rem; line-height: 1.45; font-weight: 700; }
    .handoff-check input { margin-top: .25rem; }
    .handoff-note { display: grid; gap: .35rem; font-weight: 700; }
    .handoff-note small { color: var(--plain-muted); font-weight: 600; }
    textarea { width: 100%; box-sizing: border-box; resize: vertical; padding: .6rem .75rem; border: 1px solid var(--plain-control-border);
      border-radius: 12px; font: inherit; color: var(--plain-text); background: var(--plain-surface); }
    .handoff-error { margin: 0; color: var(--plain-danger); font-weight: 700; }
    .handoff-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: .6rem; padding: 0 1.2rem 1.2rem; }
    .handoff-actions button, .handoff-body > button { @include plain.button; }
    .handoff-actions button.primary { @include plain.button-save; }
    .handoff-actions button.danger { @include plain.button-danger; }
    button:disabled { opacity: .55; cursor: default; }
    @media (max-width: 520px) { .handoff-actions button { flex: 1 1 100%; justify-content: center; } }
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
  readonly pending = signal<UncertainDeliveryDecision | null>(null);
  readonly error = signal<string | null>(null);
  readonly note = signal('');
  readonly checkedChat = signal(false);
  readonly canDecide = computed(() => !!this.preview()?.operationId && !this.loading() && !this.saving()
    && !this.finished() && this.checkedChat() && this.note().trim().length <= 400);

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

  decide(decision: UncertainDeliveryDecision): void {
    const preview = this.preview();
    if (!preview || !this.canDecide()) return;
    this.saving.set(true);
    this.pending.set(decision);
    this.error.set(null);
    this.api.decideUncertainDelivery(this.itemId, preview.operationId, decision, this.note().trim())
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: updated => {
          this.finished.set(true);
          this.saving.set(false);
          this.saved.emit(updated);
        },
        error: err => {
          this.saving.set(false);
          this.pending.set(null);
          this.error.set(apiErrorMessage(err, 'Решение не сохранено. Обновите карточку и попробуйте снова.'));
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
