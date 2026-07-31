import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AdminNotificationMediaApi,
  NotificationMediaAsset,
  NotificationMediaEvent,
  NotificationMediaRule,
  NotificationRecipientType
} from '../../../core/admin-notification-media.api';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { ToastService } from '../../../shared/toast.service';

type AudienceFilter = 'ALL' | NotificationRecipientType;

@Component({
  selector: 'app-notification-media',
  imports: [CommonModule, FormsModule, AdminLayoutComponent],
  templateUrl: './notification-media.component.html',
  styleUrl: './notification-media.component.scss'
})
export class NotificationMediaComponent {
  private readonly api = inject(AdminNotificationMediaApi);
  private readonly toast = inject(ToastService);

  readonly events = signal<NotificationMediaEvent[]>([]);
  readonly rules = signal<NotificationMediaRule[]>([]);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly audience = signal<AudienceFilter>('ALL');
  readonly newEventCode = signal('');

  readonly filteredRules = computed(() => {
    const audience = this.audience();
    return this.rules().filter((rule) => audience === 'ALL' || rule.recipientType === audience);
  });

  readonly availableEvents = computed(() => {
    const used = new Set(this.rules().map((rule) => rule.eventCode));
    const audience = this.audience();
    return this.events().filter((event) =>
      !used.has(event.code) && (audience === 'ALL' || event.recipientType === audience)
    );
  });

  constructor() {
    this.load();
  }

  setAudience(value: AudienceFilter): void {
    this.audience.set(value);
    this.newEventCode.set('');
  }

  createRule(): void {
    const eventCode = this.newEventCode();
    if (!eventCode || this.saving()) return;
    this.saving.set(true);
    this.api.create({
      eventCode,
      enabled: true,
      imageProbabilityPercent: 100,
      cooldownMinutes: 0
    }).subscribe({
      next: (rule) => {
        this.saving.set(false);
        this.newEventCode.set('');
        this.upsert(rule);
        this.toast.success('Уведомление добавлено', 'Теперь загрузите один или несколько вариантов картинки');
      },
      error: (error) => this.fail('Не удалось добавить уведомление', error)
    });
  }

  saveRule(rule: NotificationMediaRule): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.api.update(rule.id, {
      eventCode: rule.eventCode,
      enabled: rule.enabled,
      imageProbabilityPercent: this.bound(rule.imageProbabilityPercent, 0, 100),
      cooldownMinutes: this.bound(rule.cooldownMinutes, 0, 10080)
    }).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.upsert(updated);
        this.toast.success('Настройки сохранены', updated.eventLabel);
      },
      error: (error) => this.fail('Настройки не сохранены', error)
    });
  }

  upload(rule: NotificationMediaRule, event: Event): void {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (!files.length || this.saving()) return;
    this.saving.set(true);
    this.api.uploadImages(rule.id, files).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.upsert(updated);
        this.toast.success('Картинки загружены', `Добавлено: ${files.length}`);
      },
      error: (error) => this.fail('Картинки не загружены', error)
    });
  }

  toggleAsset(asset: NotificationMediaAsset): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.api.updateAsset(asset.id, {
      active: !asset.active,
      sortOrder: asset.sortOrder
    }).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.upsert(updated);
      },
      error: (error) => this.fail('Картинка не изменена', error)
    });
  }

  replace(asset: NotificationMediaAsset, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || this.saving()) return;
    this.saving.set(true);
    this.api.replaceAsset(asset.id, file).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.upsert(updated);
        this.toast.success('Картинка заменена', file.name);
      },
      error: (error) => this.fail('Картинка не заменена', error)
    });
  }

  deleteAsset(asset: NotificationMediaAsset): void {
    if (!window.confirm('Удалить эту картинку из настройки и S3?') || this.saving()) return;
    this.saving.set(true);
    this.api.deleteAsset(asset.id).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.upsert(updated);
        this.toast.success('Картинка удалена', 'Она больше не будет выбираться');
      },
      error: (error) => this.fail('Картинка не удалена', error)
    });
  }

  deleteRule(rule: NotificationMediaRule): void {
    if (!window.confirm(`Удалить настройку «${rule.eventLabel}» и все её картинки из S3?`) || this.saving()) return;
    this.saving.set(true);
    this.api.deleteRule(rule.id).subscribe({
      next: () => {
        this.saving.set(false);
        this.rules.update((rules) => rules.filter((item) => item.id !== rule.id));
        this.toast.success('Уведомление удалено', rule.eventLabel);
      },
      error: (error) => this.fail('Уведомление не удалено', error)
    });
  }

  sendTest(rule: NotificationMediaRule): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.api.test(rule.id).subscribe({
      next: (result) => {
        this.saving.set(false);
        result.sent
          ? this.toast.success('Тест отправлен', result.message)
          : this.toast.error('Тест не отправлен', result.message);
      },
      error: (error) => this.fail('Тест не отправлен', error)
    });
  }

  roleLabel(role: NotificationRecipientType): string {
    return role === 'MANAGER' ? 'Менеджер' : 'Специалист';
  }

  private load(): void {
    this.loading.set(true);
    this.api.events().subscribe({
      next: (events) => {
        this.events.set(events);
        this.api.rules().subscribe({
          next: (rules) => {
            this.rules.set(rules);
            this.loading.set(false);
          },
          error: (error) => this.fail('Настройки не загружены', error)
        });
      },
      error: (error) => this.fail('События не загружены', error)
    });
  }

  private upsert(updated: NotificationMediaRule): void {
    this.rules.update((rules) => {
      const exists = rules.some((rule) => rule.id === updated.id);
      const result = exists
        ? rules.map((rule) => rule.id === updated.id ? updated : rule)
        : [...rules, updated];
      return result.sort((left, right) => left.eventLabel.localeCompare(right.eventLabel, 'ru'));
    });
  }

  private fail(title: string, error: any): void {
    this.saving.set(false);
    this.loading.set(false);
    const message = error?.error?.detail || error?.error?.message || error?.message || 'Попробуйте ещё раз';
    this.toast.error(title, message);
  }

  private bound(value: number, min: number, max: number): number {
    const number = Number.isFinite(Number(value)) ? Number(value) : min;
    return Math.max(min, Math.min(max, Math.round(number)));
  }
}
