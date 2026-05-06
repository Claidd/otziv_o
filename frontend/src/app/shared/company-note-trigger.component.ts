import { Component, input, output, signal } from '@angular/core';

@Component({
  selector: 'app-company-note-trigger',
  template: `
    <span class="t" [class.e]="open()" (click)="toggle()">
      <span class="material-icons-sharp">priority_high</span>
      <span class="p" (click)="$event.stopPropagation()">
        @if (has(note())) {
          <label class="s">
            <span>Заметка компании</span>
            <textarea
              [readonly]="editing() !== 'company'"
              [value]="value('company', note())"
              (input)="setDraft($any($event.target).value)"
              (focus)="startEdit('company', note())"
            ></textarea>

            @if (editing() === 'company') {
              <span class="a">
                <button type="button" (click)="$event.stopPropagation(); cancelEdit()">X</button>
                <button type="button" class="save" (click)="$event.stopPropagation(); saveEdit('company')" [disabled]="!changed(note())">
                  <span class="material-icons-sharp">save</span>
                </button>
              </span>
            }
          </label>
        }

        @if (has(orderNote())) {
          <label class="s">
            <span>Заметка заказа</span>
            <textarea
              [readonly]="editing() !== 'order'"
              [value]="value('order', orderNote())"
              (input)="setDraft($any($event.target).value)"
              (focus)="startEdit('order', orderNote())"
            ></textarea>

            @if (editing() === 'order') {
              <span class="a">
                <button type="button" (click)="$event.stopPropagation(); cancelEdit()">X</button>
                <button type="button" class="save" (click)="$event.stopPropagation(); saveEdit('order')" [disabled]="!changed(orderNote())">
                  <span class="material-icons-sharp">save</span>
                </button>
              </span>
            }
          </label>
        }
      </span>
    </span>
  `,
  styles: [`
    .t {
      position: relative;
      display: inline-grid;
      width: 1rem;
      height: 1rem;
      place-items: center;
      border-radius: 50%;
      background: #f4c542;
      color: #4d3900;
      cursor: pointer;
    }

    .t > .material-icons-sharp {
      font-size: 0.86rem;
    }

    .p {
      position: absolute;
      top: 1rem;
      right: 0;
      z-index: 4;
      display: none;
      width: min(17rem, 78vw);
      max-height: 21rem;
      overflow: auto;
      gap: 0.45rem;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.75rem;
      padding: 0.55rem;
      background: var(--otziv-white);
      box-shadow: 0 1rem 2rem rgba(132, 139, 200, 0.24);
    }

    .t.e .p,
    .t:hover .p,
    .t:focus-within .p {
      display: grid;
    }

    .s {
      display: grid;
      gap: 0.35rem;
    }

    .s > span:first-child {
      color: var(--otziv-info);
      font-size: 0.68rem;
      font-weight: 900;
    }

    textarea {
      width: 100%;
      min-height: 5.2rem;
      resize: vertical;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.55rem;
      padding: 0.55rem;
      background: var(--otziv-field-background);
      color: var(--otziv-dark);
    }

    .a {
      display: flex;
      width: 100%;
      justify-content: space-between;
      gap: 0.35rem;
    }

    .a button {
      display: grid;
      width: 1.85rem;
      height: 1.85rem;
      place-items: center;
      border: 1px solid rgba(103, 116, 131, 0.22);
      border-radius: 0.5rem;
      background: var(--otziv-white);
      color: var(--otziv-dark);
    }

    .a button:not(:disabled):hover {
      border-color: transparent;
      color: white;
      background: #c461dc;
    }

    .a .save {
      color: var(--otziv-success);
    }

    .a button:first-child {
      color: var(--otziv-danger);
    }
  `]
})
export class CompanyNoteTriggerComponent {
  readonly note = input<string | null | undefined>('');
  readonly orderNote = input<string | null | undefined>('');
  readonly saveNote = output<string>();
  readonly saveOrderNote = output<string>();
  readonly open = signal(false);
  readonly editing = signal<'company' | 'order' | null>(null);
  readonly draft = signal('');

  toggle(): void {
    if (!this.editing()) {
      this.open.update((open) => !open);
    }
  }

  startEdit(field: 'company' | 'order', source?: string | null): void {
    this.open.set(true);
    if (this.editing() === field) {
      return;
    }

    this.editing.set(field);
    this.draft.set(source ?? '');
  }

  value(field: 'company' | 'order', source?: string | null): string {
    return this.editing() === field ? this.draft() : source ?? '';
  }

  changed(source?: string | null): boolean {
    return this.draft() !== (source ?? '');
  }

  setDraft(value: string): void {
    this.draft.set(value);
  }

  cancelEdit(): void {
    this.editing.set(null);
    this.open.set(false);
    this.draft.set('');
  }

  saveEdit(field: 'company' | 'order'): void {
    const source = field === 'company' ? this.note() : this.orderNote();
    if (!this.changed(source)) {
      return;
    }

    (field === 'company' ? this.saveNote : this.saveOrderNote).emit(this.draft());
    this.editing.set(null);
    this.open.set(false);
    this.draft.set('');
  }

  has(value?: string | null): boolean {
    const normalized = (value ?? '').trim().toLowerCase();
    return Boolean(normalized) && normalized !== 'нет заметок';
  }
}
