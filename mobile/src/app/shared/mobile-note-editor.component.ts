import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-mobile-note-editor',
  standalone: true,
  imports: [FormsModule],
  template: `
    <textarea
      class="lead-card-comment"
      [attr.id]="editorId || null"
      [attr.name]="name || null"
      [rows]="rows"
      [placeholder]="placeholder"
      [readonly]="readOnly || disabled"
      [disabled]="disabled"
      [ngModel]="value"
      (focus)="start.emit()"
      (click)="start.emit()"
      (ngModelChange)="valueChange.emit($event)"
      (blur)="blurred.emit()"
    ></textarea>

    @if (stateLabel) {
      <span [class]="'lead-comment-state lead-comment-state--' + state">{{ stateLabel }}</span>
    }

    @if (showActions) {
      <div class="note-actions mobile-keyboard-actions">
        <button type="button" class="cancel" (click)="cancel.emit()" [disabled]="cancelDisabled">{{ cancelLabel }}</button>
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

    .lead-card-comment {
      display: block;
      width: 100%;
      min-width: 0;
      min-height: 100%;
      resize: none;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.8rem;
      outline: 0;
      padding: 0.48rem;
      color: var(--otziv-dark);
      background: var(--otziv-field-background);
      font: 700 0.7rem/1.24 var(--otziv-font-family);
    }

    :host(.company-note-editor) .lead-card-comment,
    :host(.order-note-editor) .lead-card-comment {
      height: 100%;
      font-size: 0.76rem;
    }

    :host(.lead-comment-editor) .lead-card-comment {
      height: 100%;
    }

    .lead-card-comment:focus {
      border-color: #f4c542;
      box-shadow: 0 0 0 0.16rem rgba(244, 197, 66, 0.22);
    }

    .lead-comment-state {
      position: absolute;
      right: 0.62rem;
      bottom: 0.5rem;
      color: var(--otziv-info);
      background: var(--otziv-field-background);
      font-size: 0.58rem;
      font-weight: 900;
    }

    .lead-comment-state--saved {
      color: var(--otziv-success);
    }

    .lead-comment-state--error {
      color: var(--otziv-danger);
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
export class MobileNoteEditorComponent {
  @Input() editorId = '';
  @Input() name = '';
  @Input() value = '';
  @Input() rows = 3;
  @Input() placeholder = '';
  @Input() readOnly = false;
  @Input() disabled = false;
  @Input() stateLabel = '';
  @Input() state = 'idle';
  @Input() showActions = false;
  @Input() saveDisabled = false;
  @Input() cancelDisabled = false;
  @Input() saveIcon = 'save';
  @Input() cancelLabel = 'X';

  @Output() valueChange = new EventEmitter<string>();
  @Output() blurred = new EventEmitter<void>();
  @Output() start = new EventEmitter<void>();
  @Output() save = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();
}
