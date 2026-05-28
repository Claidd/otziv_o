import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-mobile-review-field-editor',
  standalone: true,
  imports: [FormsModule],
  template: `
    <textarea
      [readonly]="readOnly || disabled"
      [disabled]="disabled"
      [ngModel]="value"
      (focus)="start.emit()"
      (click)="start.emit()"
      (ngModelChange)="valueChange.emit($event)"
      [placeholder]="placeholder"
    ></textarea>

    @if (showToggle) {
      <button class="review-text-toggle" type="button" (click)="toggle.emit()">
        {{ expanded ? 'свернуть' : 'развернуть' }}
      </button>
    }

    @if (editing) {
      <div class="note-actions mobile-keyboard-actions">
        <button type="button" class="cancel" (click)="cancel.emit()" [disabled]="disabled">X</button>
        <button type="button" class="save" (click)="save.emit()" [disabled]="saveDisabled">
          <span class="material-icons-sharp">{{ saveIcon }}</span>
        </button>
      </div>
    }
  `,
  styles: [`
    :host {
      position: relative;
      display: grid;
      min-width: 0;
      gap: var(--otziv-card-gap, 0.35rem);
    }

    textarea {
      display: block;
      width: 100%;
      min-width: 0;
      resize: none;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.8rem;
      outline: 0;
      padding: 0.48rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font: 700 0.7rem/1.24 var(--otziv-font-family);
    }

    :host(.review-field--text) textarea {
      height: var(--otziv-review-text-height, 9.1rem);
      overflow: hidden;
      text-align: left;
      vertical-align: top;
    }

    :host-context(.expanded-text):host(.review-field--text) textarea,
    :host(.review-field--text.editing) textarea,
    :host(.editing.review-field--text) textarea {
      height: var(--otziv-review-text-height-open, 10.2rem);
      overflow: auto;
    }

    :host(.review-field--answer) textarea {
      height: var(--otziv-review-answer-height, 3.6rem);
      color: var(--otziv-info);
      font-size: 0.66rem;
      font-weight: 600;
      opacity: 0.72;
      text-align: center;
    }

    textarea:focus,
    :host(.editing) textarea {
      border-color: #f4c542;
      box-shadow: 0 0 0 0.16rem rgba(244, 197, 66, 0.22);
    }

    .review-text-toggle {
      position: absolute;
      right: 0.58rem;
      bottom: 0.52rem;
      z-index: 1;
      border: 0;
      color: var(--otziv-info);
      background: var(--otziv-field-background);
      font: 900 0.58rem/1 var(--otziv-font-family);
    }

    :host(.editing) .review-text-toggle {
      display: none;
    }

    .note-actions {
      display: flex;
      justify-content: space-between;
      gap: 0.35rem;
    }

    .note-actions button {
      display: grid;
      width: 1.85rem;
      height: 1.85rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.55rem;
      color: var(--otziv-dark);
      background: var(--otziv-white);
      font: 900 0.8rem/1 var(--otziv-font-family);
    }

    .note-actions .save {
      color: var(--otziv-success);
    }

    .note-actions .cancel {
      color: var(--otziv-danger);
    }
  `]
})
export class MobileReviewFieldEditorComponent {
  @Input() value = '';
  @Input() placeholder = '';
  @Input() readOnly = false;
  @Input() disabled = false;
  @Input() editing = false;
  @Input() showToggle = false;
  @Input() expanded = false;
  @Input() saveDisabled = false;
  @Input() saveIcon = 'save';

  @Output() start = new EventEmitter<void>();
  @Output() valueChange = new EventEmitter<string>();
  @Output() toggle = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();
  @Output() save = new EventEmitter<void>();
}
