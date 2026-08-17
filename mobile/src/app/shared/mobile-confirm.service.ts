import { Injectable, signal } from '@angular/core';

export interface MobileConfirmRequest {
  title?: string;
  message: string;
  confirmText?: string;
  cancelText?: string;
  danger?: boolean;
  confirmDelayMs?: number;
}

interface ActiveConfirm extends Required<MobileConfirmRequest> {
  resolve: (value: boolean) => void;
}

@Injectable({ providedIn: 'root' })
export class MobileConfirmService {
  readonly active = signal<ActiveConfirm | null>(null);
  readonly confirmArmed = signal(true);
  private armTimer: ReturnType<typeof setTimeout> | undefined;

  confirm(request: MobileConfirmRequest): Promise<boolean> {
    if (this.active()) {
      return Promise.resolve(false);
    }
    const confirmDelayMs = Math.max(0, Math.min(5_000, Math.round(request.confirmDelayMs ?? 0)));
    this.confirmArmed.set(confirmDelayMs === 0);
    if (confirmDelayMs > 0) {
      this.armTimer = setTimeout(() => {
        this.armTimer = undefined;
        if (this.active()) {
          this.confirmArmed.set(true);
        }
      }, confirmDelayMs);
    }
    return new Promise<boolean>((resolve) => {
      this.active.set({
        title: request.title || 'Подтверждение',
        message: request.message,
        confirmText: request.confirmText || 'Подтвердить',
        cancelText: request.cancelText || 'Отмена',
        danger: Boolean(request.danger),
        confirmDelayMs,
        resolve
      });
    });
  }

  close(result: boolean): void {
    const current = this.active();
    if (!current || (result && !this.confirmArmed())) {
      return;
    }

    if (this.armTimer) {
      clearTimeout(this.armTimer);
      this.armTimer = undefined;
    }
    this.active.set(null);
    this.confirmArmed.set(true);
    current.resolve(result);
  }
}
