export function newManualPaymentTaskOperationKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `manual-task-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 14)}`;
}

export class ManualPaymentTaskOperationKeyDraft {
  private operationKey: string;

  constructor(private readonly keyFactory = newManualPaymentTaskOperationKey) {
    this.operationKey = this.keyFactory();
  }

  current(): string {
    return this.operationKey;
  }

  rotate(): string {
    this.operationKey = this.keyFactory();
    return this.operationKey;
  }
}
