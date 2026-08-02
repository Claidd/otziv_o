export type RouteEpochTicket = {
  generation: number;
  key: string;
};

/**
 * Identifies one visit to a routed resource. The generation makes an A -> B -> A
 * navigation different from the first A, so late mutation responses can finish
 * at the server without being allowed to update the new screen.
 */
export class RouteEpochGuard {
  private generation = 0;
  private currentKey: string | null = null;
  private initialized = false;
  private destroyed = false;

  change(key: string | null): boolean {
    if (this.destroyed) {
      return false;
    }
    if (this.initialized && key === this.currentKey) {
      return false;
    }

    this.initialized = true;
    this.currentKey = key;
    this.generation += 1;
    return true;
  }

  capture(): RouteEpochTicket | null {
    if (this.destroyed || !this.initialized || this.currentKey === null) {
      return null;
    }
    return {
      generation: this.generation,
      key: this.currentKey
    };
  }

  accepts(ticket: RouteEpochTicket): boolean {
    return !this.destroyed
      && ticket.generation === this.generation
      && ticket.key === this.currentKey;
  }

  destroy(): void {
    this.destroyed = true;
    this.currentKey = null;
    this.generation += 1;
  }
}
