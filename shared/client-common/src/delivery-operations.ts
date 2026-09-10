import type { DeliveryOperation } from './billing-payments.generated';
export type { DeliveryOperation } from './billing-payments.generated';

export function deliveryOperationMessage(operation: DeliveryOperation | null | undefined): string | null {
  if (!operation) return null;
  if (operation.errorCode === 'finalization_required') return operation.status === 'SENT'
    ? 'Отправка подтверждена. Карточка требует сверки.' : 'Отправка остановлена. Карточка требует сверки.';
  if (operation.errorCode === 'context_changed') return 'Отправка остановлена: изменились карточка или права доступа. Требуется сверка.';
  switch (operation.status) {
    case 'QUEUED': return 'Сообщение сохранено в очереди.';
    case 'SENDING': return 'Сообщение отправляется. Можно продолжать работу.';
    case 'RETRYABLE': return 'Отправка отложена. Система повторит попытку автоматически.';
    case 'UNKNOWN': return 'Исход отправки уточняется. Повторная рассылка заблокирована.';
    case 'FAILED': return 'Сообщение не отправлено. Проверьте результат попыток перед повтором.';
    case 'SENT': return 'Отправка подтверждена.';
    default: return 'Статус отправки требует проверки.';
  }
}

export function deliveryOperationPending(operation: DeliveryOperation | null | undefined): boolean {
  return !!operation && ['QUEUED', 'SENDING', 'RETRYABLE'].includes(operation.status);
}

/** Bounded receipt polling. Only the caller supplies a read; mutations cannot be retried here. */
export class DeliveryStatusWatcher {
  private generation = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;

  cancel(): void {
    this.generation += 1;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  watch<T extends { delivery?: DeliveryOperation | null }>(initial: T, read: () => Promise<T>,
      accept: (value: T) => void, current: () => boolean): void {
    this.cancel();
    if (!deliveryOperationPending(initial.delivery)) return;
    const generation = this.generation;
    const operationId = initial.delivery!.operationId;
    let remaining = 60;
    const poll = async (): Promise<void> => {
      this.timer = null;
      if (generation !== this.generation || !current()) return;
      try {
        const value = await read();
        if (generation !== this.generation || !current() || value.delivery?.operationId !== operationId) return;
        accept(value);
        if (deliveryOperationPending(value.delivery) && --remaining > 0) this.timer = setTimeout(poll, 3000);
      } catch {
        // Keep the last acknowledged state. A failed read never means delivery failed.
      }
    };
    this.timer = setTimeout(poll, 2000);
  }
}

/** A card can survive navigation while its provider operation completes in the background. */
export function managerCardDelivery(card: { delivery?: DeliveryOperation | null; comment?: string | null }): DeliveryOperation | null {
  if (card.delivery) return card.delivery;
  const marker = /^client_(?:message|reply)_delivery_(?:prepared|unknown):([0-9a-f-]{36})(?:;|$)/.exec(card.comment ?? '');
  return marker ? { operationId: marker[1], status: 'UNKNOWN', attempts: 0, errorCode: null } : null;
}

/** Owns independent bounded readers; old pages and replaced operations cannot receive late updates. */
export class CardDeliveryTracker {
  private entries = new Map<number, { id: string; watcher: DeliveryStatusWatcher }>();

  track(cardId: number, initial: DeliveryOperation, read: () => Promise<DeliveryOperation>,
      accept: (value: DeliveryOperation) => void, current: () => boolean): void {
    if (this.entries.get(cardId)?.id === initial.operationId) return;
    this.cancel(cardId);
    const entry = { id: initial.operationId, watcher: new DeliveryStatusWatcher() };
    this.entries.set(cardId, entry);
    const active = () => this.entries.get(cardId) === entry && current();
    accept(initial);
    // A single lookup also restores state after navigation, including an UNKNOWN operation.
    void read().then(value => {
      if (!active() || value.operationId !== entry.id) return;
      accept(value);
      entry.watcher.watch({ delivery: value }, async () => ({ delivery: await read() }),
        updated => accept(updated.delivery), active);
    }).catch(() => { /* Preserve the last known outcome; a failed GET cannot trigger a send. */ });
  }

  cancel(cardId: number): void { this.entries.get(cardId)?.watcher.cancel(); this.entries.delete(cardId); }
  cancelAll(): void { for (const entry of this.entries.values()) entry.watcher.cancel(); this.entries.clear(); }
}
