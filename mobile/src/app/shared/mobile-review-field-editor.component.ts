import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonModal } from '@ionic/angular/standalone';

@Component({
  selector: 'app-mobile-review-field-editor',
  standalone: true,
  imports: [FormsModule, IonModal],
  template: `
    <textarea
      readonly
      [disabled]="disabled"
      [ngModel]="value"
      (focus)="requestStart()"
      (click)="requestStart()"
      [placeholder]="placeholder"
    ></textarea>

    @if (showToggle) {
      <button class="review-text-toggle" type="button" (click)="toggle.emit()">
        {{ expanded ? 'свернуть' : 'развернуть' }}
      </button>
    }

    <ion-modal
      class="sheet-modal review-edit-sheet review-text-edit-sheet"
      [isOpen]="editing"
      (didPresent)="focusEditor($event)"
      (didDismiss)="handleDismiss()"
    >
      <ng-template>
        <form class="sheet-body sheet-form review-text-edit-form" (ngSubmit)="save.emit()">
          <header class="sheet-head review-text-edit-head">
            <div>
              @if (context) {
                <p class="sheet-note">{{ context }}</p>
              }
            </div>
            <button class="icon-button" type="button" (click)="cancel.emit()" [disabled]="disabled" aria-label="Закрыть">
              <span class="material-icons-sharp">close</span>
            </button>
          </header>

          <label class="sheet-field review-text-edit-field">
            <span>{{ placeholder }}</span>
            <textarea
              name="reviewTextFullEditor"
              autofocus
              [ngModel]="value"
              (ngModelChange)="valueChange.emit($event)"
              [placeholder]="placeholder"
              [disabled]="disabled"
            ></textarea>
          </label>

          <footer class="sheet-actions review-text-edit-actions mobile-keyboard-actions">
            <button class="secondary" type="button" (click)="cancel.emit()" [disabled]="disabled">Отмена</button>
            <button type="submit" [disabled]="saveDisabled">
              {{ disabled ? 'Сохраняю' : 'Сохранить' }}
            </button>
          </footer>
        </form>
      </ng-template>
    </ion-modal>
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

  `]
})
export class MobileReviewFieldEditorComponent {
  @Input() value = '';
  @Input() placeholder = '';
  @Input() context = '';
  @Input() readOnly = false;
  @Input() disabled = false;
  @Input() editing = false;
  @Input() showToggle = false;
  @Input() expanded = false;
  @Input() saveDisabled = false;

  @Output() start = new EventEmitter<void>();
  @Output() valueChange = new EventEmitter<string>();
  @Output() toggle = new EventEmitter<void>();
  @Output() cancel = new EventEmitter<void>();
  @Output() save = new EventEmitter<void>();

  requestStart(): void {
    if (!this.readOnly && !this.disabled) {
      this.start.emit();
    }
  }

  handleDismiss(): void {
    if (this.editing && !this.disabled) {
      this.cancel.emit();
    }
  }

  focusEditor(event: CustomEvent): void {
    const modal = event.target as HTMLElement | null;
    window.setTimeout(() => modal?.querySelector<HTMLTextAreaElement>('textarea[name="reviewTextFullEditor"]')?.focus(), 50);
  }
}
