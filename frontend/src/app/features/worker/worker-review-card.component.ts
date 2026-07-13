import { Component, DestroyRef, EventEmitter, inject, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { WorkerReviewItem, WorkerSection } from '../../core/worker.api';
import { mobileKeyboardActionBottom } from '../../shared/mobile-keyboard-action-bottom';
import {
  ReviewCopyKind,
  ReviewEditableField,
  SideNoteField,
  workerBotBrowserPath,
  workerReviewDetailsPath,
} from './worker-board.config';
import {
  workerHasReviewCompanyNote,
  workerHasReviewNote,
  workerHasReviewOrderNote,
  workerHasReviewOwnNote,
  workerRecoveryTaskDateMutationKey,
  workerReviewFieldKey,
  workerReviewFieldSourceValue,
  workerReviewNoteMutationKey,
  workerReviewNoteTitle,
  workerReviewTextNeedsToggle,
  workerSaveReviewFieldMutationKey,
  workerSideNoteKey,
  workerSideNoteMutationKey,
  workerSideNoteSourceValue,
} from './worker-board-note.helpers';

export type ReviewFieldValueChange = {
  field: ReviewEditableField;
  value: string;
};

export type SideNoteValueChange = {
  field: SideNoteField;
  value: string;
};

@Component({
  selector: 'app-worker-review-card',
  imports: [FormsModule],
  templateUrl: './worker-review-card.component.html',
  styleUrl: './worker-review-card.component.scss',
})
export class WorkerReviewCardComponent {
  private readonly destroyRef = inject(DestroyRef);

  @Input() review!: WorkerReviewItem;
  @Input() activeSection: WorkerSection = 'publish';
  @Input() copied: string | null = null;
  @Input() mutationKey: string | null = null;
  @Input() canOpenEditModal = false;
  @Input() canInlineEditBotName = false;
  @Input() canOpenTitleLink = true;
  @Input() canEditRecoveryTaskDate = false;
  @Input() showFilialCityInFooter = false;
  @Input() reviewFieldDrafts: Record<string, string> = {};
  @Input() editingReviewFieldKey: string | null = null;
  @Input() savedReviewFieldKey: string | null = null;
  @Input() recoveryTaskDateDrafts: Record<number, string> = {};
  @Input() editingRecoveryTaskDateId: number | null = null;
  @Input() savedRecoveryTaskDateId: number | null = null;
  @Input() expandedReviewTextIds: Record<number, boolean> = {};
  @Input() editingReviewNoteId: number | null = null;
  @Input() reviewNoteDrafts: Record<number, string> = {};
  @Input() savedReviewNoteId: number | null = null;
  @Input() editingSideNoteKey: string | null = null;
  @Input() sideNoteDrafts: Record<string, string> = {};
  @Input() savedSideNoteKey: string | null = null;
  @Input() requireCredentialCopyBeforeAccountAction = false;
  @Input() accountActionCredentialsCopied = false;
  @Input() publishLockedByCredentialWait = false;
  @Input() publishCredentialWaitTitle = 'Действие с отзывом';
  isReviewTitleExpanded = false;
  isBotNameEditing = false;
  botNameDraft = '';
  readonly mobileReviewActionBottom = mobileKeyboardActionBottom(this.destroyRef);

  @Output() readonly reviewFieldEditStarted = new EventEmitter<ReviewEditableField>();
  @Output() readonly reviewFieldDraftChanged = new EventEmitter<ReviewFieldValueChange>();
  @Output() readonly reviewFieldEditCanceled = new EventEmitter<ReviewEditableField>();
  @Output() readonly reviewFieldSaveRequested = new EventEmitter<ReviewEditableField>();
  @Output() readonly reviewTextToggled = new EventEmitter<void>();
  @Output() readonly reviewNoteEditStarted = new EventEmitter<void>();
  @Output() readonly reviewNoteDraftChanged = new EventEmitter<string>();
  @Output() readonly reviewNoteEditCanceled = new EventEmitter<void>();
  @Output() readonly reviewNoteSaveRequested = new EventEmitter<void>();
  @Output() readonly recoveryTaskDateEditStarted = new EventEmitter<void>();
  @Output() readonly recoveryTaskDateDraftChanged = new EventEmitter<string>();
  @Output() readonly recoveryTaskDateEditCanceled = new EventEmitter<void>();
  @Output() readonly recoveryTaskDateSaveRequested = new EventEmitter<void>();
  @Output() readonly sideNoteEditStarted = new EventEmitter<SideNoteField>();
  @Output() readonly sideNoteDraftChanged = new EventEmitter<SideNoteValueChange>();
  @Output() readonly sideNoteEditCanceled = new EventEmitter<SideNoteField>();
  @Output() readonly sideNoteSaveRequested = new EventEmitter<SideNoteField>();
  @Output() readonly copyRequested = new EventEmitter<ReviewCopyKind>();
  @Output() readonly titleCopyRequested = new EventEmitter<string>();
  @Output() readonly botChangeRequested = new EventEmitter<void>();
  @Output() readonly botNameSaveRequested = new EventEmitter<string>();
  @Output() readonly botDeactivateRequested = new EventEmitter<void>();
  @Output() readonly accountRepairRequested = new EventEmitter<string>();
  @Output() readonly doneRequested = new EventEmitter<void>();
  @Output() readonly editOpened = new EventEmitter<void>();

  isBadTask(): boolean {
    return !!this.review.badTask;
  }

  canEditBotNameInline(): boolean {
    return this.canInlineEditBotName
      && this.activeSection === 'nagul'
      && !!this.review.botId
      && !this.hasUnavailableBot();
  }

  startBotNameEdit(event: Event): void {
    if (!this.canEditBotNameInline() || this.isBotNameSaving()) {
      return;
    }

    this.botNameDraft = (this.review.botFio ?? '').trim();
    this.isBotNameEditing = true;
    const host = (event.currentTarget as HTMLElement | null)?.closest('.bot-line');
    window.requestAnimationFrame(() => {
      const input = host?.querySelector<HTMLInputElement>('.bot-name-input');
      input?.focus();
      input?.select();
    });
  }

  finishBotNameEdit(): void {
    if (!this.isBotNameEditing) {
      return;
    }

    const botName = this.botNameDraft.trim();
    if (!botName) {
      return;
    }

    this.isBotNameEditing = false;
    if (botName === (this.review.botFio ?? '').trim()) {
      return;
    }
    this.botNameSaveRequested.emit(botName);
  }

  canSaveBotNameEdit(): boolean {
    const botName = this.botNameDraft.trim();
    return !!botName && botName !== (this.review.botFio ?? '').trim();
  }

  setBotNameDraft(value: string): void {
    this.botNameDraft = value;
  }

  cancelBotNameEdit(): void {
    this.isBotNameEditing = false;
    this.botNameDraft = (this.review.botFio ?? '').trim();
  }

  isBotNameSaving(): boolean {
    return this.mutationKey === `review-${this.review.id}-bot-name`;
  }

  botNameLabel(): string {
    return (this.review.botFio ?? '').trim() || 'Впиши Имя Фамилию';
  }

  isRecoveryTask(): boolean {
    return !!this.review.recoveryTask;
  }

  isWalkTone(): boolean {
    return this.activeSection === 'nagul' && !this.isBadTask();
  }

  isPublicationTone(): boolean {
    return this.activeSection === 'publish' && !this.isBadTask();
  }

  reviewTextCopyDisabled(): boolean {
    return this.activeSection === 'nagul';
  }

  reviewTextCopyTitle(): string {
    return this.reviewTextCopyDisabled()
      ? 'Копирование текста недоступно в разделе «Выгул»'
      : 'Скопировать текст отзыва';
  }

  isRecoveryTone(): boolean {
    return this.activeSection === 'recovery' || this.isRecoveryTask();
  }

  isBadTone(): boolean {
    return this.activeSection === 'bad' || this.isBadTask();
  }

  ratingTaskLabel(): string {
    if (!this.isBadTask()) {
      return '';
    }

    return `${this.review.originalRating ?? 5} -> ${this.review.targetRating ?? 2}`;
  }

  reviewEditUrl(): string {
    return workerReviewDetailsPath(this.review);
  }

  isReviewTitleLinkEnabled(): boolean {
    return (
      this.canOpenTitleLink ||
      (this.activeSection !== 'nagul' &&
        this.activeSection !== 'recovery' &&
        this.activeSection !== 'publish')
    );
  }

  reviewTitle(): string {
    const companyTitle = this.review.companyTitle?.trim() || 'Компания';

    if (!this.shouldShowFilialTitle()) {
      return companyTitle;
    }

    const filialTitle = this.review.filialTitle?.trim();
    return filialTitle ? `${companyTitle} - ${filialTitle}` : companyTitle;
  }

  fullReviewTitle(): string {
    const parts = [
      this.review.companyTitle?.trim() || 'Компания',
      this.review.filialTitle?.trim(),
      this.review.filialCity?.trim()
    ].filter((part): part is string => Boolean(part));
    return parts.join(' - ');
  }

  displayedReviewTitle(): string {
    return this.isReviewTitleExpanded ? this.fullReviewTitle() : this.reviewTitle();
  }

  usesInteractiveTitle(): boolean {
    return this.showFilialCityInFooter;
  }

  handleReviewTitleClick(event: MouseEvent): void {
    if (!this.usesInteractiveTitle()) {
      return;
    }

    event.preventDefault();
    this.isReviewTitleExpanded = true;
  }

  handleReviewTitleDoubleClick(event: MouseEvent): void {
    if (!this.usesInteractiveTitle()) {
      return;
    }

    event.preventDefault();
    this.isReviewTitleExpanded = true;
    this.titleCopyRequested.emit(this.fullReviewTitle());
  }

  footerLabel(): string {
    if (this.showFilialCityInFooter) {
      return this.review.filialCity?.trim() || 'город';
    }

    return this.review.workerFio?.trim() || 'специалист';
  }

  private shouldShowFilialTitle(): boolean {
    return (
      this.activeSection === 'nagul' ||
      this.activeSection === 'recovery' ||
      this.activeSection === 'publish'
    );
  }

  botBrowserUrl(): string {
    return workerBotBrowserPath(this.review.botId);
  }

  reviewPhotoUrl(): string {
    return this.review.urlPhoto || this.review.url || '';
  }

  needsReviewPhoto(): boolean {
    return Boolean(this.review.productPhoto);
  }

  hasReviewPhoto(): boolean {
    return Boolean(this.needsReviewPhoto() && this.reviewPhotoUrl());
  }

  hasReviewNote(): boolean {
    return workerHasReviewNote(this.review);
  }

  hasReviewOwnNote(): boolean {
    return workerHasReviewOwnNote(this.review);
  }

  hasReviewOrderNote(): boolean {
    return workerHasReviewOrderNote(this.review);
  }

  hasReviewCompanyNote(): boolean {
    return workerHasReviewCompanyNote(this.review);
  }

  reviewNoteTitle(): string {
    return workerReviewNoteTitle(this.review);
  }

  reviewFieldValue(field: ReviewEditableField): string {
    const key = workerReviewFieldKey(this.review, field);
    return this.reviewFieldDrafts[key] ?? workerReviewFieldSourceValue(this.review, field);
  }

  isReviewFieldEditing(field: ReviewEditableField): boolean {
    return this.editingReviewFieldKey === workerReviewFieldKey(this.review, field);
  }

  isReviewFieldChanged(field: ReviewEditableField): boolean {
    return this.reviewFieldValue(field) !== workerReviewFieldSourceValue(this.review, field);
  }

  isReviewFieldSaved(field: ReviewEditableField): boolean {
    return this.savedReviewFieldKey === workerReviewFieldKey(this.review, field);
  }

  activeReviewField(): ReviewEditableField | null {
    if (this.isReviewFieldEditing('text')) {
      return 'text';
    }

    if (this.isReviewFieldEditing('answer')) {
      return 'answer';
    }

    return null;
  }

  reviewFieldMutationKey(field: ReviewEditableField): string {
    return workerSaveReviewFieldMutationKey(this.review, field);
  }

  shouldShowReviewTextToggle(): boolean {
    return workerReviewTextNeedsToggle(this.review);
  }

  isReviewTextExpanded(): boolean {
    return Boolean(this.expandedReviewTextIds[this.review.id]);
  }

  isReviewTextOpen(): boolean {
    return this.isReviewTextExpanded() || this.isReviewFieldEditing('text');
  }

  reviewNoteValue(): string {
    return this.reviewNoteDrafts[this.review.id] ?? this.review.comment ?? '';
  }

  isReviewNoteEditing(): boolean {
    return this.editingReviewNoteId === this.review.id;
  }

  isReviewNoteChanged(): boolean {
    return this.reviewNoteValue() !== (this.review.comment ?? '');
  }

  isReviewNoteSaved(): boolean {
    return this.savedReviewNoteId === this.review.id;
  }

  reviewNoteMutationKey(): string {
    return workerReviewNoteMutationKey(this.review);
  }

  recoveryTaskDateValue(): string {
    if (!this.review.recoveryTaskId) {
      return '';
    }

    return this.recoveryTaskDateDrafts[this.review.recoveryTaskId] ?? this.review.recoveryTaskScheduledDate ?? '';
  }

  isRecoveryTaskDateEditing(): boolean {
    return !!this.review.recoveryTaskId && this.editingRecoveryTaskDateId === this.review.recoveryTaskId;
  }

  isRecoveryTaskDateChanged(): boolean {
    return this.recoveryTaskDateValue() !== (this.review.recoveryTaskScheduledDate ?? '');
  }

  isRecoveryTaskDateSaved(): boolean {
    return !!this.review.recoveryTaskId && this.savedRecoveryTaskDateId === this.review.recoveryTaskId;
  }

  recoveryTaskDateMutationKey(): string {
    return workerRecoveryTaskDateMutationKey(this.review);
  }

  sideNoteValue(field: SideNoteField): string {
    return (
      this.sideNoteDrafts[workerSideNoteKey(this.review, field)] ??
      workerSideNoteSourceValue(this.review, field)
    );
  }

  isSideNoteEditing(field: SideNoteField): boolean {
    return this.editingSideNoteKey === workerSideNoteKey(this.review, field);
  }

  isSideNoteChanged(field: SideNoteField): boolean {
    return this.sideNoteValue(field) !== workerSideNoteSourceValue(this.review, field);
  }

  isSideNoteSaved(field: SideNoteField): boolean {
    return this.savedSideNoteKey === workerSideNoteKey(this.review, field);
  }

  sideNoteMutationKey(field: SideNoteField): string {
    return workerSideNoteMutationKey(this.review, field);
  }

  botLabel(): string {
    if (this.hasInactiveRealPublicationBot()) {
      return 'аккаунт неактивен - можно закрыть';
    }

    if (this.hasUnavailableBot() && this.isPlaceholderBotName(this.normalizedBotFio())) {
      return 'смените аккаунт';
    }

    if (this.review.botFio) {
      return `${this.review.botFio} ${this.review.botCounter || ''}`.trim();
    }

    return this.review.productTitle || 'Аккаунт';
  }

  hasUnavailableBot(): boolean {
    if (!this.review.botId || this.review.botId === 1) {
      return true;
    }

    const botFio = this.normalizedBotFio();
    return (
      this.isPlaceholderBotName(botFio) ||
      (this.activeSection === 'publish' && this.isTemplateBotName(botFio))
    );
  }

  hasUnavailablePlaceholderBot(): boolean {
    return !this.review.botId ||
      this.review.botId === 1 ||
      this.isPlaceholderBotName(this.normalizedBotFio());
  }

  hasTemplatePublicationBot(): boolean {
    return this.activeSection === 'publish' && this.isTemplateBotName(this.normalizedBotFio());
  }

  hasInactiveRealPublicationBot(): boolean {
    return (
      this.activeSection === 'publish' &&
      this.review.botActive === false &&
      this.canPublishWithCurrentBot()
    );
  }

  canPublishWithCurrentBot(): boolean {
    if (!this.review.botId || this.review.botId === 1) {
      return false;
    }

    const botFio = this.normalizedBotFio();
    return (
      !!this.review.botLogin?.trim() &&
      !!this.review.botPassword?.trim() &&
      !this.isPlaceholderBotName(botFio) &&
      !this.isTemplateBotName(botFio)
    );
  }

  accountActionLocked(): boolean {
    if (this.cannotCompleteBecauseBotUnavailable()) {
      return false;
    }

    return this.requireCredentialCopyBeforeAccountAction && !this.accountActionCredentialsCopied;
  }

  accountActionTitle(): string {
    return this.accountActionLocked()
      ? 'Сначала скопируйте логин и пароль аккаунта'
      : 'Действие с аккаунтом';
  }

  reviewCredentialCopyDisabled(kind: ReviewCopyKind): boolean {
    return (kind === 'login' || kind === 'password') && this.cannotCompleteBecauseBotUnavailable();
  }

  reviewCredentialCopyTitle(kind: ReviewCopyKind): string {
    return this.reviewCredentialCopyDisabled(kind)
      ? this.accountRepairTitle()
      : 'Скопировать';
  }

  requestDone(): void {
    if (this.cannotCompleteBecauseBotUnavailable()) {
      this.accountRepairRequested.emit(this.accountRepairTitle());
      return;
    }

    this.doneRequested.emit();
  }

  reviewDate(): string {
    return (
      this.review.recoveryTaskScheduledDate ||
      this.review.badTaskScheduledDate ||
      this.review.publishedDate ||
      'Не назначено'
    );
  }

  isPublicationDateOverdue(): boolean {
    if (!this.review.publishedDate) {
      return false;
    }

    const publicationDate = this.parseLocalDateValue(this.review.publishedDate);
    if (!publicationDate) {
      return false;
    }

    const today = new Date();
    const todayValue = today.getFullYear() * 10000 + (today.getMonth() + 1) * 100 + today.getDate();
    return publicationDate < todayValue;
  }

  doneLabel(): string {
    if (this.isRecoveryTask()) {
      return 'Восстановил';
    }

    if (this.isBadTask()) {
      return 'Сменил';
    }

    if (this.isPublishedPublishAction()) {
      return 'ОПУБЛИКОВАНО';
    }

    if (this.cannotCompleteBecauseBotUnavailable()) {
      if (this.hasTemplatePublicationBot()) {
        return 'НУЖЕН ВЫГУЛ';
      }
      return 'СМЕНИТЕ АККАУНТ';
    }

    return this.activeSection === 'nagul' ? 'ВЫГУЛЯЛ' : 'ОПУБЛИКОВАЛ';
  }

  isPublishedPublishAction(): boolean {
    return this.activeSection === 'publish' && !this.isBadTask() && !!this.review.publish;
  }

  cannotPublishBecauseBotUnavailable(): boolean {
    return (
      this.activeSection === 'publish' &&
      !this.isBadTask() &&
      !this.isRecoveryTask() &&
      !this.review.publish &&
      !this.canPublishWithCurrentBot()
    );
  }

  cannotCompleteBecauseBotUnavailable(): boolean {
    return (
      this.isAccountWorkSection() &&
      !this.isBadTask() &&
      !this.isRecoveryTask() &&
      !this.isPublishedPublishAction() &&
      !this.canCompleteWithCurrentBot()
    );
  }

  private canCompleteWithCurrentBot(): boolean {
    if (!this.review.botId || this.review.botId === 1) {
      return false;
    }

    const botFio = this.normalizedBotFio();
    return (
      !!this.review.botLogin?.trim() &&
      !!this.review.botPassword?.trim() &&
      !this.isPlaceholderBotName(botFio) &&
      (this.activeSection !== 'publish' || !this.isTemplateBotName(botFio))
    );
  }

  publishActionTitle(): string {
    if (this.cannotCompleteBecauseBotUnavailable()) {
      return this.accountRepairTitle();
    }

    if (this.publishLockedByCredentialWait) {
      return this.publishCredentialWaitTitle;
    }

    return 'Действие с отзывом';
  }

  private accountRepairTitle(): string {
    if (!this.review.botId || this.review.botId === 1) {
      return 'Аккаунт не назначен. Нажмите "смена", чтобы подобрать рабочий аккаунт.';
    }

    const botFio = this.normalizedBotFio();
    if (this.isPlaceholderBotName(botFio)) {
      return 'Для карточки стоит заглушка вместо аккаунта. Нажмите "смена".';
    }

    if (this.isTemplateBotName(botFio)) {
      return 'Это новый невыгулянный аккаунт со служебным именем "Впиши Имя Фамилию". Сначала отправьте его в выгул; после нормального имени он станет доступен для публикации.';
    }

    if (!this.review.botLogin?.trim() || !this.review.botPassword?.trim()) {
      return 'У назначенного аккаунта нет логина или пароля. Проверьте аккаунт или нажмите "смена".';
    }

    return 'Этот аккаунт нельзя использовать для действия. Нажмите "смена".';
  }

  private isAccountWorkSection(): boolean {
    return this.activeSection === 'publish' || this.activeSection === 'nagul';
  }

  private isTemplateBotName(botFio: string): boolean {
    return (
      botFio === 'впишите имя фамилию' ||
      botFio === 'впиши имя фамилию' ||
      botFio === 'впишите фамилию имя'
    );
  }

  private isPlaceholderBotName(botFio: string): boolean {
    return botFio === 'нет доступных аккаунтов' || botFio === 'добавьте аккаунты и нажмите сменить';
  }

  private normalizedBotFio(): string {
    return (this.review.botFio ?? '').trim().toLocaleLowerCase('ru-RU');
  }

  isMutating(key: string): boolean {
    return this.mutationKey === key;
  }

  private parseLocalDateValue(value: string): number | null {
    const match = value.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if (!match) {
      return null;
    }

    return Number(match[1]) * 10000 + Number(match[2]) * 100 + Number(match[3]);
  }
}
