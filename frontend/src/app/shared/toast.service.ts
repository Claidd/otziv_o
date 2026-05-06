import { Injectable, signal } from '@angular/core';
import { sanitizeErrorText } from './api-error-message';

export type ToastType = 'success' | 'error' | 'info';

export type ToastMessage = {
  id: number;
  type: ToastType;
  title: string;
  message?: string;
};

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<ToastMessage[]>([]);

  private nextId = 1;
  private readonly timers = new Map<number, number>();

  success(title: string, message?: string): number {
    return this.show({ type: 'success', title, message }, 3600);
  }

  error(title: string, message?: string): number {
    return this.show({
      type: 'error',
      title: sanitizeErrorText(title, 'Действие не выполнено') ?? 'Действие не выполнено',
      message: sanitizeErrorText(message)
    }, 5600);
  }

  info(title: string, message?: string): number {
    return this.show({ type: 'info', title, message }, 4200);
  }

  dismiss(id: number): void {
    const timer = this.timers.get(id);
    if (timer) {
      window.clearTimeout(timer);
      this.timers.delete(id);
    }

    this.toasts.update((toasts) => toasts.filter((toast) => toast.id !== id));
  }

  private show(toast: Omit<ToastMessage, 'id'>, duration: number): number {
    const id = this.nextId++;
    this.toasts.update((toasts) => [...toasts, { ...toast, id }]);
    this.timers.set(id, window.setTimeout(() => this.dismiss(id), duration));
    return id;
  }
}
