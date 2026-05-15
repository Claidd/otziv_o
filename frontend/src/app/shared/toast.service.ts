import { Injectable, signal } from '@angular/core';
import { sanitizeErrorText } from './api-error-message';

export type ToastType = 'success' | 'error' | 'info' | 'warning';

export type ToastMessage = {
  id: number;
  type: ToastType;
  title: string;
  message?: string;
  actions?: ToastAction[];
};

export type ToastAction = {
  label: string;
  routerLink?: string;
  href?: string;
  callback?: () => void;
};

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<ToastMessage[]>([]);

  private nextId = 1;
  private readonly timers = new Map<number, number>();

  success(title: string, message?: string, actions?: ToastAction | ToastAction[]): number {
    const normalizedActions = this.normalizeActions(actions);
    return this.show({ type: 'success', title, message, actions: normalizedActions }, normalizedActions.length ? 0 : 2000);
  }

  error(title: string, message?: string): number {
    return this.show({
      type: 'error',
      title: sanitizeErrorText(title, 'Действие не выполнено') ?? 'Действие не выполнено',
      message: sanitizeErrorText(message)
    }, 5600);
  }

  info(title: string, message?: string, actions?: ToastAction | ToastAction[]): number {
    const normalizedActions = this.normalizeActions(actions);
    return this.show({ type: 'info', title, message, actions: normalizedActions }, normalizedActions.length ? 0 : 4200);
  }

  warning(title: string, message?: string, actions?: ToastAction | ToastAction[]): number {
    const normalizedActions = this.normalizeActions(actions);
    return this.show({ type: 'warning', title, message, actions: normalizedActions }, normalizedActions.length ? 0 : 4200);
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
    if (duration > 0) {
      this.timers.set(id, window.setTimeout(() => this.dismiss(id), duration));
    }
    return id;
  }

  private normalizeActions(actions?: ToastAction | ToastAction[]): ToastAction[] {
    if (!actions) {
      return [];
    }

    return Array.isArray(actions) ? actions.filter(Boolean) : [actions];
  }
}
