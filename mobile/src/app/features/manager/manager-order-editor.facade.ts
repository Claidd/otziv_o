import { Injectable, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { OrderEditPayload, OrderUpdateRequest } from '../../core/api.service';
import { EditorSession } from '../../core/editor-session';
import { PageWriteTracker } from '../../core/page-write-tracker';
import { ManagerOrderEditorApi } from '../../core/manager-order-editor.api';
import { MobileConfirmService } from '../../shared/mobile-confirm.service';

/** Provided by each manager page, never as a shared root editor state. */
@Injectable()
export class ManagerOrderEditorFacade {
  private readonly api = inject(ManagerOrderEditorApi);
  private readonly confirm = inject(MobileConfirmService);
  private readonly session = new EditorSession();
  private readonly writes = inject(PageWriteTracker);
  private confirming = false;

  readonly opened = signal(false);
  readonly payload = signal<OrderEditPayload | null>(null);
  readonly draft = signal<OrderUpdateRequest | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly deleting = signal(false);
  readonly error = signal<string | null>(null);

  open(orderId: number): void {
    if (this.saving() || this.deleting()) {
      return;
    }
    this.session.open(orderId);
    this.opened.set(true);
    this.payload.set(null);
    this.draft.set(null);
    this.error.set(null);
    void this.load(orderId);
  }

  close(force = false): void {
    if (!force && (this.saving() || this.deleting())) {
      return;
    }
    this.session.close();
    this.opened.set(false);
    this.payload.set(null);
    this.draft.set(null);
    this.error.set(null);
    this.loading.set(false);
    this.saving.set(false);
    this.deleting.set(false);
    this.confirming = false;
  }

  setField<K extends keyof OrderUpdateRequest>(field: K, value: OrderUpdateRequest[K]): void {
    this.draft.update(draft => draft ? { ...draft, [field]: value } : draft);
  }

  canSave(): boolean {
    const draft = this.draft();
    return Boolean(draft && Number.isFinite(draft.counter));
  }

  async save(afterSave: (payload: OrderEditPayload) => Promise<void>): Promise<void> {
    const ticket = this.session.capture();
    const order = this.payload();
    const draft = this.draft();
    if (!ticket || this.saving() || this.deleting()) {
      return;
    }
    if (!order || order.id !== ticket.entityId || !draft || !this.canSave()) {
      this.error.set('Проверьте данные заказа.');
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    const command: OrderUpdateRequest = {
      ...draft,
      orderComments: draft.orderComments.trim(),
      commentsCompany: draft.commentsCompany.trim()
    };
    try {
      const updated = await firstValueFrom(this.writes.track(this.api.update(ticket.entityId, command)));
      if (!this.session.accepts(ticket)) {
        return;
      }
      this.applyPayload(updated, ticket.entityId);
      await afterSave(updated);
      if (this.session.accepts(ticket)) {
        this.close(true);
      }
    } catch (error) {
      if (this.session.accepts(ticket)) {
        this.error.set(this.message(error, 'Заказ не сохранен.'));
      }
    } finally {
      if (this.session.accepts(ticket)) {
        this.saving.set(false);
      }
    }
  }

  async remove(afterDelete: () => Promise<void>): Promise<void> {
    const ticket = this.session.capture();
    const order = this.payload();
    if (!ticket || !order || order.id !== ticket.entityId || !order.canDelete || this.saving() || this.deleting() || this.confirming) {
      return;
    }
    // Reserve the action before awaiting confirmation to reject duplicate taps.
    this.confirming = true;
    this.error.set(null);
    try {
      const confirmed = await this.confirm.confirm({ title: 'Удалить заказ', message: 'Удалить заказ?', confirmText: 'Удалить', danger: true });
      if (!confirmed || !this.session.accepts(ticket)) {
        return;
      }
      this.deleting.set(true);
      await firstValueFrom(this.writes.track(this.api.delete(ticket.entityId)));
      if (!this.session.accepts(ticket)) {
        return;
      }
      await afterDelete();
      if (this.session.accepts(ticket)) {
        this.close(true);
      }
    } catch (error) {
      if (this.session.accepts(ticket)) {
        this.error.set(this.message(error, 'Заказ не удален.'));
      }
    } finally {
      if (this.session.accepts(ticket)) {
        this.deleting.set(false);
        this.confirming = false;
      }
    }
  }

  private async load(orderId: number): Promise<void> {
    const ticket = this.session.beginRead('payload');
    if (!ticket) {
      return;
    }
    this.loading.set(true);
    try {
      const payload = await this.session.read(ticket, this.api.getEdit(orderId));
      if (payload && this.session.accepts(ticket)) {
        this.applyPayload(payload, ticket.entityId);
      }
    } catch (error) {
      if (this.session.accepts(ticket)) {
        this.error.set(this.message(error, 'Не удалось загрузить редактор заказа.'));
      }
    } finally {
      if (this.session.accepts(ticket)) {
        this.loading.set(false);
      }
    }
  }

  private applyPayload(payload: OrderEditPayload, expectedId: number): void {
    if (payload.id !== expectedId) {
      throw new Error('Ответ сервера относится к другой записи.');
    }
    this.payload.set(payload);
    this.draft.set({
      filialId: payload.filial?.id ?? null,
      workerId: payload.worker?.id ?? null,
      managerId: payload.manager?.id ?? null,
      counter: payload.counter ?? 0,
      orderComments: payload.orderComments ?? '',
      commentsCompany: payload.commentsCompany ?? '',
      complete: !!payload.complete
    });
  }

  private message(error: unknown, fallback: string): string {
    if (error instanceof HttpErrorResponse) {
      const payload = error.error;
      const message = typeof payload === 'string' ? payload.trim()
        : payload && typeof payload === 'object' ? String(payload.message ?? payload.detail ?? '').trim() : '';
      if (message) {
        return message;
      }
      if (error.status) {
        return `${fallback} Код ${error.status}.`;
      }
    }
    return error instanceof Error ? error.message : fallback;
  }
}
