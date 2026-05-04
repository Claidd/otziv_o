import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { WorkerReviewItem, WorkerSection } from '../../core/worker.api';
import {
  ReviewCopyKind,
  ReviewEditableField,
  SideNoteField,
  workerLegacyUrl
} from './worker-board.config';
import {
  workerHasReviewCompanyNote,
  workerHasReviewNote,
  workerHasReviewOrderNote,
  workerHasReviewOwnNote,
  workerReviewFieldKey,
  workerReviewFieldSourceValue,
  workerReviewNoteMutationKey,
  workerReviewNoteTitle,
  workerReviewTextNeedsToggle,
  workerSaveReviewFieldMutationKey,
  workerSideNoteKey,
  workerSideNoteMutationKey,
  workerSideNoteSourceValue
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
  styleUrl: './worker-review-card.component.scss'
})
export class WorkerReviewCardComponent {
  @Input() review!: WorkerReviewItem;
  @Input() activeSection: WorkerSection = 'publish';
  @Input() copied: string | null = null;
  @Input() mutationKey: string | null = null;
  @Input() canOpenEditModal = false;
  @Input() reviewFieldDrafts: Record<string, string> = {};
  @Input() editingReviewFieldKey: string | null = null;
  @Input() savedReviewFieldKey: string | null = null;
  @Input() expandedReviewTextIds: Record<number, boolean> = {};
  @Input() editingReviewNoteId: number | null = null;
  @Input() reviewNoteDrafts: Record<number, string> = {};
  @Input() savedReviewNoteId: number | null = null;
  @Input() editingSideNoteKey: string | null = null;
  @Input() sideNoteDrafts: Record<string, string> = {};
  @Input() savedSideNoteKey: string | null = null;

  @Output() readonly reviewFieldEditStarted = new EventEmitter<ReviewEditableField>();
  @Output() readonly reviewFieldDraftChanged = new EventEmitter<ReviewFieldValueChange>();
  @Output() readonly reviewFieldEditCanceled = new EventEmitter<ReviewEditableField>();
  @Output() readonly reviewFieldSaveRequested = new EventEmitter<ReviewEditableField>();
  @Output() readonly reviewTextToggled = new EventEmitter<void>();
  @Output() readonly reviewNoteEditStarted = new EventEmitter<void>();
  @Output() readonly reviewNoteDraftChanged = new EventEmitter<string>();
  @Output() readonly reviewNoteEditCanceled = new EventEmitter<void>();
  @Output() readonly reviewNoteSaveRequested = new EventEmitter<void>();
  @Output() readonly sideNoteEditStarted = new EventEmitter<SideNoteField>();
  @Output() readonly sideNoteDraftChanged = new EventEmitter<SideNoteValueChange>();
  @Output() readonly sideNoteEditCanceled = new EventEmitter<SideNoteField>();
  @Output() readonly sideNoteSaveRequested = new EventEmitter<SideNoteField>();
  @Output() readonly copyRequested = new EventEmitter<ReviewCopyKind>();
  @Output() readonly botChangeRequested = new EventEmitter<void>();
  @Output() readonly botDeactivateRequested = new EventEmitter<void>();
  @Output() readonly doneRequested = new EventEmitter<void>();
  @Output() readonly editOpened = new EventEmitter<void>();

  isBadTask(): boolean {
    return !!this.review.badTask;
  }

  ratingTaskLabel(): string {
    if (!this.isBadTask()) {
      return '';
    }

    return `${this.review.originalRating ?? 5} -> ${this.review.targetRating ?? 2}`;
  }

  reviewEditUrl(): string {
    return workerLegacyUrl(`/review/editReview/${this.review.sourceReviewId ?? this.review.id}`);
  }

  botBrowserUrl(): string {
    return this.review.botId ? workerLegacyUrl(`/bots/${this.review.botId}/browser`) : 'https://vk.com/';
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

  sideNoteValue(field: SideNoteField): string {
    return this.sideNoteDrafts[workerSideNoteKey(this.review, field)] ?? workerSideNoteSourceValue(this.review, field);
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
    if (this.hasUnavailableBot()) {
      return 'нет доступных аккаунтов';
    }

    if (this.review.botFio) {
      return `${this.review.botFio} ${this.review.botCounter || ''}`.trim();
    }

    return this.review.productTitle || 'Аккаунт';
  }

  hasUnavailableBot(): boolean {
    return (this.review.botFio ?? '').trim().toLocaleLowerCase('ru-RU') === 'нет доступных аккаунтов';
  }

  reviewDate(): string {
    return this.review.badTaskScheduledDate || this.review.publishedDate || this.review.created || '-';
  }

  doneLabel(): string {
    if (this.isBadTask()) {
      return 'Сменил';
    }

    return this.activeSection === 'nagul' ? 'ВЫГУЛЯЛ' : 'ОПУБЛИКОВАЛ';
  }

  isMutating(key: string): boolean {
    return this.mutationKey === key;
  }
}
