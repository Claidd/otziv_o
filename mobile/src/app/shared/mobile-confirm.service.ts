import { Injectable, signal } from '@angular/core';

export interface MobileConfirmRequest {
  title?: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  danger?: boolean;
}

interface ActiveConfirm extends Required<MobileConfirmRequest> {
  resolve: (value: boolean) => void;
}

@Injectable({ providedIn: 'root' })
export class MobileConfirmService {
  readonly active = signal<ActiveConfirm | null>(null);

  confirm(request: MobileConfirmRequest): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      this.active.set({
        title: request.title || 'Подтверждение',
        message: request.message,
        confirmText: request.confirmText || 'Подтвердить',
        cancelText: request.cancelText || 'Отмена',
        danger: Boolean(request.danger),
        resolve
      });
    });
  }

  close(result: boolean): void {
    const current = this.active();
    if (!current) {
      return;
    }

    this.active.set(null);
    current.resolve(result);
  }
}
