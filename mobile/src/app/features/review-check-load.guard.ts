export type ReviewCheckLoadKey = {
  orderDetailId: string;
  capabilityToken: string | null;
};

export type ReviewCheckLoadTicket = {
  generation: number;
  key: string;
};

export type ReviewCheckRouteTicket = {
  generation: number;
  key: string;
};

function keyOf(value: ReviewCheckLoadKey): string {
  return `${value.orderDetailId}\u0000${value.capabilityToken ?? ''}`;
}

/**
 * Guards route-driven review-check reads against late delivery. Mutations are
 * intentionally outside this lifecycle because leaving the page must not
 * interrupt a user action that may already have reached the server.
 */
export class ReviewCheckLoadGuard {
  private generation = 0;
  private active = true;
  private destroyed = false;

  activate(): void {
    if (!this.destroyed) {
      this.active = true;
    }
  }

  leave(): void {
    this.active = false;
    this.generation += 1;
  }

  destroy(): void {
    this.destroyed = true;
    this.active = false;
    this.generation += 1;
  }

  invalidate(): void {
    this.generation += 1;
  }

  canStart(): boolean {
    return this.active && !this.destroyed;
  }

  begin(key: ReviewCheckLoadKey): ReviewCheckLoadTicket {
    this.generation += 1;
    return {
      generation: this.generation,
      key: keyOf(key)
    };
  }

  accepts(ticket: ReviewCheckLoadTicket, currentKey: ReviewCheckLoadKey): boolean {
    return this.active
      && !this.destroyed
      && ticket.generation === this.generation
      && ticket.key === keyOf(currentKey);
  }
}

/**
 * Tracks the identity of the routed review check independently from GET
 * generations. Mutations are allowed to finish, but their UI delivery is
 * accepted only while the exact route epoch that started them is current.
 */
export class ReviewCheckRouteGuard {
  private generation = 0;
  private currentKey: string | null = null;
  private initialized = false;
  private destroyed = false;

  change(key: ReviewCheckLoadKey | null): boolean {
    if (this.destroyed) {
      return false;
    }

    const nextKey = key == null ? null : keyOf(key);
    if (this.initialized && nextKey === this.currentKey) {
      return false;
    }

    this.initialized = true;
    this.currentKey = nextKey;
    this.generation += 1;
    return true;
  }

  capture(currentKey: ReviewCheckLoadKey): ReviewCheckRouteTicket | null {
    const serializedKey = keyOf(currentKey);
    if (this.destroyed || !this.initialized || this.currentKey !== serializedKey) {
      return null;
    }

    return {
      generation: this.generation,
      key: serializedKey
    };
  }

  accepts(ticket: ReviewCheckRouteTicket, currentKey: ReviewCheckLoadKey): boolean {
    return !this.destroyed
      && ticket.generation === this.generation
      && ticket.key === this.currentKey
      && ticket.key === keyOf(currentKey);
  }

  destroy(): void {
    this.destroyed = true;
    this.currentKey = null;
    this.generation += 1;
  }
}
