import type { AbstractControl } from '@angular/forms';

/** Selection generations distinguish A -> B -> A; revisions also protect edits made during a request. */
export class DictionaryEditorSession {
  private generation = 0;
  private revision = 0;
  private readonly changes;

  constructor(form: AbstractControl) {
    this.changes = form.valueChanges.subscribe(() => this.revision++);
  }

  change(): void {
    this.generation++;
  }
  destroy(): void {
    this.change();
    this.changes.unsubscribe();
  }
  capture(id: number | null = null) {
    return { id, generation: this.generation, revision: this.revision };
  }
  sameSelection(ticket: DictionaryEditorTicket, id: number | null = null): boolean {
    return ticket.id === id && ticket.generation === this.generation;
  }
  sameDraft(ticket: DictionaryEditorTicket, id: number | null = null): boolean {
    return this.sameSelection(ticket, id) && ticket.revision === this.revision;
  }
}

export type DictionaryEditorTicket = { id: number | null; generation: number; revision: number };
