import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AdminLayoutComponent } from '../../../shared/admin-layout.component';
import { apiErrorMessage } from '../../../shared/api-error-message';
import { LoadErrorCardComponent } from '../../../shared/load-error-card.component';
import { ToastService } from '../../../shared/toast.service';
import {
  AdminPerformer,
  AdminPerformerControl,
  PerformerRolloutSettingsRequest,
  PerformerApi,
  PerformerAssignment
} from '../../../core/performer.api';

@Component({
  selector: 'app-admin-performers',
  imports: [AdminLayoutComponent, LoadErrorCardComponent, FormsModule],
  templateUrl: './admin-performers.component.html',
  styleUrl: './admin-performers.component.scss'
})
export class AdminPerformersComponent implements OnInit {
  private readonly api = inject(PerformerApi);
  private readonly toast = inject(ToastService);

  readonly loading = signal(false);
  readonly saving = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly control = signal<AdminPerformerControl | null>(null);
  readonly statusReason = signal('');
  readonly rolloutEnabled = signal(false);
  readonly rolloutCityIds = signal('');
  readonly rolloutProductIds = signal('');
  readonly manualOrderId = signal('');

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.adminControl().subscribe({
      next: (control) => {
        this.control.set(control);
        this.rolloutEnabled.set(control.rollout?.enabled ?? false);
        this.rolloutCityIds.set(control.rollout?.cityIds ?? '');
        this.rolloutProductIds.set(control.rollout?.productIds ?? '');
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(apiErrorMessage(err, 'Не удалось загрузить контроль исполнителей'));
        this.loading.set(false);
      }
    });
  }

  saveRollout(): void {
    const request: PerformerRolloutSettingsRequest = {
      enabled: this.rolloutEnabled(),
      cityIds: this.rolloutCityIds(),
      productIds: this.rolloutProductIds()
    };
    this.saving.set('rollout');
    this.api.updateRollout(request).subscribe({
      next: (rollout) => {
        const current = this.control();
        if (current) {
          this.control.set({ ...current, rollout });
        }
        this.rolloutEnabled.set(rollout.enabled);
        this.rolloutCityIds.set(rollout.cityIds);
        this.rolloutProductIds.set(rollout.productIds);
        this.toast.success('Тестовый запуск обновлен', rollout.enabled ? 'Раздача включена' : 'Раздача выключена');
        this.saving.set(null);
      },
      error: (err) => {
        this.toast.error('Настройки не сохранены', apiErrorMessage(err, 'Не удалось сохранить тестовый запуск'));
        this.saving.set(null);
      }
    });
  }

  updateStatus(performer: AdminPerformer, status: string): void {
    const key = `status-${performer.id}`;
    this.saving.set(key);
    this.api.updatePerformerStatus(performer.id, status, this.statusReason()).subscribe({
      next: () => {
        this.toast.success('Статус обновлен', performer.fio || performer.username);
        this.saving.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error('Статус не изменен', apiErrorMessage(err, 'Не удалось обновить статус'));
        this.saving.set(null);
      }
    });
  }

  verifyAssignment(assignment: PerformerAssignment): void {
    const key = `verify-${assignment.id}`;
    this.saving.set(key);
    this.api.verifyAssignment(assignment.id).subscribe({
      next: () => {
        this.toast.success('Задание подтверждено', `#${assignment.id}`);
        this.saving.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error('Не подтверждено', apiErrorMessage(err, 'Не удалось подтвердить задание'));
        this.saving.set(null);
      }
    });
  }

  createAssignmentsForOrder(): void {
    const orderId = Number(this.manualOrderId());
    if (!Number.isFinite(orderId) || orderId <= 0) {
      this.toast.error('Укажите номер заказа', 'Нужен числовой ID заказа');
      return;
    }

    this.saving.set('create-assignments');
    this.api.createAssignmentsForOrder(orderId).subscribe({
      next: (result) => {
        this.toast.success('Задания созданы', `Новых заданий: ${result.createdAssignments}`);
        this.saving.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error('Задания не созданы', apiErrorMessage(err, 'Не удалось создать задания по заказу'));
        this.saving.set(null);
      }
    });
  }

  runSchedulerOnce(): void {
    this.saving.set('scheduler');
    this.api.runSchedulerOnce().subscribe({
      next: (result) => {
        this.toast.success(
          'Раздача выполнена',
          `офферов: ${result.offeredAssignments}, истекло: ${result.expiredOffers}, уведомлений: ${result.readyNotifications}`
        );
        this.saving.set(null);
        this.load();
      },
      error: (err) => {
        this.toast.error('Раздача не выполнена', apiErrorMessage(err, 'Не удалось запустить раздачу'));
        this.saving.set(null);
      }
    });
  }
}
