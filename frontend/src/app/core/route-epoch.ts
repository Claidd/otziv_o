export type RouteEpochTicket = {
  generation: number;
  key: string;
};

/** Distinguishes repeated visits to the same routed bearer resource. */
export class RouteEpoch {
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
    return { generation: this.generation, key: this.currentKey };
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
