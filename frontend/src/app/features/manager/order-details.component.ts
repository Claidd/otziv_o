import { Component, DestroyRef, HostListener, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Observable } from 'rxjs';
import { appEnvironment } from '../../core/app-environment';
import { ManagerApi, OrderDetailsPayload, OrderReviewItem, ReviewUpdateRequest } from '../../core/manager.api';
import { AdminLayoutComponent } from '../../shared/admin-layout.component';
import { ToastService } from '../../shared/toast.service';

type ReviewCopyKind = 'filialUrl' | 'botLogin' | 'botPassword' | 'text' | 'answer';
type ReviewEditableField = 'text' | 'answer';
type SideNoteField = 'order' | 'company';
type ReviewEditDraft = ReviewUpdateRequest;

@Component({
  selector: 'app-order-details',
  imports: [AdminLayoutComponent, FormsModule],
  templateUrl: './order-details.component.html',
  styleUrl: './order-details.component.scss'
})
export class OrderDetailsComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly managerApi = inject(ManagerApi);
  private readonly toastService = inject(ToastService);

  readonly orderId = signal<number | null>(null);
  readonly details = signal<OrderDetailsPayload | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly mutationKey = signal<string | null>(null);
  readonly copied = signal<string | null>(null);
  readonly editingReviewFieldKey = signal<string | null>(null);
  readonly reviewFieldDrafts = signal<Record<string, string>>({});
  readonly savedReviewFieldKey = signal<string | null>(null);
  readonly expandedReviewTextIds = signal<Record<number, boolean>>({});
  readonly editingReviewNoteId = signal<number | null>(null);
  readonly reviewNoteDrafts = signal<Record<number, string>>({});
  readonly savedReviewNoteId = signal<number | null>(null);
  readonly editingSideNoteField = signal<SideNoteField | null>(null);
  readonly sideNoteDrafts = signal<Partial<Record<SideNoteField, string>>>({});
  readonly savedSideNoteField = signal<SideNoteField | null>(null);
  readonly activeReviewSlide = signal(0);
  readonly editReview = signal<OrderReviewItem | null>(null);
  readonly reviewEditDraft = signal<ReviewEditDraft | null>(null);
  readonly reviewEditSaving = signal(false);
  readonly reviewEditDeleting = signal(false);
  readonly reviewEditUploading = signal(false);
  readonly reviewEditError = signal<string | null>(null);

  readonly layoutTitle = computed(() => this.details()?.title || 'Детали заказа');
  readonly reviews = computed(() => this.details()?.reviews ?? []);
  readonly productOptions = computed(() => this.details()?.products ?? []);
  readonly reviewEditBusy = computed(() => this.reviewEditSaving() || this.reviewEditDeleting() || this.reviewEditUploading());
  constructor() {
    this.route.paramMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => {
        const id = Number(params.get('orderId'));
        if (!Number.isFinite(id) || id <= 0) {
          this.error.set('Заказ не найден');
          return;
        }

        this.orderId.set(id);
        this.loadDetails();
      });
  }

  @HostListener('window:keydown.escape')
  closeReviewEditFromKeyboard(): void {
    this.closeReviewEdit();
  }

  loadDetails(): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    this.managerApi.getOrderDetails(orderId).subscribe({
      next: (details) => {
        this.details.set(details);
        this.activeReviewSlide.set(0);
        this.loading.set(false);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить детали заказа');
        this.error.set(message);
        this.loading.set(false);
        this.toastService.error('Детали не загрузились', message);
      }
    });
  }

  async copyReviewField(review: OrderReviewItem, kind: ReviewCopyKind): Promise<void> {
    const map: Record<ReviewCopyKind, { value: string; label: string }> = {
      filialUrl: { value: review.filialUrl, label: 'Ссылка скопирована' },
      botLogin: { value: review.botLogin, label: 'Логин скопирован' },
      botPassword: { value: review.botPassword, label: 'Пароль скопирован' },
      text: { value: review.text, label: 'Текст отзыва скопирован' },
      answer: { value: review.answer, label: 'Ответ скопирован' }
    };

    const item = map[kind];
    await this.copyText(item.value, `${review.id}-${kind}`, item.label);
  }

  startReviewFieldEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    if (this.isMutating(this.saveFieldMutationKey(review, field))) {
      return;
    }

    const key = this.reviewFieldKey(review, field);
    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(key);
    this.reviewFieldDrafts.update((drafts) => {
      if (key in drafts) {
        return drafts;
      }

      return {
        ...drafts,
        [key]: this.reviewFieldSourceValue(review, field)
      };
    });
  }

  setReviewFieldDraft(review: OrderReviewItem, field: ReviewEditableField, value: string): void {
    const key = this.reviewFieldKey(review, field);
    this.reviewFieldDrafts.update((drafts) => ({
      ...drafts,
      [key]: value
    }));
  }

  cancelReviewFieldEdit(review: OrderReviewItem, field: ReviewEditableField): void {
    if (this.isMutating(this.saveFieldMutationKey(review, field))) {
      return;
    }

    this.savedReviewFieldKey.set(null);
    this.editingReviewFieldKey.set(null);
    this.clearReviewFieldDraft(review, field);
  }

  saveReviewField(review: OrderReviewItem, field: ReviewEditableField): void {
    const value = this.reviewFieldValue(review, field);
    if (field === 'text' && !value.trim()) {
      this.toastService.error('Текст не сохранен', 'Поле отзыва не должно быть пустым');
      return;
    }

    const key = this.saveFieldMutationKey(review, field);
    const fieldKey = this.reviewFieldKey(review, field);
    this.mutationKey.set(key);
    this.error.set(null);

    const request = field === 'text'
      ? this.managerApi.updateOrderReviewText(review.orderId, review.id, value)
      : this.managerApi.updateOrderReviewAnswer(review.orderId, review.id, value);

    request.subscribe({
      next: (details) => {
        this.details.set(details);
        this.mutationKey.set(null);
        this.savedReviewFieldKey.set(fieldKey);
        this.toastService.success(
          field === 'text' ? 'Текст сохранен' : 'Ответ сохранен',
          `Отзыв #${review.id} обновлен`
        );

        window.setTimeout(() => {
          if (this.savedReviewFieldKey() === fieldKey) {
            this.savedReviewFieldKey.set(null);
          }

          if (this.editingReviewFieldKey() === fieldKey) {
            this.editingReviewFieldKey.set(null);
          }

          this.clearReviewFieldDraft(review, field);
        }, 1000);
      },
      error: (err) => {
        const message = this.errorMessage(err, field === 'text'
          ? 'Не удалось сохранить текст отзыва'
          : 'Не удалось сохранить ответ на отзыв'
        );
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error(field === 'text' ? 'Текст не сохранен' : 'Ответ не сохранен', message);
      }
    });
  }

  shouldShowReviewTextToggle(review: OrderReviewItem): boolean {
    const value = this.reviewFieldSourceValue(review, 'text');
    return value.length > 190 || value.split(/\r?\n/).length > 5;
  }

  isReviewTextExpanded(review: OrderReviewItem): boolean {
    return !!this.expandedReviewTextIds()[review.id];
  }

  isReviewTextOpen(review: OrderReviewItem): boolean {
    return this.isReviewTextExpanded(review) || this.isReviewFieldEditing(review, 'text');
  }

  toggleReviewText(review: OrderReviewItem): void {
    this.expandedReviewTextIds.update((items) => ({
      ...items,
      [review.id]: !items[review.id]
    }));
  }

  onReviewCarouselScroll(event: Event): void {
    const track = event.currentTarget as HTMLElement | null;
    const firstCard = track?.querySelector<HTMLElement>('.review-card');
    if (!track || !firstCard) {
      return;
    }

    const styles = window.getComputedStyle(track);
    const gap = parseFloat(styles.columnGap || styles.gap || '0') || 0;
    const step = firstCard.offsetWidth + gap;
    if (step <= 0) {
      return;
    }

    const maxIndex = Math.max(this.reviews().length - 1, 0);
    const index = Math.round(track.scrollLeft / step);
    this.activeReviewSlide.set(Math.min(maxIndex, Math.max(0, index)));
  }

  hasReviewNote(review: OrderReviewItem): boolean {
    return this.hasReviewOwnNote(review)
      || this.hasReviewOrderNote(review)
      || this.hasReviewCompanyNote(review);
  }

  hasReviewOwnNote(review: OrderReviewItem): boolean {
    return this.hasMeaningfulNote(review.comment);
  }

  hasReviewOrderNote(review: OrderReviewItem): boolean {
    return this.hasMeaningfulNote(review.orderComments)
      || this.hasMeaningfulNote(this.details()?.orderComments);
  }

  hasReviewCompanyNote(review: OrderReviewItem): boolean {
    return this.hasMeaningfulNote(review.commentCompany)
      || this.hasMeaningfulNote(this.details()?.companyComments);
  }

  startReviewNoteEdit(review: OrderReviewItem): void {
    if (this.isMutating(`save-note-${review.id}`)) {
      return;
    }

    this.savedReviewNoteId.set(null);
    this.editingReviewNoteId.set(review.id);
    this.reviewNoteDrafts.update((drafts) => {
      if (review.id in drafts) {
        return drafts;
      }

      return {
        ...drafts,
        [review.id]: review.comment ?? ''
      };
    });
  }

  setReviewNoteDraft(reviewId: number, value: string): void {
    this.reviewNoteDrafts.update((drafts) => ({
      ...drafts,
      [reviewId]: value
    }));
  }

  cancelReviewNoteEdit(review: OrderReviewItem): void {
    if (this.isMutating(`save-note-${review.id}`)) {
      return;
    }

    this.savedReviewNoteId.set(null);
    this.editingReviewNoteId.set(null);
    this.clearReviewNoteDraft(review.id);
  }

  saveReviewNote(review: OrderReviewItem): void {
    const value = this.reviewNoteValue(review);
    const key = `save-note-${review.id}`;
    this.mutationKey.set(key);
    this.error.set(null);

    this.managerApi.updateOrderReviewNote(review.orderId, review.id, value).subscribe({
      next: (details) => {
        this.details.set(details);
        this.mutationKey.set(null);
        this.savedReviewNoteId.set(review.id);
        this.toastService.success('Заметка сохранена', `Отзыв #${review.id} обновлен`);

        window.setTimeout(() => {
          if (this.savedReviewNoteId() === review.id) {
            this.savedReviewNoteId.set(null);
          }

          if (this.editingReviewNoteId() === review.id) {
            this.editingReviewNoteId.set(null);
          }

          this.clearReviewNoteDraft(review.id);
        }, 1000);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить заметку отзыва');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Заметка не сохранена', message);
      }
    });
  }

  reviewNoteValue(review: OrderReviewItem): string {
    return this.reviewNoteDrafts()[review.id] ?? review.comment ?? '';
  }

  isReviewNoteEditing(review: OrderReviewItem): boolean {
    return this.editingReviewNoteId() === review.id;
  }

  isReviewNoteChanged(review: OrderReviewItem): boolean {
    return this.reviewNoteValue(review) !== (review.comment ?? '');
  }

  isReviewNoteSaved(review: OrderReviewItem): boolean {
    return this.savedReviewNoteId() === review.id;
  }

  reviewNoteTitle(review: OrderReviewItem): string {
    const items: string[] = [];

    if (this.hasReviewOwnNote(review)) {
      items.push('Есть заметка отзыва');
    }

    if (this.hasReviewOrderNote(review)) {
      items.push('Есть заметка заказа');
    }

    if (this.hasReviewCompanyNote(review)) {
      items.push('Есть заметка компании');
    }

    return items.join('. ') || 'Заметка отзыва';
  }

  hasReviewPhoto(review: OrderReviewItem): boolean {
    return !!this.reviewPhotoUrl(review);
  }

  needsReviewPhoto(review: OrderReviewItem): boolean {
    return !!review.productPhoto && !this.hasReviewPhoto(review);
  }

  reviewPhotoUrl(review: OrderReviewItem): string {
    return (review.urlPhoto || review.url || '').trim();
  }

  startSideNoteEdit(details: OrderDetailsPayload, field: SideNoteField): void {
    if (this.isMutating(`save-side-${field}`)) {
      return;
    }

    this.savedSideNoteField.set(null);
    this.editingSideNoteField.set(field);
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [field]: drafts[field] ?? this.sideNoteSourceValue(details, field)
    }));
  }

  setSideNoteDraft(field: SideNoteField, value: string): void {
    this.sideNoteDrafts.update((drafts) => ({
      ...drafts,
      [field]: value
    }));
  }

  cancelSideNoteEdit(field: SideNoteField): void {
    if (this.isMutating(`save-side-${field}`)) {
      return;
    }

    this.savedSideNoteField.set(null);
    this.editingSideNoteField.set(null);
    this.clearSideNoteDraft(field);
  }

  saveSideNote(details: OrderDetailsPayload, field: SideNoteField): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    const value = this.sideNoteValue(details, field);
    const key = `save-side-${field}`;
    this.mutationKey.set(key);
    this.error.set(null);

    const request = field === 'order'
      ? this.managerApi.updateOrderNote(orderId, value)
      : this.managerApi.updateOrderCompanyNote(orderId, value);

    request.subscribe({
      next: (updatedDetails) => {
        this.details.set(updatedDetails);
        this.mutationKey.set(null);
        this.savedSideNoteField.set(field);
        this.toastService.success(field === 'order' ? 'Заметка заказа сохранена' : 'Заметка компании сохранена');

        window.setTimeout(() => {
          if (this.savedSideNoteField() === field) {
            this.savedSideNoteField.set(null);
          }

          if (this.editingSideNoteField() === field) {
            this.editingSideNoteField.set(null);
          }

          this.clearSideNoteDraft(field);
        }, 1000);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить заметку');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Заметка не сохранена', message);
      }
    });
  }

  sideNoteValue(details: OrderDetailsPayload, field: SideNoteField): string {
    return this.sideNoteDrafts()[field] ?? this.sideNoteSourceValue(details, field);
  }

  isSideNoteEditing(field: SideNoteField): boolean {
    return this.editingSideNoteField() === field;
  }

  isSideNoteChanged(details: OrderDetailsPayload, field: SideNoteField): boolean {
    return this.sideNoteValue(details, field) !== this.sideNoteSourceValue(details, field);
  }

  isSideNoteSaved(field: SideNoteField): boolean {
    return this.savedSideNoteField() === field;
  }

  reviewFieldValue(review: OrderReviewItem, field: ReviewEditableField): string {
    const key = this.reviewFieldKey(review, field);
    return this.reviewFieldDrafts()[key] ?? this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldEditing(review: OrderReviewItem, field: ReviewEditableField): boolean {
    return this.editingReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  isReviewFieldChanged(review: OrderReviewItem, field: ReviewEditableField): boolean {
    return this.reviewFieldValue(review, field) !== this.reviewFieldSourceValue(review, field);
  }

  isReviewFieldSaved(review: OrderReviewItem, field: ReviewEditableField): boolean {
    return this.savedReviewFieldKey() === this.reviewFieldKey(review, field);
  }

  changeReviewText(review: OrderReviewItem): void {
    this.runReviewMutation(
      `text-${review.id}`,
      this.managerApi.changeOrderReviewText(review.orderId, review.id),
      'Текст заменен',
      'Новый текст отзыва подтянут'
    );
  }

  changeBot(review: OrderReviewItem): void {
    this.runReviewMutation(
      `bot-${review.id}`,
      this.managerApi.changeOrderReviewBot(review.orderId, review.id),
      'Аккаунт заменен',
      review.companyTitle || `Отзыв #${review.id}`
    );
  }

  deactivateBot(review: OrderReviewItem): void {
    if (!review.botId) {
      this.toastService.error('Аккаунт не заблокирован', 'У отзыва нет активного аккаунта');
      return;
    }

    const confirmed = window.confirm(`Заблокировать аккаунт "${this.botLabel(review)}" и заменить его?`);
    if (!confirmed) {
      return;
    }

    this.runReviewMutation(
      `block-${review.id}`,
      this.managerApi.deactivateOrderReviewBot(review.orderId, review.id, review.botId),
      'Аккаунт заблокирован',
      'Назначен новый доступный аккаунт'
    );
  }

  publishReview(review: OrderReviewItem): void {
    this.runReviewMutation(
      `publish-${review.id}`,
      this.managerApi.publishOrderReview(review.orderId, review.id),
      'Отзыв опубликован',
      `Отзыв #${review.id} учтен в заказе`
    );
  }

  openReviewEdit(review: OrderReviewItem): void {
    if (!this.details()?.canEditReviews) {
      return;
    }

    this.editReview.set(review);
    this.reviewEditDraft.set(this.toReviewEditDraft(review));
    this.reviewEditError.set(null);
    this.reviewEditSaving.set(false);
    this.reviewEditDeleting.set(false);
    this.reviewEditUploading.set(false);
  }

  closeReviewEdit(): void {
    if (!this.editReview() || this.reviewEditBusy()) {
      return;
    }

    this.editReview.set(null);
    this.reviewEditDraft.set(null);
    this.reviewEditError.set(null);
  }

  setReviewEditField<K extends keyof ReviewEditDraft>(field: K, value: ReviewEditDraft[K]): void {
    this.reviewEditDraft.update((draft) => draft ? { ...draft, [field]: value } : draft);
  }

  saveReviewEdit(): void {
    const review = this.editReview();
    const draft = this.reviewEditDraft();

    if (!review || !draft) {
      return;
    }

    if (!draft.text.trim()) {
      this.reviewEditError.set('Поле отзыва не должно быть пустым');
      return;
    }

    this.reviewEditSaving.set(true);
    this.reviewEditError.set(null);

    this.managerApi.updateOrderReview(review.orderId, review.id, draft).subscribe({
      next: (details) => {
        this.details.set(details);
        this.clearReviewFieldDraft(review, 'text');
        this.clearReviewFieldDraft(review, 'answer');
        this.clearReviewNoteDraft(review.id);
        this.reviewEditSaving.set(false);
        this.editReview.set(null);
        this.reviewEditDraft.set(null);
        this.toastService.success('Отзыв сохранен', `Изменения по отзыву #${review.id} применены`);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось сохранить отзыв');
        this.reviewEditError.set(message);
        this.reviewEditSaving.set(false);
        this.toastService.error('Отзыв не сохранен', message);
      }
    });
  }

  deleteReviewEdit(): void {
    const review = this.editReview();

    if (!review || this.reviewEditDeleting()) {
      return;
    }

    const confirmed = window.confirm(`Удалить отзыв #${review.id}?`);
    if (!confirmed) {
      return;
    }

    this.reviewEditDeleting.set(true);
    this.reviewEditError.set(null);

    this.managerApi.deleteOrderReview(review.orderId, review.id).subscribe({
      next: (details) => {
        this.details.set(details);
        this.reviewEditDeleting.set(false);
        this.editReview.set(null);
        this.reviewEditDraft.set(null);
        this.toastService.success('Отзыв удален', `Отзыв #${review.id} удален из заказа`);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось удалить отзыв');
        this.reviewEditError.set(message);
        this.reviewEditDeleting.set(false);
        this.toastService.error('Отзыв не удален', message);
      }
    });
  }

  uploadReviewPhoto(event: Event): void {
    const review = this.editReview();
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0];

    if (!review || !file) {
      return;
    }

    this.reviewEditUploading.set(true);
    this.reviewEditError.set(null);

    this.managerApi.uploadOrderReviewPhoto(review.orderId, review.id, file).subscribe({
      next: (details) => {
        this.details.set(details);
        this.reviewEditUploading.set(false);

        const updatedReview = details.reviews.find((item) => item.id === review.id);
        if (updatedReview) {
          this.editReview.set(updatedReview);
          this.reviewEditDraft.update((draft) => draft ? {
            ...draft,
            url: updatedReview.url || updatedReview.urlPhoto || ''
          } : this.toReviewEditDraft(updatedReview));
        }

        if (input) {
          input.value = '';
        }

        this.toastService.success('Фото загружено', `Отзыв #${review.id} обновлен`);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Не удалось загрузить фото');
        this.reviewEditError.set(message);
        this.reviewEditUploading.set(false);
        if (input) {
          input.value = '';
        }
        this.toastService.error('Фото не загружено', message);
      }
    });
  }

  canEditReviewDates(): boolean {
    return !!this.details()?.canEditReviewDates;
  }

  canEditReviewPublish(): boolean {
    return !!this.details()?.canEditReviewPublish;
  }

  canEditReviewVigul(): boolean {
    return !!this.details()?.canEditReviewVigul;
  }

  canDeleteReviews(): boolean {
    return !!this.details()?.canDeleteReviews;
  }

  productNeedsPhoto(productId: number | null): boolean {
    if (productId == null) {
      return false;
    }

    return !!this.productOptions().find((product) => product.id === productId)?.photo;
  }

  addReview(): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    this.runDetailsMutation(
      'add-review',
      this.managerApi.addOrderReview(orderId),
      'Отзыв добавлен',
      'Карточка нового отзыва появилась в заказе'
    );
  }

  sendToCheck(): void {
    const orderId = this.orderId();
    if (!orderId) {
      return;
    }

    this.mutationKey.set('send-check');
    this.managerApi.updateOrderStatus(orderId, 'В проверку').subscribe({
      next: () => {
        this.mutationKey.set(null);
        this.toastService.success('Заказ отправлен', 'Статус изменен на "В проверку"');
        this.loadDetails();
      },
      error: (err) => {
        this.mutationKey.set(null);
        this.toastService.error('Заказ не отправлен', this.errorMessage(err, 'Не удалось отправить заказ на проверку'));
      }
    });
  }

  reviewEditUrl(review: OrderReviewItem): string {
    return this.legacyUrl(`/review/editReview/${review.id}`);
  }

  editAllReviewsUrl(): string {
    const details = this.details();
    return details?.orderDetailsId ? this.appUrl(`/review/editReviews/${details.orderDetailsId}`) : this.appUrl('/manager');
  }

  botLabel(review: OrderReviewItem): string {
    const counter = review.botCounter ? ` ${review.botCounter}` : '';
    return `${review.botFio || 'Аккаунт не назначен'}${counter}`;
  }

  trackReview(_index: number, review: OrderReviewItem): number {
    return review.id;
  }

  isMutating(key: string): boolean {
    return this.mutationKey() === key;
  }

  private toReviewEditDraft(review: OrderReviewItem): ReviewEditDraft {
    return {
      text: review.text ?? '',
      answer: review.answer ?? '',
      comment: review.comment ?? '',
      created: review.created || null,
      changed: review.changed || null,
      publishedDate: review.publishedDate || null,
      publish: !!review.publish,
      vigul: !!review.vigul,
      botName: review.botFio ?? '',
      botPassword: review.botPassword ?? '',
      productId: review.productId ?? null,
      url: review.url || review.urlPhoto || ''
    };
  }

  private runReviewMutation(
    key: string,
    request: Observable<OrderDetailsPayload>,
    toastTitle: string,
    toastMessage: string
  ): void {
    this.runDetailsMutation(key, request, toastTitle, toastMessage);
  }

  private runDetailsMutation(
    key: string,
    request: Observable<OrderDetailsPayload>,
    toastTitle: string,
    toastMessage: string
  ): void {
    this.mutationKey.set(key);
    this.error.set(null);

    request.subscribe({
      next: (details) => {
        this.details.set(details);
        this.mutationKey.set(null);
        this.toastService.success(toastTitle, toastMessage);
      },
      error: (err) => {
        const message = this.errorMessage(err, 'Действие не выполнено');
        this.mutationKey.set(null);
        this.error.set(message);
        this.toastService.error('Действие не выполнено', message);
      }
    });
  }

  private async copyText(text: string, key: string, toast: string): Promise<void> {
    const value = (text ?? '').trim();
    if (!value) {
      return;
    }

    try {
      await navigator.clipboard.writeText(value);
      this.copied.set(key);
      this.toastService.success('Скопировано', toast);
      window.setTimeout(() => {
        if (this.copied() === key) {
          this.copied.set(null);
        }
      }, 1200);
    } catch {
      this.toastService.error('Не скопировано', 'Браузер не дал доступ к буферу обмена');
    }
  }

  private legacyUrl(path: string): string {
    return `${appEnvironment.legacyBaseUrl}${path}`;
  }

  private appUrl(path: string): string {
    return path;
  }

  private reviewFieldKey(review: OrderReviewItem, field: ReviewEditableField): string {
    return `${review.id}-${field}`;
  }

  private saveFieldMutationKey(review: OrderReviewItem, field: ReviewEditableField): string {
    return `save-${field}-${review.id}`;
  }

  private reviewFieldSourceValue(review: OrderReviewItem, field: ReviewEditableField): string {
    return field === 'text' ? review.text ?? '' : review.answer ?? '';
  }

  private clearReviewFieldDraft(review: OrderReviewItem, field: ReviewEditableField): void {
    const key = this.reviewFieldKey(review, field);
    this.reviewFieldDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[key];
      return next;
    });
  }

  private clearReviewNoteDraft(reviewId: number): void {
    this.reviewNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[reviewId];
      return next;
    });
  }

  private sideNoteSourceValue(details: OrderDetailsPayload, field: SideNoteField): string {
    return field === 'order' ? details.orderComments ?? '' : details.companyComments ?? '';
  }

  private hasMeaningfulNote(value: string | null | undefined): boolean {
    const note = (value ?? '').trim();
    return !!note && note.toLowerCase() !== 'нет заметок';
  }

  private clearSideNoteDraft(field: SideNoteField): void {
    this.sideNoteDrafts.update((drafts) => {
      const next = { ...drafts };
      delete next[field];
      return next;
    });
  }

  private errorMessage(err: unknown, fallback: string): string {
    if (err && typeof err === 'object' && 'error' in err) {
      const body = (err as { error?: { message?: string } | string }).error;

      if (typeof body === 'string' && body.trim()) {
        return body;
      }

      if (body && typeof body === 'object' && 'message' in body && body.message) {
        return body.message;
      }
    }

    if (err && typeof err === 'object' && 'message' in err) {
      return String((err as { message?: string }).message ?? fallback);
    }

    return fallback;
  }
}
