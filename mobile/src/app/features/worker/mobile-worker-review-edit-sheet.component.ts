import { HttpErrorResponse } from '@angular/common/http';
import { Component, EventEmitter, Input, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonModal } from '@ionic/angular/standalone';
import { firstValueFrom } from 'rxjs';
import {
  ApiService,
  OrderDetailsPayload,
  OrderReviewItem,
  ReviewUpdateRequest,
  WorkerActivitySource,
  WorkerReviewItem
} from '../../core/api.service';
import { MobileConfirmService } from '../../shared/mobile-confirm.service';
import { safeHttpsOrInternalUrl } from '../../shared/external-navigation';

@Component({
  selector: 'app-mobile-worker-review-edit-sheet',
  imports: [FormsModule, IonModal],
  template: `
    <ion-modal class="worker-review-edit-modal" [isOpen]="targetReview() !== null" (didDismiss)="requestClose()">
      <ng-template>
        <form class="editor" (ngSubmit)="save()">
          <header>
            @if (details()?.canDeleteReviews) {
              <button class="icon danger" type="button" (click)="remove()" [disabled]="busy()" aria-label="Удалить отзыв">
                <span class="material-icons-sharp">delete</span>
              </button>
            }
            <div>
              <small>Отзыв #{{ currentReview()?.id || targetReview()?.id || '' }}</small>
              <h2>Редактор отзыва</h2>
            </div>
            <button class="icon" type="button" (click)="requestClose()" [disabled]="busy()" aria-label="Закрыть">
              <span class="material-icons-sharp">close</span>
            </button>
          </header>

          <section class="content">
            @if (error()) {
              <p class="error">{{ error() }}</p>
            }

            @if (loading()) {
              <div class="loading"><span class="spinner"></span>Загружаю карточку...</div>
            } @else if (draft(); as form) {
              <label>
                <span>Имя бота</span>
                <input name="botName" type="text" maxlength="255" [ngModel]="form.botName" (ngModelChange)="setField('botName', $event)" [disabled]="busy()">
              </label>

              @if (showBotPassword()) {
                <label>
                  <span>Пароль бота</span>
                  <input
                    name="botPassword"
                    type="password"
                    autocomplete="new-password"
                    [ngModel]="form.botPassword"
                    (ngModelChange)="setField('botPassword', $event)"
                    [placeholder]="currentReview()?.botPasswordPresent ? 'Пароль сохранен — введите новый для замены' : 'Пароль не задан'"
                    [disabled]="busy()"
                  >
                </label>
              }

              <label>
                <span>Дата публикации</span>
                <input name="publishedDate" type="date" [ngModel]="form.publishedDate" (ngModelChange)="setField('publishedDate', emptyToNull($event))" [readonly]="!details()?.canEditReviewDates" [disabled]="busy()">
              </label>

              @if (showNewAccountAction()) {
                <button class="utility" type="button" (click)="assignNewAccount()" [disabled]="busy()">
                  <span class="material-icons-sharp">person_add</span>
                  {{ assigningAccount() ? 'Ищу аккаунт...' : 'Назначить новый аккаунт' }}
                </button>
              }

              <label class="wide">
                <span>Текст отзыва</span>
                <textarea name="text" rows="7" required [ngModel]="form.text" (ngModelChange)="setField('text', $event)" [disabled]="busy()"></textarea>
              </label>

              <label class="wide">
                <span>Ответ или замечание</span>
                <textarea name="answer" rows="3" [ngModel]="form.answer" (ngModelChange)="setField('answer', $event)" [disabled]="busy()"></textarea>
              </label>

              <label class="wide">
                <span>Заметка</span>
                <textarea name="comment" rows="3" [ngModel]="form.comment" (ngModelChange)="setField('comment', $event)" [disabled]="busy()"></textarea>
              </label>

              <label>
                <span>Продукт</span>
                <select name="productId" [ngModel]="form.productId" (ngModelChange)="setField('productId', normalizeId($event))" [disabled]="busy()">
                  <option [ngValue]="null">Не выбран</option>
                  @for (product of details()?.products ?? []; track product.id) {
                    <option [ngValue]="product.id">{{ product.label }}</option>
                  }
                </select>
              </label>

              <label class="wide">
                <span>Ссылка на фото</span>
                <input name="url" type="text" [ngModel]="form.url" (ngModelChange)="setField('url', $event)" [disabled]="busy()">
              </label>

              @if (productNeedsPhoto(form.productId) || form.url.trim()) {
                <div class="photo-actions wide">
                  @if (safeMediaUrl(form.url); as photoUrl) {
                    <a [href]="photoUrl" target="_blank" rel="noopener">
                      <span class="material-icons-sharp">photo_camera</span>Открыть фото
                    </a>
                  }
                  @if (productNeedsPhoto(form.productId)) {
                    <label class="upload">
                      <span class="material-icons-sharp">add_photo_alternate</span>{{ uploading() ? 'Загружаю...' : 'Загрузить фото' }}
                      <input type="file" accept="image/*" (change)="uploadPhoto($event)" [disabled]="busy()">
                    </label>
                  }
                </div>
              }

              @if (details()?.canEditReviewDates) {
                <div class="two-columns wide">
                  <label>
                    <span>Создан</span>
                    <input name="created" type="date" [ngModel]="form.created" (ngModelChange)="setField('created', emptyToNull($event))" [disabled]="busy()">
                  </label>
                  <label>
                    <span>Изменен</span>
                    <input name="changed" type="date" [ngModel]="form.changed" (ngModelChange)="setField('changed', emptyToNull($event))" [disabled]="busy()">
                  </label>
                </div>
              }

              <div class="toggles wide">
                @if (details()?.canEditReviewPublish) {
                  <label><input name="publish" type="checkbox" [ngModel]="form.publish" (ngModelChange)="setField('publish', $event)" [disabled]="busy()"><span>Опубликован</span></label>
                }
                @if (details()?.canEditReviewVigul) {
                  <label><input name="vigul" type="checkbox" [ngModel]="form.vigul" (ngModelChange)="setField('vigul', $event)" [disabled]="busy() || (canOnlyUnsetVigul() && !form.vigul)"><span>Выгул</span></label>
                }
              </div>
            }
          </section>

          <footer>
            <button class="secondary" type="button" (click)="requestClose()" [disabled]="busy()">Отмена</button>
            <button type="submit" [disabled]="busy() || loading() || !canSave()">{{ saving() ? 'Сохраняю...' : 'Сохранить' }}</button>
          </footer>
        </form>
      </ng-template>
    </ion-modal>
  `,
  styles: [`
    :host { display: contents; }
    ion-modal.worker-review-edit-modal { --height: min(94%, 54rem); --width: min(100%, 36rem); --border-radius: 8px 8px 0 0; --background: var(--otziv-background, #f7f8fb); }
    .editor { height: 100%; display: grid; grid-template-rows: auto minmax(0, 1fr) auto; color: var(--otziv-dark, #1f2733); background: var(--otziv-background, #f7f8fb); }
    header { display: grid; grid-template-columns: 2.65rem minmax(0, 1fr) 2.65rem; align-items: center; gap: .5rem; padding: .8rem 1rem; border-bottom: 1px solid var(--otziv-border, #dbe1e8); }
    header div { min-width: 0; text-align: center; }
    header small { display: block; color: var(--otziv-muted, #748192); font-weight: 800; }
    h2 { margin: .1rem 0 0; font-size: 1.25rem; letter-spacing: 0; }
    button, a, .upload { min-height: 2.7rem; border: 1px solid var(--otziv-border, #dbe1e8); border-radius: 8px; color: inherit; background: var(--otziv-white, #fff); font: inherit; font-weight: 800; }
    button:disabled, .upload:has(input:disabled) { opacity: .55; }
    .icon { display: grid; width: 2.65rem; min-height: 2.65rem; place-items: center; padding: 0; }
    .icon.danger { color: #bd3150; }
    .content { overflow-y: auto; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-content: start; gap: .75rem; padding: 1rem; }
    label { min-width: 0; display: grid; gap: .35rem; color: var(--otziv-muted, #687789); font-size: .78rem; font-weight: 900; }
    input, textarea, select { box-sizing: border-box; width: 100%; min-width: 0; border: 1px solid var(--otziv-border, #dbe1e8); border-radius: 8px; padding: .72rem .78rem; color: var(--otziv-dark, #1f2733); background: var(--otziv-white, #fff); font: 700 .92rem/1.35 var(--otziv-font-family, sans-serif); letter-spacing: 0; }
    textarea { resize: vertical; }
    input:focus, textarea:focus, select:focus { outline: 2px solid color-mix(in srgb, var(--otziv-primary, #5f91c7) 45%, transparent); outline-offset: 1px; }
    .wide, .error, .loading, .utility { grid-column: 1 / -1; }
    .utility { display: flex; align-items: center; justify-content: center; gap: .4rem; }
    .two-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .75rem; }
    .toggles { display: flex; flex-wrap: wrap; gap: .65rem; }
    .toggles label { grid-template-columns: auto 1fr; align-items: center; min-height: 2.7rem; flex: 1 1 9rem; padding: 0 .8rem; border: 1px solid var(--otziv-border, #dbe1e8); border-radius: 8px; background: var(--otziv-white, #fff); }
    .toggles input { width: 1.15rem; height: 1.15rem; padding: 0; }
    .photo-actions { display: flex; flex-wrap: wrap; gap: .6rem; }
    .photo-actions a, .upload { display: inline-flex; flex: 1 1 10rem; align-items: center; justify-content: center; gap: .4rem; padding: 0 .7rem; text-decoration: none; }
    .upload input { display: none; }
    .error { margin: 0; padding: .75rem; border: 1px solid rgba(189,49,80,.35); border-radius: 8px; color: #bd3150; background: rgba(189,49,80,.08); font-weight: 800; }
    .loading { display: flex; min-height: 8rem; align-items: center; justify-content: center; gap: .65rem; color: var(--otziv-muted, #687789); font-weight: 800; }
    .spinner { width: 1.1rem; height: 1.1rem; border: 2px solid currentColor; border-right-color: transparent; border-radius: 50%; animation: spin .8s linear infinite; }
    footer { display: grid; grid-template-columns: 1fr 1.35fr; gap: .65rem; padding: .75rem 1rem calc(.75rem + env(safe-area-inset-bottom)); border-top: 1px solid var(--otziv-border, #dbe1e8); background: var(--otziv-background, #f7f8fb); }
    footer button:last-child { border-color: var(--otziv-primary, #5f91c7); color: #fff; background: var(--otziv-primary, #5f91c7); }
    @keyframes spin { to { transform: rotate(360deg); } }
    @media (max-width: 430px) { .content { grid-template-columns: minmax(0, 1fr); } .wide, .error, .loading, .utility { grid-column: auto; } .two-columns { grid-template-columns: minmax(0, 1fr); } }
    :host-context(body.otziv-dark-theme) ion-modal.worker-review-edit-modal { --background: #171b20; }
    :host-context(body.otziv-dark-theme) .editor, :host-context(body.otziv-dark-theme) footer { color: #edf3f7; background: #171b20; }
    :host-context(body.otziv-dark-theme) header, :host-context(body.otziv-dark-theme) footer { border-color: rgba(151,169,183,.18); }
    :host-context(body.otziv-dark-theme) input, :host-context(body.otziv-dark-theme) textarea, :host-context(body.otziv-dark-theme) select, :host-context(body.otziv-dark-theme) button, :host-context(body.otziv-dark-theme) .upload, :host-context(body.otziv-dark-theme) .toggles label { border-color: rgba(151,169,183,.2); color: #edf3f7; background: #20262c; }
    :host-context(body.otziv-dark-theme) footer button:last-child { border-color: #6c9bcf; background: #315f8e; }
  `]
})
export class MobileWorkerReviewEditSheetComponent {
  safeMediaUrl(value: unknown): string {
    return safeHttpsOrInternalUrl(value) ?? '';
  }
  private readonly api = inject(ApiService);
  private readonly confirm = inject(MobileConfirmService);
  private loadVersion = 0;

  readonly targetReview = signal<WorkerReviewItem | null>(null);
  readonly details = signal<OrderDetailsPayload | null>(null);
  readonly currentReview = signal<OrderReviewItem | null>(null);
  readonly draft = signal<ReviewUpdateRequest | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly uploading = signal(false);
  readonly assigningAccount = signal(false);
  readonly error = signal<string | null>(null);

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly changed = new EventEmitter<void>();

  @Input()
  set review(value: WorkerReviewItem | null) {
    if (value?.id === this.targetReview()?.id && value?.orderId === this.targetReview()?.orderId) {
      return;
    }
    this.targetReview.set(value);
    if (value) {
      void this.load(value);
    } else {
      this.reset();
    }
  }

  busy(): boolean {
    return this.saving() || this.deleting() || this.uploading() || this.assigningAccount();
  }

  requestClose(): void {
    if (!this.busy()) {
      this.closed.emit();
    }
  }

  setField<K extends keyof ReviewUpdateRequest>(field: K, value: ReviewUpdateRequest[K]): void {
    this.draft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  emptyToNull(value: string | null | undefined): string | null {
    return value?.trim() || null;
  }

  normalizeId(value: number | string | null): number | null {
    const id = Number(value);
    return Number.isFinite(id) && id > 0 ? id : null;
  }

  canSave(): boolean {
    return Boolean(this.currentReview() && this.draft()?.text.trim());
  }

  canOnlyUnsetVigul(): boolean {
    const details = this.details();
    return Boolean(details?.canEditReviewVigul && !details.canEditReviewDates && !details.canEditReviewPublish);
  }

  showBotPassword(): boolean {
    const details = this.details();
    return Boolean(details?.canEditReviewDates || details?.canEditReviewPublish || details?.canDeleteReviews);
  }

  showNewAccountAction(): boolean {
    const details = this.details();
    return Boolean(details?.canEditReviewDates || details?.canEditReviewPublish || details?.canDeleteReviews);
  }

  productNeedsPhoto(productId: number | null): boolean {
    return Boolean(this.details()?.products.find((product) => product.id === productId)?.photo);
  }

  async save(): Promise<void> {
    const review = this.currentReview();
    const draft = this.draft();
    if (!review || !draft || !draft.text.trim() || this.busy()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    try {
      const request = this.canOnlyUnsetVigul() ? { ...draft, vigul: Boolean(review.vigul && draft.vigul) } : draft;
      await firstValueFrom(this.api.updateManagerOrderReview(review.orderId, review.id, request));
      this.changed.emit();
      this.closed.emit();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось сохранить отзыв.'));
    } finally {
      this.saving.set(false);
    }
  }

  async remove(): Promise<void> {
    const review = this.currentReview();
    if (!review || !this.details()?.canDeleteReviews || this.busy()) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Удалить отзыв',
      message: `Удалить отзыв #${review.id}?`,
      confirmText: 'Удалить',
      danger: true
    });
    if (!confirmed) {
      return;
    }
    this.deleting.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.deleteManagerOrderReview(review.orderId, review.id));
      this.changed.emit();
      this.closed.emit();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось удалить отзыв.'));
    } finally {
      this.deleting.set(false);
    }
  }

  async assignNewAccount(): Promise<void> {
    const review = this.currentReview();
    if (!review || !this.showNewAccountAction() || this.busy()) {
      return;
    }
    this.assigningAccount.set(true);
    this.error.set(null);
    try {
      const updated = await firstValueFrom(this.api.assignManagerOrderReviewNewAccount(review.orderId, review.id, this.activitySource()));
      this.applyReview(updated);
      this.changed.emit();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось назначить новый аккаунт.'));
    } finally {
      this.assigningAccount.set(false);
    }
  }

  async uploadPhoto(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    const review = this.currentReview();
    input.value = '';
    if (!file || !review || this.busy()) {
      return;
    }
    this.uploading.set(true);
    this.error.set(null);
    try {
      const updated = await firstValueFrom(this.api.uploadManagerOrderReviewPhoto(review.orderId, review.id, file));
      this.applyReview(updated);
      this.changed.emit();
    } catch (error) {
      this.error.set(this.errorMessage(error, 'Не удалось загрузить фото.'));
    } finally {
      this.uploading.set(false);
    }
  }

  private async load(target: WorkerReviewItem): Promise<void> {
    const version = ++this.loadVersion;
    this.loading.set(true);
    this.error.set(null);
    this.details.set(null);
    this.currentReview.set(null);
    this.draft.set(null);
    try {
      const details = await firstValueFrom(this.api.getManagerOrderDetails(target.orderId));
      if (version !== this.loadVersion || this.targetReview()?.id !== target.id) {
        return;
      }
      const review = details.reviews.find((item) => item.id === target.id);
      if (!review) {
        throw new Error('Карточка отзыва не найдена в заказе.');
      }
      this.details.set(details);
      this.applyReview(review);
    } catch (error) {
      if (version === this.loadVersion) {
        this.error.set(this.errorMessage(error, 'Не удалось открыть редактор отзыва.'));
      }
    } finally {
      if (version === this.loadVersion) {
        this.loading.set(false);
      }
    }
  }

  private applyReview(review: OrderReviewItem): void {
    this.currentReview.set(review);
    this.draft.set({
      text: review.text ?? '',
      answer: review.answer ?? '',
      comment: review.comment ?? '',
      created: review.created || null,
      changed: review.changed || null,
      publishedDate: review.publishedDate || null,
      publish: Boolean(review.publish),
      vigul: Boolean(review.vigul),
      botName: review.botFio ?? '',
      botPassword: '',
      productId: review.productId ?? null,
      url: review.url || review.urlPhoto || ''
    });
  }

  private reset(): void {
    this.loadVersion += 1;
    this.details.set(null);
    this.currentReview.set(null);
    this.draft.set(null);
    this.loading.set(false);
    this.error.set(null);
  }

  private activitySource(): WorkerActivitySource {
    return { sourcePage: 'mobile-worker', sourceEntry: 'review-edit', sourceSection: 'specialist' };
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const body = error.error;
      if (typeof body === 'string' && body.trim()) {
        return body.trim();
      }
      if (body && typeof body === 'object') {
        const message = (body as { message?: unknown }).message;
        if (typeof message === 'string' && message.trim()) {
          return message.trim();
        }
      }
    }
    return error instanceof Error && error.message ? error.message : fallback;
  }
}
