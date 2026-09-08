import { firstValueFrom, Observable, Subject, takeUntil } from 'rxjs';

export type EditorTicket = Readonly<{
  entityId: number;
  generation: number;
  channel?: string;
  request?: number;
}>;

/** Owns GET lifetimes only. Writes keep their captured identity and are never cancelled here. */
export class EditorSession {
  private entityId: number | null = null;
  private generation = 0;
  private readonly revisions = new Map<string, number>();
  private readonly reads = new Map<string, Subject<void>>();

  open(entityId: number): void {
    this.close();
    this.entityId = entityId;
  }

  close(): void {
    this.entityId = null;
    this.generation += 1;
    this.revisions.clear();
    for (const cancellation of this.reads.values()) {
      cancellation.next();
      cancellation.complete();
    }
    this.reads.clear();
  }

  capture(): EditorTicket | null {
    return this.entityId === null ? null : { entityId: this.entityId, generation: this.generation };
  }

  accepts(ticket: EditorTicket | null): ticket is EditorTicket {
    return ticket !== null
      && ticket.entityId === this.entityId
      && ticket.generation === this.generation
      && (ticket.channel === undefined || this.revisions.get(ticket.channel) === ticket.request);
  }

  beginRead(channel: string): EditorTicket | null {
    const session = this.capture();
    if (!session) {
      return null;
    }
    this.reads.get(channel)?.next();
    this.reads.get(channel)?.complete();
    this.reads.delete(channel);
    const request = (this.revisions.get(channel) ?? 0) + 1;
    this.revisions.set(channel, request);
    return { ...session, channel, request };
  }

  async read<T>(ticket: EditorTicket, source: Observable<T>): Promise<T | undefined> {
    if (!this.accepts(ticket) || ticket.channel === undefined) {
      return undefined;
    }
    const cancellation = new Subject<void>();
    this.reads.set(ticket.channel, cancellation);
    try {
      const value = await firstValueFrom(source.pipe(takeUntil(cancellation)), { defaultValue: undefined });
      return this.accepts(ticket) ? value : undefined;
    } catch (error) {
      if (this.accepts(ticket)) {
        throw error;
      }
      return undefined;
    } finally {
      if (this.reads.get(ticket.channel) === cancellation) {
        this.reads.delete(ticket.channel);
      }
      cancellation.complete();
    }
  }
}
