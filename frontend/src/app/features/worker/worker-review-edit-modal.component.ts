import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { ManagerOption, ProductOption } from '../../core/manager.api';
import {
  ReviewEditDraft,
  ReviewEditItem,
  trackWorkerOption
} from './worker-board.config';

const REVIEW_PUBLICATION_MAX_FUTURE_DAYS = 90;

function localDateInputValue(daysFromToday = 0): string {
  const date = new Date();
  date.setDate(date.getDate() + daysFromToday);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

export type WorkerReviewEditDraftChange = {
  [K in keyof ReviewEditDraft]: {
    field: K;
    value: ReviewEditDraft[K];
  };
}[keyof ReviewEditDraft];

@Component({
  selector: 'app-worker-review-edit-modal',
  imports: [FormsModule],
  templateUrl: './worker-review-edit-modal.component.html'
})
export class WorkerReviewEditModalComponent {
  @Input() loading = false;
  @Input() review: ReviewEditItem | null = null;
  @Input() draft: ReviewEditDraft | null = null;
  @Input() saving = false;
  @Input() deleting = false;
  @Input() uploading = false;
  @Input() newAccountLoading = false;
  @Input() busy = false;
  @Input() error: string | null = null;
  @Input() productOptions: ProductOption[] = [];
  @Input() canEditDates = false;
  @Input() canEditPublish = false;
  @Input() canEditVigul = false;
  @Input() canOnlyUnsetVigul = false;
  @Input() canDelete = false;
  readonly reviewPublicationDateMax = localDateInputValue(REVIEW_PUBLICATION_MAX_FUTURE_DAYS);

  @Output() readonly closed = new EventEmitter<void>();
  @Output() readonly submitted = new EventEmitter<void>();
  @Output() readonly deleted = new EventEmitter<void>();
  @Output() readonly newAccountRequested = new EventEmitter<void>();
  @Output() readonly photoSelected = new EventEmitter<File>();
  @Output() readonly draftChange = new EventEmitter<WorkerReviewEditDraftChange>();

  setField<K extends keyof ReviewEditDraft>(field: K, value: ReviewEditDraft[K]): void {
    if (field === 'vigul' && this.canOnlyUnsetVigul && value === true) {
      return;
    }

    this.draftChange.emit({ field, value } as WorkerReviewEditDraftChange);
  }

  canShowVigulControl(draft: ReviewEditDraft): boolean {
    if (!this.canEditVigul) {
      return false;
    }

    return !this.canOnlyUnsetVigul || !!this.review?.vigul || !!draft.vigul;
  }

  isVigulInputDisabled(draft: ReviewEditDraft): boolean {
    return this.canOnlyUnsetVigul && !draft.vigul;
  }

  productNeedsPhoto(productId: number | null): boolean {
    if (productId == null) {
      return false;
    }

    return !!this.productOptions.find((product) => product.id === productId)?.photo;
  }

  uploadPhoto(event: Event): void {
    const input = event.target as HTMLInputElement | null;
    const file = input?.files?.[0];

    if (file) {
      this.photoSelected.emit(file);
    }

    if (input) {
      input.value = '';
    }
  }

  trackOption(index: number, option: ManagerOption): number {
    return trackWorkerOption(index, option);
  }
}
